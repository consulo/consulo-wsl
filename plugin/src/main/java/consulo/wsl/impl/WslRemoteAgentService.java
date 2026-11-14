package consulo.wsl.impl;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.disposer.Disposable;
import consulo.wsl.agent.AgentSession;
import consulo.wsl.agent.WslAgentDeployer;
import consulo.wsl.agent.WslCommandExecutor;
import consulo.wsl.agent.WslExe;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/**
 * One managed agent per server (key); blocking download, deployment and startup never run on the UI thread.
 */
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public final class WslRemoteAgentService implements Disposable {
    private record Startup(CompletableFuture<AgentSession> session, FutureTask<Void> task, WslAgentOptions options) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Map<String, Startup> mySessions = new HashMap<>();
    private final Executor myExecutor;
    private final WslAgentBinaries myBinaries;
    private boolean myDisposed;

    @Inject
    public WslRemoteAgentService(ApplicationConcurrency concurrency, WslAgentBinaries binaries) {
        myExecutor = concurrency.executor();
        myBinaries = binaries;
    }

    public synchronized CompletableFuture<AgentSession> start(String key, WslAgentOptions options) {
        if (myDisposed) {
            return CompletableFuture.failedFuture(new IllegalStateException("WSL service is disposed"));
        }
        Startup existing = mySessions.get(key);
        if (existing != null) {
            boolean running = !existing.session().isDone()
                || !existing.session().isCompletedExceptionally() && existing.session().join().isAlive();
            if (running && !existing.options().equals(options)) {
                return CompletableFuture.failedFuture(
                    new IOException("Disconnect the WSL agent running in " + options.distribution() + " before changing its options"));
            }
            if (running) {
                return existing.session();
            }
            stop(key);
        }
        CompletableFuture<AgentSession> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                AgentSession session = launch(options);
                if (!result.complete(session)) {
                    session.close();
                }
            }
            catch (Exception e) {
                result.completeExceptionally(e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });
        mySessions.put(key, new Startup(result, task, options));
        myExecutor.execute(task);
        return result;
    }

    private AgentSession launch(WslAgentOptions options) throws IOException, InterruptedException {
        Path executable = WslExe.findExecutable();
        if (executable == null) {
            throw new IOException("wsl.exe was not found. Install WSL with 'wsl --install'.");
        }
        WslCommandExecutor commands = WslExe.executor(executable, options.distribution(), options.user());
        WslAgentDeployer deployer = new WslAgentDeployer(commands, TIMEOUT);
        WslAgentDeployer.Target target = deployer.probe();
        Path binary = options.downloadAgent()
            ? myBinaries.resolve(target.architecture(), false)
            : Path.of(options.customAgentPath());
        WslAgentDeployer.Deployment deployment = deployer.deploy(target, binary);
        return deployer.launch(deployment, options.port(), options.permissions());
    }

    public synchronized void stop(String key) {
        Startup startup = mySessions.remove(key);
        if (startup == null) {
            return;
        }
        // An already completed session closes off the UI thread; an in-flight task is interrupted.
        startup.session().thenAccept(session -> myExecutor.execute(session::close));
        startup.session().cancel(false);
        startup.task().cancel(true);
    }

    @Override
    public void dispose() {
        Startup[] pending;
        synchronized (this) {
            myDisposed = true;
            pending = mySessions.values().toArray(Startup[]::new);
            mySessions.clear();
        }
        for (Startup startup : pending) {
            startup.session().thenAccept(AgentSession::close);
            startup.session().cancel(false);
            startup.task().cancel(true);
        }
    }
}
