package consulo.wsl.agent;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the platform distro launcher pipe. Close after the agent connection and filesystem close. */
public final class AgentSession implements AutoCloseable {
    public record Endpoint(String host, int port, boolean useTls, String certificateFingerprint, String token) {
        @Override
        public String toString() {
            return host + ":" + port + " (TLS, SHA-256 " + certificateFingerprint + ", token=<redacted>)";
        }
    }

    private final Process process;
    private final String token;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final CompletableFuture<String> startup = new CompletableFuture<>();
    private @Nullable Endpoint endpoint;

    private AgentSession(Process process, int port) {
        this.process = process;
        this.port = port;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        CompletableFuture<Void> stdout = Processes.async(() -> {
            readOutput(process.getInputStream(), true);
            return null;
        });
        CompletableFuture<Void> stderr = Processes.async(() -> {
            readOutput(process.getErrorStream(), false);
            return null;
        });
        CompletableFuture.allOf(stdout, stderr).whenComplete((ignored, error) -> {
            if (!startup.isDone()) startup.completeExceptionally(new IOException("Agent ended before startup. " + diagnostics(), error));
        });
    }

    static AgentSession start(Process process, int port, Duration timeout) throws IOException, InterruptedException {
        AgentSession session = new AgentSession(process, port);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process.getOutputStream().write((session.token + "\n").getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
            String fingerprint = session.startup.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            session.verifyEndpoint(fingerprint, deadline);
            session.endpoint = new Endpoint("127.0.0.1", port, true, fingerprint, session.token);
            return session;
        }
        catch (ExecutionException e) {
            throw new IOException("WSL agent failed to start. " + session.diagnostics(), e.getCause());
        }
        catch (TimeoutException e) {
            throw new IOException("WSL agent startup timed out. " + session.diagnostics(), e);
        }
        finally {
            if (session.endpoint == null) session.close();
        }
    }

    public Endpoint endpoint() {
        return java.util.Objects.requireNonNull(endpoint, "Agent session is not started");
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    /** Do not log the endpoint token; it is passed directly into RemoteAgentConnection in the future bridge. */
    public synchronized String diagnostics() {
        return String.join("\n", log);
    }

    private void readOutput(InputStream stream, boolean stdout) throws IOException {
        String fingerprint = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (this) {
                    if (log.size() == 80) log.removeFirst();
                    String redacted = line.replace(token, "<redacted>");
                    log.addLast(redacted.substring(0, Math.min(redacted.length(), 1000)));
                }
                String prefix = "tls: on, certificate SHA-256 ";
                if (stdout && line.startsWith(prefix)) {
                    String digest = line.substring(prefix.length()).replace(":", "").strip();
                    if (digest.matches("[a-fA-F0-9]{64}")) fingerprint = digest;
                }
                if (stdout && fingerprint != null && line.startsWith("listening on 127.0.0.1:" + port + ",")) {
                    startup.complete(fingerprint);
                }
            }
        }
    }

    private void verifyEndpoint(String fingerprint, long deadline) throws IOException, InterruptedException {
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            byte[] expected = HexFormat.of().parseHex(fingerprint);
            X509TrustManager pin = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("Client certificates are not used");
                }
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain.length == 0 || !java.security.MessageDigest.isEqual(expected,
                        WslAgentDeployer.sha256().digest(chain[0].getEncoded()))) {
                        throw new CertificateException("WSL agent certificate does not match the launcher fingerprint");
                    }
                }
            };
            context.init(null, new TrustManager[] {pin}, null);
        }
        catch (GeneralSecurityException e) {
            throw new IOException("Cannot create pinned WSL TLS connection", e);
        }
        IOException last = null;
        while (System.nanoTime() < deadline && process.isAlive()) {
            int remainingMillis = (int) Math.max(1, Math.min(1000, (deadline - System.nanoTime()) / 1_000_000));
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"h2"});
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", port), remainingMillis);
                socket.setSoTimeout(remainingMillis);
                socket.startHandshake();
                if (!"h2".equals(socket.getApplicationProtocol())) throw new IOException("Agent did not negotiate HTTP/2");
                return;
            }
            catch (IOException e) {
                last = e;
            }
            Thread.sleep(100);
        }
        throw new IOException("Cannot reach the pinned WSL agent at Windows localhost:" + port
            + ". Check WSL localhost forwarding and port conflicts. " + diagnostics(), last);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean interrupted = false;
        try {
            // EOF is the Linux guardian's shutdown signal, including after a Windows IDE crash.
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
        catch (IOException ignored) {
            process.destroy();
        }
        catch (InterruptedException ignored) {
            interrupted = true;
            process.destroy();
        }
        finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
