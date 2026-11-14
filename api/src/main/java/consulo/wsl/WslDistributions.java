package consulo.wsl;

import consulo.logging.Logger;
import consulo.wsl.agent.WslExe;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Registered distributions as reported by {@code wsl.exe --list --quiet}, including imported ones. */
public final class WslDistributions {
    private static final Logger LOG = Logger.getInstance(WslDistributions.class);

    private WslDistributions() {
    }

    public static boolean isAvailable() {
        return WslExe.findExecutable() != null;
    }

    /** Blocking (the WSL service may need to start); call off the UI thread. */
    public static List<String> list() {
        Path executable = WslExe.findExecutable();
        if (executable == null) {
            return List.of();
        }
        try {
            return WslExe.listDistributions(executable, Duration.ofSeconds(30));
        }
        catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Cannot list WSL distributions", e);
            return List.of();
        }
    }
}
