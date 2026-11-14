package consulo.wsl.agent;

import java.io.IOException;
import java.util.List;

/** {@link WslExe#executor} runs scripts through wsl.exe; tests supply a local shell. */
@FunctionalInterface
public interface WslCommandExecutor {
    Process start(String script, List<String> arguments) throws IOException;
}
