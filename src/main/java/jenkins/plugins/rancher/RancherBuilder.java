package jenkins.plugins.rancher;


import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.plugins.rancher.action.InServiceStrategy;
import jenkins.plugins.rancher.action.ServiceUpgrade;
import jenkins.plugins.rancher.entity.*;
import jenkins.plugins.rancher.util.Parser;
import jenkins.plugins.rancher.util.ServiceField;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RancherBuilder extends Builder implements SimpleBuildStep {

    public static final String UPGRADED = "upgraded";
    public static final String ACTIVE = "active";
    public static final String ACTIVE1 = "active";

    private final String environmentId;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String service;
    private final String image;
    private final boolean confirm;
    private final String ports;
    private final String environments;
    private RancherClient rancherClient;

    @DataBoundConstructor
    public RancherBuilder(
            String environmentId, String endpoint, String accessKey, String secretKey, String service,
            String image, boolean confirm, String ports, String environments) {
        this.environmentId = environmentId;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.service = service;
        this.image = image;
        this.confirm = confirm;
        this.ports = ports;
        this.environments = environments;
        rancherClient = new RancherClient(endpoint, accessKey, secretKey);
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        String dockerUUID = String.format("docker:%s", Parser.paraser(image, getBuildEnvs(build, listener)));
        ServiceField serviceField = new ServiceField(this.service);
        listener.getLogger().println("Deploy [" + getService() + "(" + dockerUUID + ")] to rancher environment [" + endpoint + "/projects/" + getEnvironmentId() + "]");

        Stack stack = getStack(listener, serviceField, rancherClient);

        Optional<Services> services = rancherClient.services(stack.getId());
        if (!services.isPresent()) {
            throw new AbortException("error happen when stack services");
        }

        Optional<Service> serviceOptional = services.get().getData().stream().filter(service1 -> service1.getName().equals(serviceField.getServiceName())).findAny();
        if (serviceOptional.isPresent()) {
            listener.getLogger().println("upgrade service");
            upgradeService(serviceOptional.get(), dockerUUID);
        } else {
            listener.getLogger().println("create service instance");
            createService(stack, serviceField.getServiceName(), dockerUUID);
        }

    }

    private void upgradeService(Service service, String dockerUUID) throws IOException {
        ServiceUpgrade serviceUpgrade = new ServiceUpgrade();
        InServiceStrategy inServiceStrategy = new InServiceStrategy();

        LaunchConfig launchConfig = service.getLaunchConfig();
        launchConfig.setImageUuid(dockerUUID);

        inServiceStrategy.setLaunchConfig(launchConfig);
        serviceUpgrade.setInServiceStrategy(inServiceStrategy);
        Optional<Service> service1 = rancherClient.upgradeService(getEnvironmentId(), service.getId(), serviceUpgrade);
        if (!service1.isPresent()) {
            throw new AbortException("upgrade service error");
        }

        waitUntilServiceStateIs(service1.get().getId(), UPGRADED);

        if (!confirm) {
            return;
        }

        rancherClient.finishUpgradeService(environmentId, service1.get().getId());
        waitUntilServiceStateIs(service1.get().getId(), ACTIVE);
    }

    private void createService(Stack stack, String serviceName, String dockerUUID) throws IOException {
        Service service = new Service();
        service.setName(serviceName);
        LaunchConfig launchConfig = new LaunchConfig();
        launchConfig.setImageUuid(dockerUUID);
        service.setLaunchConfig(launchConfig);
        Optional<Service> service1 = rancherClient.createService(service, getEnvironmentId(), stack.getId());

        if (!service1.isPresent()) {
            throw new AbortException("upgrade service error");
        }

        waitUntilServiceStateIs(service1.get().getId(), ACTIVE);

    }

    private void waitUntilServiceStateIs(String serviceId, String targetState) throws IOException {
        try {
            int i = 30;
            while (i-- > 0) {
                Optional<Service> checkService = rancherClient.service(serviceId);
                String state = checkService.get().getState();
                if (state.equals(targetState)) {
                    break;
                }
                System.out.println(state);
                Thread.sleep(2000);
            }
            if (i <= 0) {
                throw new AbortException("Service[" + serviceId + "] State not invalidate[" + targetState + "], current state is " + rancherClient.service(serviceId).get().getState());
            }
        } catch (Exception e) {
            throw new AbortException("Timeout to wait service state to " + targetState);
        }

    }

    private Stack getStack(@Nonnull TaskListener listener, ServiceField serviceField, RancherClient rancherClient) throws IOException {
        Optional<Stacks> stacks = rancherClient.stacks(getEnvironmentId());
        if (!stacks.isPresent()) {
            throw new AbortException("error happen when fetch stack in environment<" + getEnvironmentId() + ">");
        }

        Optional<Stack> stack = stacks.get().getData().stream().filter(stackItem -> isEqual(serviceField, stackItem)).findAny();
        if (stack.isPresent()) {
            listener.getLogger().println("Stack already exited. skip");
            return stack.get();
        } else {
            listener.getLogger().println("Stack not existed, create first");
            return createStack(serviceField, rancherClient);
        }
    }

    private Stack createStack(ServiceField serviceField, RancherClient rancherClient) throws IOException {
        Stack stack1 = new Stack();
        stack1.setName(serviceField.getStackName());
        Optional<Stack> stackOptional = rancherClient.createStack(stack1, getEnvironmentId());
        if (!stackOptional.isPresent()) {
            throw new AbortException("error happen when create stack");
        } else {
            return stackOptional.get();
        }
    }

    private boolean isEqual(ServiceField serviceField, Stack stack1) {
        return stack1.getName().equals(serviceField.getStackName());
    }

    private Map<String, String> getBuildEnvs(Run<?, ?> build, TaskListener listener) {
        Map<String, String> envs = new HashMap<>();
        try {
            EnvVars environment = build.getEnvironment(listener);
            environment.keySet().forEach(key -> {
                String value = environment.get(key);
                envs.put(key, value);
            });
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
        }
        return envs;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public boolean isConfirm() {
        return confirm;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getEnvironments() {
        return environments;
    }

    public String getImage() {
        return image;
    }

    public String getPorts() {
        return ports;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getService() {
        return service;
    }


    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        public String getDisplayName() {
            return "Deploy/Upgrade Rancher Service";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}