package consulo.wsl.impl;

import consulo.remoteServer.platformAware.LaunchedRemoteAgent;
import consulo.remoteServer.platformAware.RemoteAgentLauncher;
import consulo.wsl.agent.AgentSession;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Deploys and starts the agent in the distribution of one WSL server; the platform connection owns the started agent.
 */
final class WslAgentLauncher implements RemoteAgentLauncher {
    private final String myServerId;
    private final WslServerConfiguration myConfiguration;
    private final WslRemoteAgentService myAgentService;

    WslAgentLauncher(String serverId, WslServerConfiguration configuration, WslRemoteAgentService agentService) {
        myServerId = serverId;
        myConfiguration = configuration;
        myAgentService = agentService;
    }

    @Override
    public CompletableFuture<LaunchedRemoteAgent> launch() {
        WslAgentOptions options = myConfiguration.toOptions();
        if (options.distribution().isEmpty()) {
            return CompletableFuture.failedFuture(new IOException("Select a WSL distribution in the server settings"));
        }
        return myAgentService.start(myServerId, options).thenApply(session -> new Launched(session.endpoint()));
    }

    private final class Launched implements LaunchedRemoteAgent {
        private final AgentSession.Endpoint myEndpoint;

        private Launched(AgentSession.Endpoint endpoint) {
            myEndpoint = endpoint;
        }

        @Override
        public String getHost() {
            return myEndpoint.host();
        }

        @Override
        public int getPort() {
            return myEndpoint.port();
        }

        @Override
        public boolean isUseTls() {
            return myEndpoint.useTls();
        }

        @Override
        public String getCertificateFingerprint() {
            return myEndpoint.certificateFingerprint();
        }

        @Override
        public @Nullable String getToken() {
            return myEndpoint.token();
        }

        @Override
        public void close() {
            // closing the launcher pipe stops the agent and removes its session workspace
            myAgentService.stop(myServerId);
        }
    }
}
