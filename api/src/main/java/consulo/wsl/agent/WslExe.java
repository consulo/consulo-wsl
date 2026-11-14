package consulo.wsl.agent;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Talks to the modern {@code wsl.exe} launcher: enumerates registered distributions and runs POSIX scripts in them.
 * <p>
 * Scripts and their arguments never pass through Windows command-line quoting: they are POSIX-quoted, base64-encoded and
 * decoded by a fixed bootstrap that contains no double quotes, so the Windows argv round trip is lossless.
 */
public final class WslExe {
    private static final Pattern DISTRIBUTION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern USER_NAME = Pattern.compile("[a-z_][a-z0-9_.-]*\\$?");
    private static final String BOOTSTRAP_PREFIX = "set -f;IFS=;eval $(echo ";
    private static final String BOOTSTRAP_SUFFIX = "|base64 -d)";

    private WslExe() {
    }

    public static @Nullable Path findExecutable() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) {
            return null;
        }
        Path executable = Path.of(systemRoot, "System32", "wsl.exe");
        return Files.isRegularFile(executable) ? executable : null;
    }

    public static List<String> listDistributions(Path executable, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--list", "--quiet");
        builder.environment().put("WSL_UTF8", "1");
        Processes.Output output = Processes.run(builder.start(), timeout, stream -> {
        });
        return parseListing(output.stdout());
    }

    /** {@code wsl.exe --list} writes UTF-16LE unless WSL_UTF8 is honored by the installed version. */
    public static List<String> parseListing(byte[] bytes) {
        int zeros = 0;
        for (byte value : bytes) {
            if (value == 0) {
                zeros++;
            }
        }
        String text = zeros * 4 > bytes.length ? new String(bytes, StandardCharsets.UTF_16LE) : new String(bytes, StandardCharsets.UTF_8);
        List<String> result = new ArrayList<>();
        for (String line : text.replace("﻿", "").split("\r?\n|\r")) {
            String name = line.replace("\0", "").strip();
            if (DISTRIBUTION_NAME.matcher(name).matches() && !result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    public static boolean isValidDistributionName(String name) {
        return DISTRIBUTION_NAME.matcher(name).matches();
    }

    public static boolean isValidUserName(String name) {
        return name.isEmpty() || USER_NAME.matcher(name).matches();
    }

    /** Full Windows argv; {@code user} empty means the distribution default user. */
    public static List<String> command(Path executable, String distribution, String user, String script, List<String> arguments) {
        if (!isValidDistributionName(distribution)) {
            throw new IllegalArgumentException("Invalid WSL distribution name: " + distribution);
        }
        if (!isValidUserName(user)) {
            throw new IllegalArgumentException("Invalid Linux user name: " + user);
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--distribution");
        command.add(distribution);
        if (!user.isEmpty()) {
            command.add("--user");
            command.add(user);
        }
        command.add("--exec");
        command.add("/bin/sh");
        command.add("-c");
        command.add(bootstrap(script, arguments));
        return command;
    }

    /**
     * A {@code sh -c} body which decodes and evaluates the script with its positional parameters.
     * With an empty IFS and globbing disabled the command substitution is neither split nor expanded.
     */
    public static String bootstrap(String script, List<String> arguments) {
        StringBuilder body = new StringBuilder("unset IFS\nset +f\nset --");
        for (String argument : arguments) {
            body.append(' ').append(quote(argument));
        }
        body.append('\n').append(script);
        String encoded = Base64.getEncoder().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
        return BOOTSTRAP_PREFIX + encoded + BOOTSTRAP_SUFFIX;
    }

    static String quote(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL in shell argument");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Runs scripts in one distribution through {@code wsl.exe}. */
    public static WslCommandExecutor executor(Path executable, String distribution, String user) {
        return (script, arguments) -> new ProcessBuilder(command(executable, distribution, user, script, arguments)).start();
    }
}
