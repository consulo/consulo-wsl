package consulo.wsl.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Exercises the same sh -c body that wsl.exe --exec receives, using a local POSIX shell. */
public final class WslAgentDeployerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("consulo wsl ' tests ");
        List<String> invocations = Collections.synchronizedList(new ArrayList<>());
        WslCommandExecutor commands = (script, arguments) -> {
            String command = WslExe.bootstrap(script, arguments);
            invocations.add(command);
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
            builder.environment().put("HOME", root.resolve("home").toString());
            builder.environment().put("XDG_CACHE_HOME", root.resolve("cache spaces ' $ &").toString());
            return builder.start();
        };
        try {
            var deployer = new WslAgentDeployer(commands, Duration.ofSeconds(5));
            Path binary = root.resolve("source remote-agent.exe");
            Files.copy(Path.of("/bin/true"), binary);
            var first = deployer.deploy(binary);
            check(!first.reused(), "first deployment uploads");
            check(first.binaryPath().endsWith("/remote-agent.exe"), "remote executable filename");
            check(Files.isExecutable(Path.of(first.binaryPath())), "uploaded binary executable");
            check(deployer.deploy(binary).reused(), "cache validated and reused");
            Files.writeString(Path.of(first.binaryPath()), "corrupted");
            check(!deployer.deploy(binary).reused(), "corrupted cache repaired");

            var one = Processes.async(() -> deployer.deploy(binary));
            var two = Processes.async(() -> deployer.deploy(binary));
            check(one.get().sha256().equals(two.get().sha256()), "concurrent deployment agrees");
            String zeros = "0".repeat(64);
            expectIo("checksum", () -> Processes.run(commands.start(WslAgentDeployer.script("deploy.sh"),
                List.of(first.cacheRoot(), zeros, "upload")), Duration.ofSeconds(3),
                stream -> stream.write("bad bytes".getBytes(StandardCharsets.UTF_8))));
            try (var files = Files.walk(Path.of(first.cacheRoot()))) {
                check(files.noneMatch(path -> path.getFileName().toString().startsWith(".upload.")), "failed upload removes temporary file");
            }
            Path marker = root.resolve("must-not-exist");
            String malicious = "' ; touch " + marker + "; $(uname) $HOME\nsecond line";
            var echo = Processes.run(commands.start("printf '%s' \"$1\"", List.of(malicious)), Duration.ofSeconds(3), stream -> {});
            check(WslAgentDeployer.text(echo.stdout()).equals(malicious) && !Files.exists(marker), "POSIX command quoting preserves literal values");

            Path windows = root.resolve("windows-agent.exe");
            Files.writeString(windows, "MZ Windows executable");
            expectIo("Linux ELF", () -> deployer.deploy(windows));
            byte[] wrongArchitecture = Files.readAllBytes(binary);
            wrongArchitecture[18] = (byte) (first.architecture().equals("x86_64") ? 183 : 62);
            Path wrong = root.resolve("wrong architecture");
            Files.write(wrong, wrongArchitecture);
            expectIo("does not match", () -> deployer.deploy(wrong));

            // Startup contracts are independent of ELF validation; use small scripts for failure/timeout fixtures.
            Path script = root.resolve("fake agent");
            Files.writeString(script, "#!/bin/sh\necho expected-loader-failure >&2\nexit 9\n");
            script.toFile().setExecutable(true);
            var failure = new WslAgentDeployer.Deployment(first.architecture(), first.cacheRoot(), script.toString(), first.sha256(), false);
            expectIo("expected-loader-failure", () -> deployer.launch(failure, freePort()));
            check(sessionCount(first) == 0, "startup failure cleans session");
            Files.writeString(script, "#!/bin/sh\nexec sleep 60\n");
            long start = System.nanoTime();
            expectIo("timed out", () -> new WslAgentDeployer(commands, Duration.ofMillis(500)).launch(failure, freePort()));
            check(System.nanoTime() - start < Duration.ofSeconds(4).toNanos(), "startup deadline stops child");
            check(sessionCount(first) == 0, "startup deadline cleans session");

            start = System.nanoTime();
            expectIo("timed out", () -> Processes.run(new ProcessBuilder("sleep", "60").start(), Duration.ofMillis(300),
                stream -> stream.write(new byte[2 * 1024 * 1024])));
            check(System.nanoTime() - start < Duration.ofSeconds(3).toNanos(), "blocked stdin upload has deadline");

            testWslExe();
            testBinaryCache(root);

            if (args.length == 1) testRealAgent(deployer, Path.of(args[0]), invocations);
            else System.out.println("Real-agent test skipped; pass its Linux executable path to test.sh.");
            System.out.println("Passed " + checks + " checks.");
        }
        finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testRealAgent(WslAgentDeployer deployer, Path binary, List<String> invocations) throws Exception {
        var deployment = deployer.deploy(binary);
        AgentSession.Endpoint endpoint;
        try (AgentSession session = deployer.launch(deployment, 0, "fs, process")) {
            endpoint = session.endpoint();
            check(session.isAlive(), "real Rust agent passed pinned TLS + HTTP2 startup");
            check(endpoint.useTls() && endpoint.token().length() == 64, "TLS and 256-bit token");
            check(session.diagnostics().contains("token: required"), "agent token supplied through stdin and environment");
            check(session.diagnostics().lines().anyMatch(line -> line.startsWith("permissions:") && line.contains("fs")
                && line.contains("process") && !line.contains("http")), "permissions passed to agent: " + session.diagnostics());
            check(!endpoint.toString().contains(endpoint.token()) && !session.diagnostics().contains(endpoint.token()), "token redacted");
            check(invocations.stream().noneMatch(command -> command.contains(endpoint.token())), "token absent from launcher argv");
            check(deployer.deploy(binary).reused(), "running agent cache reused");
        }
        check(sessionCount(deployment) == 0, "real agent stopped and TLS/session workspace removed");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 300);
            throw new AssertionError("Agent port remains open");
        }
        catch (IOException expected) { checks++; }
        System.out.println("Real Linux agent deployment, startup and shutdown verified.");
    }

    private static void testWslExe() throws Exception {
        byte[] utf16 = "\uFEFFUbuntu-24.04\r\nDebian\r\nmy.imported_distro\r\n\r\n".getBytes(StandardCharsets.UTF_16LE);
        check(WslExe.parseListing(utf16).equals(List.of("Ubuntu-24.04", "Debian", "my.imported_distro")), "UTF-16LE wsl --list parsed");
        byte[] windowsCapture = {0x55, 0, 0x62, 0, 0x75, 0, 0x6e, 0, 0x74, 0, 0x75, 0, 0x0d, 0, 0x0a, 0};
        check(WslExe.parseListing(windowsCapture).equals(List.of("Ubuntu")), "real Windows wsl --list --quiet capture parsed");
        check(WslExe.parseListing("Ubuntu\nAlpine\n".getBytes(StandardCharsets.UTF_8)).equals(List.of("Ubuntu", "Alpine")),
            "UTF-8 wsl --list parsed");
        check(WslExe.parseListing("Windows Subsystem for Linux has no installed distributions.\r\n".getBytes(StandardCharsets.UTF_16LE))
            .isEmpty(), "no-distribution message is not a distribution");

        Path exe = Path.of("C:\\Windows\\System32\\wsl.exe");
        List<String> command = WslExe.command(exe, "Ubuntu", "root", "echo \"$1\" 'x' \\", List.of("a \"b\" \\", "c'd"));
        check(command.subList(1, 9).equals(List.of("--distribution", "Ubuntu", "--user", "root", "--exec", "/bin/sh", "-c", command.get(8))),
            "wsl.exe argv shape " + command);
        String body = command.get(8);
        check(body.indexOf('"') < 0 && body.indexOf('\\') < 0 && !body.endsWith(" "), "bootstrap survives Windows argv quoting");
        check(!WslExe.command(exe, "Ubuntu", "", "true", List.of()).contains("--user"), "default user omits --user");
        expectIllegal(() -> WslExe.command(exe, "-d evil", "", "true", List.of()));
        expectIllegal(() -> WslExe.command(exe, "Ubuntu", "root --exec", "true", List.of()));

        check(WslAgentDeployer.normalizePermissions(" FS, process ").equals("fs,process"), "permissions normalized");
        check(WslAgentDeployer.normalizePermissions("").equals("*"), "empty permissions mean all");
        expectIllegal(() -> WslAgentDeployer.normalizePermissions("fs;rm -rf"));
    }

    private static void testBinaryCache(Path root) throws Exception {
        Path source = root.resolve("archive source");
        Files.createDirectories(source.resolve("pkg"));
        Files.write(source.resolve("pkg/remote-agent"), new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 3});
        Files.writeString(source.resolve("pkg/README"), "readme");
        Path archive = source.resolve("remote-agent.tar.gz");
        Process tar = new ProcessBuilder("tar", "czf", archive.toString(), "-C", source.resolve("pkg").toString(), "README", "remote-agent")
            .inheritIO().start();
        check(tar.waitFor() == 0, "tar fixture created");
        byte[] archiveBytes = Files.readAllBytes(archive);

        long[] now = {1_000_000};
        List<String> requests = new ArrayList<>();
        boolean[] offline = {false};
        String[] serverEtag = {"\"v1\""};
        AgentBinaryCache.Fetcher fetcher = (url, etag) -> {
            requests.add(url + " " + etag);
            if (offline[0]) {
                throw new IOException("offline");
            }
            return serverEtag[0].equals(etag) ? null : new AgentBinaryCache.Response(archiveBytes, serverEtag[0]);
        };
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now[0]); }
        };
        AgentBinaryCache cache = new AgentBinaryCache(root.resolve("windows cache"), "https://example.invalid/bin", fetcher, Duration.ofHours(1), clock);
        check(cache.url("x86_64").equals("https://example.invalid/bin/linux-x86_64/remote-agent.tar.gz"), "download url");

        AgentBinaryCache.Result first = cache.resolve("x86_64", false);
        check(first.downloaded() && Files.readAllBytes(first.binary())[4] == 1, "agent downloaded and extracted from tar.gz");
        check(requests.equals(List.of("https://example.invalid/bin/linux-x86_64/remote-agent.tar.gz null")), "first request unconditional");
        check(!cache.resolve("x86_64", false).downloaded() && requests.size() == 1, "fresh cache used without a request");

        now[0] += Duration.ofHours(2).toMillis();
        check(!cache.resolve("x86_64", false).downloaded() && requests.get(1).endsWith(" \"v1\""), "stale cache revalidated with ETag (304)");
        check(!cache.resolve("x86_64", false).downloaded() && requests.size() == 2, "304 renews freshness");

        serverEtag[0] = "\"v2\"";
        check(cache.resolve("x86_64", true).downloaded(), "forced refresh downloads a new version");

        offline[0] = true;
        now[0] += Duration.ofHours(2).toMillis();
        AgentBinaryCache.Result offlineResult = cache.resolve("x86_64", false);
        check(offlineResult.refreshFailure() != null && Files.isRegularFile(offlineResult.binary()), "offline falls back to cached agent");
        expectIo("Cannot download", () -> cache.resolve("aarch64", false));

        offline[0] = false;
        AgentBinaryCache broken = new AgentBinaryCache(root.resolve("broken cache"), "https://example.invalid/",
            (url, etag) -> new AgentBinaryCache.Response(java.util.zip.Deflater.class.getName().getBytes(), null), Duration.ofHours(1), clock);
        expectIo("", () -> broken.resolve("x86_64", false));
        try (var files = Files.list(root.resolve("windows cache/linux-x86_64"))) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "no temporary download files left");
        }
    }

    private static void expectIllegal(Action action) throws Exception {
        try {
            action.run();
        }
        catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static long sessionCount(WslAgentDeployer.Deployment deployment) throws IOException {
        try (var files = Files.list(Path.of(deployment.cacheRoot(), "sessions"))) { return files.count(); }
    }
    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expectIo(String message, Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) {
            check(String.valueOf(expected.getMessage()).contains(message), "Expected '" + message + "': " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected IOException containing " + message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
