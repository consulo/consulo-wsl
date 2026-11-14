package consulo.wsl.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Deploys through a {@link WslCommandExecutor}; owns no Windows discovery or launcher logic. */
public final class WslAgentDeployer {
    public record Target(String architecture, String cacheRoot) {
    }

    public record Deployment(String architecture, String cacheRoot, String binaryPath, String sha256, boolean reused) {
    }

    private static final Pattern PERMISSIONS = Pattern.compile("\\*|[a-z]+(,[a-z]+)*");

    private final WslCommandExecutor myCommands;
    private final Duration myTimeout;

    public WslAgentDeployer(WslCommandExecutor commands, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Positive timeout required");
        }
        myCommands = commands;
        myTimeout = timeout;
    }

    /** Starts the distribution if needed and reports its architecture and agent cache directory. */
    public Target probe() throws IOException, InterruptedException {
        String probe = text(run("probe.sh", List.of(), stream -> {
        }).stdout());
        String line = probe.lines()
            .filter(it -> it.startsWith("CONSULO_WSL_PROBE\t"))
            .findFirst()
            .orElseThrow(() -> new IOException("No WSL probe reply: " + probe.strip()));
        String[] fields = line.split("\t", -1);
        if (fields.length != 3) {
            throw new IOException("Invalid WSL probe reply");
        }
        String architecture = switch (fields[1]) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IOException("Unsupported WSL architecture: " + fields[1]);
        };
        String root = fields[2];
        if (!root.startsWith("/") || root.indexOf('\r') >= 0) {
            throw new IOException("Invalid WSL cache root");
        }
        return new Target(architecture, root);
    }

    public Deployment deploy(Path binary) throws IOException, InterruptedException {
        return deploy(probe(), binary);
    }

    public Deployment deploy(Target target, Path binary) throws IOException, InterruptedException {
        if (!Files.isRegularFile(binary)) {
            throw new IOException("Missing Linux remote agent: " + binary);
        }
        verifyLinuxBinary(binary, target.architecture());
        String digest;
        try (InputStream stream = Files.newInputStream(binary)) {
            MessageDigest sha256 = sha256();
            byte[] buffer = new byte[64 * 1024];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                sha256.update(buffer, 0, size);
            }
            digest = HexFormat.of().formatHex(sha256.digest());
        }
        String root = target.cacheRoot();
        boolean reused = text(run("deploy.sh", List.of(root, digest, "check"), stream -> {
        }).stdout()).lines().anyMatch("CONSULO_WSL_CACHED"::equals);
        if (!reused) {
            String reply = text(run("deploy.sh", List.of(root, digest, "upload"), stream -> Files.copy(binary, stream)).stdout());
            if (reply.lines().noneMatch("CONSULO_WSL_DEPLOYED"::equals)) {
                throw new IOException("No deployment acknowledgement");
            }
        }
        return new Deployment(target.architecture(), root, root + "/bin/" + digest + "/remote-agent.exe", digest, reused);
    }

    public AgentSession launch(Deployment deployment, int port) throws IOException, InterruptedException {
        return launch(deployment, port, "*");
    }

    /**
     * Port 0 chooses a Windows candidate; Linux binding and TLS pinning detect conflicts.
     *
     * @param permissions {@code *} or comma-separated agent permission groups
     */
    public AgentSession launch(Deployment deployment, int port, String permissions) throws IOException, InterruptedException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        String normalized = normalizePermissions(permissions);
        if (port != 0) {
            return launchOnce(deployment, port, normalized);
        }
        IOException failure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                candidate = socket.getLocalPort();
            }
            try {
                return launchOnce(deployment, candidate, normalized);
            }
            catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().contains("already used")) {
                    throw e;
                }
                failure = e;
            }
        }
        throw new IOException("Unable to allocate a WSL agent port", failure);
    }

    public static String normalizePermissions(String permissions) {
        String normalized = permissions.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return "*";
        }
        if (!PERMISSIONS.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Agent permissions must be * or comma-separated groups: " + permissions);
        }
        return normalized;
    }

    private AgentSession launchOnce(Deployment deployment, int port, String permissions) throws IOException, InterruptedException {
        Process process = myCommands.start(script("launch.sh"),
            List.of(deployment.binaryPath(), Integer.toString(port), deployment.cacheRoot(), permissions));
        return AgentSession.start(process, port, myTimeout);
    }

    private Processes.Output run(String script, List<String> args, Processes.Input input) throws IOException, InterruptedException {
        return Processes.run(myCommands.start(script(script), args), myTimeout, input);
    }

    static String script(String name) throws IOException {
        try (InputStream stream = WslAgentDeployer.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing script resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static void verifyLinuxBinary(Path binary, String architecture) throws IOException {
        byte[] header;
        try (InputStream stream = Files.newInputStream(binary)) {
            header = stream.readNBytes(20);
        }
        if (header.length < 20 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
            throw new IOException("Select the Linux ELF remote agent. A Windows remote-agent.exe runs on Windows, even through WSL interop.");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if (header[4] != 2 || header[5] != 1 || machine != (architecture.equals("x86_64") ? 62 : 183)) {
            throw new IOException("Linux agent binary does not match WSL architecture " + architecture);
        }
    }
}
