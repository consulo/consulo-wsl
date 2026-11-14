package consulo.wsl.impl;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.execution.configuration.fragment.RunConfigurationFragmentEditor;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.remoteServer.RemoteServerConfigurable;
import consulo.remoteServer.configuration.RemoteServer;
import consulo.remoteServer.configuration.deployment.DeploymentConfigurationBase;
import consulo.remoteServer.configuration.deployment.DeploymentConfigurator;
import consulo.remoteServer.configuration.deployment.DeploymentSource;
import consulo.remoteServer.platformAware.PlatformAwareServerType;
import consulo.remoteServer.platformAware.RemoteAgentConnectorFactory;
import consulo.remoteServer.runtime.ServerConnector;
import consulo.remoteServer.runtime.ServerTaskExecutor;
import consulo.wsl.icon.WslIconGroup;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A WSL distribution in Clouds: connecting deploys and starts the remote agent in it.
 */
@ExtensionImpl
public class WslServerType extends PlatformAwareServerType<WslServerConfiguration> {
    private final ApplicationConcurrency myConcurrency;
    private final WslRemoteAgentService myAgentService;
    private final RemoteAgentConnectorFactory myConnectorFactory;

    @Inject
    public WslServerType(ApplicationConcurrency concurrency, WslRemoteAgentService agentService, RemoteAgentConnectorFactory connectorFactory) {
        super("wsl", "WslDeployment", LocalizeValue.localizeTODO("WSL"), WslIconGroup.linux());
        myConcurrency = concurrency;
        myAgentService = agentService;
        myConnectorFactory = connectorFactory;
    }

    @Override
    public WslServerConfiguration createDefaultConfiguration() {
        return new WslServerConfiguration();
    }

    @Override
    public RemoteServerConfigurable createServerConfigurable(WslServerConfiguration configuration) {
        return new WslServerConfigurable(configuration, myConcurrency);
    }

    @Override
    public DeploymentConfigurator<?, WslServerConfiguration> createDeploymentConfigurator(Project project) {
        return new WslDeploymentConfigurator();
    }

    @Override
    public boolean mayHaveProjectSpecificDeploymentSources() {
        return false;
    }

    @Override
    public ServerConnector<?> createConnector(RemoteServer<WslServerConfiguration> server, ServerTaskExecutor asyncTasksExecutor) {
        // the platform is known by the id of the server - its name and distribution may change
        return myConnectorFactory.createConnector(server.getId(),
            new WslAgentLauncher(server.getId(), server.getConfiguration(), myAgentService));
    }

    @Override
    public ServerConnector<?> createConnector(WslServerConfiguration configuration, ServerTaskExecutor asyncTasksExecutor) {
        throw new UnsupportedOperationException("A WSL platform connects as a server - by its id");
    }

    private static class WslDeploymentConfigurator extends DeploymentConfigurator<DeploymentConfigurationBase, WslServerConfiguration> {
        @Override
        public List<DeploymentSource> getAvailableDeploymentSources() {
            return List.of();
        }

        @Override
        public DeploymentConfigurationBase createDefaultConfiguration(DeploymentSource source) {
            return new DeploymentConfigurationBase() {
            };
        }

        @Override
        public @Nullable RunConfigurationFragmentEditor<DeploymentConfigurationBase> createEditor(DeploymentSource source,
                                                                                                  @Nullable RemoteServer<WslServerConfiguration> server) {
            return null;
        }
    }
}
