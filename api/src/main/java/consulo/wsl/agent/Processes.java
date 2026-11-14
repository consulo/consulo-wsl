package consulo.wsl.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded capture and a deadline covering both stdin upload and process execution. */
final class Processes {
    private static final int OUTPUT_LIMIT = 256 * 1024;

    @FunctionalInterface
    interface Input {
        void write(OutputStream stream) throws IOException;
    }

    record Output(byte[] stdout, byte[] stderr) {}

    static Output run(Process process, Duration timeout, Input input) throws IOException, InterruptedException {
        CompletableFuture<byte[]> stdout = async(() -> capture(process.getInputStream()));
        CompletableFuture<byte[]> stderr = async(() -> capture(process.getErrorStream()));
        CompletableFuture<Void> upload = async(() -> {
            try (OutputStream stream = process.getOutputStream()) {
                input.write(stream);
            }
            return null;
        });
        try {
            CompletableFuture.allOf(process.onExit(), upload, stdout, stderr).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Output output = new Output(stdout.get(), stderr.get());
            if (process.exitValue() != 0) {
                throw new IOException("WSL command exited with " + process.exitValue() + ": "
                    + WslAgentDeployer.text(output.stderr()).strip() + " "
                    + WslAgentDeployer.text(output.stdout()).strip());
            }
            return output;
        }
        catch (TimeoutException e) {
            throw new IOException("WSL command timed out after " + timeout, e);
        }
        catch (ExecutionException e) {
            throw new IOException("WSL command I/O failed", e.getCause());
        }
        finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            // Killing the process unblocks a writer even when a large upload filled its pipe.
            close(process.getOutputStream());
            close(process.getInputStream());
            close(process.getErrorStream());
        }
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); }
        catch (IOException ignored) {}
    }

    static <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(work.call());
            }
            catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }, "consulo-wsl-io");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static byte[] capture(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                result.write(buffer, 0, Math.min(size, OUTPUT_LIMIT - result.size()));
            }
            return result.toByteArray();
        }
    }
}
