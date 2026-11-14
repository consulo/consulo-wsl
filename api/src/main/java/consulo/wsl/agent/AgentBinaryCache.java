package consulo.wsl.agent;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

/**
 * Windows-side cache of the published Linux remote agent ({@code linux-<arch>/remote-agent.tar.gz} in consulo/binaries).
 * The archive is revalidated with its ETag after {@code refreshInterval}; a cached agent is used when offline.
 */
public final class AgentBinaryCache {
    public static final String DEFAULT_BASE_URL = "https://github.com/consulo/binaries/raw/master/";

    /** A conditional GET; returns {@code null} when the server answered 304 Not Modified. */
    @FunctionalInterface
    public interface Fetcher {
        @Nullable Response fetch(String url, @Nullable String etag) throws IOException;
    }

    public record Response(byte[] body, @Nullable String etag) {
    }

    public record Result(Path binary, boolean downloaded, @Nullable IOException refreshFailure) {
    }

    private static final String BINARY = "remote-agent";
    private static final String METADATA = "remote-agent.properties";
    private static final int TAR_BLOCK = 512;

    private final Path myRoot;
    private final String myBaseUrl;
    private final Fetcher myFetcher;
    private final Duration myRefreshInterval;
    private final Clock myClock;

    public AgentBinaryCache(Path root, String baseUrl, Fetcher fetcher, Duration refreshInterval, Clock clock) {
        myRoot = root;
        myBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        myFetcher = fetcher;
        myRefreshInterval = refreshInterval;
        myClock = clock;
    }

    public String url(String architecture) {
        return myBaseUrl + "linux-" + architecture + "/remote-agent.tar.gz";
    }

    public synchronized Result resolve(String architecture, boolean forceRefresh) throws IOException {
        Path directory = myRoot.resolve("linux-" + architecture);
        Path binary = directory.resolve(BINARY);
        Path metadataFile = directory.resolve(METADATA);
        Properties metadata = new Properties();
        if (Files.isRegularFile(metadataFile)) {
            try (InputStream stream = Files.newInputStream(metadataFile)) {
                metadata.load(stream);
            }
        }
        boolean cached = Files.isRegularFile(binary);
        long checked = parseLong(metadata.getProperty("checked"));
        long now = myClock.millis();
        if (cached && !forceRefresh && now - checked >= 0 && now - checked < myRefreshInterval.toMillis()) {
            return new Result(binary, false, null);
        }

        Response response;
        try {
            response = myFetcher.fetch(url(architecture), cached ? metadata.getProperty("etag") : null);
        }
        catch (IOException e) {
            if (cached) {
                return new Result(binary, false, e);
            }
            throw new IOException("Cannot download the WSL remote agent from " + url(architecture) + ": " + e.getMessage(), e);
        }

        Files.createDirectories(directory);
        boolean downloaded = false;
        if (response != null) {
            byte[] executable = extract(response.body());
            Path temporary = Files.createTempFile(directory, ".download", ".tmp");
            try {
                Files.write(temporary, executable);
                move(temporary, binary);
            }
            finally {
                Files.deleteIfExists(temporary);
            }
            if (response.etag() != null) {
                metadata.setProperty("etag", response.etag());
            }
            else {
                metadata.remove("etag");
            }
            downloaded = true;
        }
        else if (!cached) {
            throw new IOException("Server reported the WSL remote agent unchanged, but no cached copy exists");
        }
        metadata.setProperty("checked", Long.toString(now));
        Path temporary = Files.createTempFile(directory, ".metadata", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                metadata.store(stream, null);
            }
            move(temporary, metadataFile);
        }
        finally {
            Files.deleteIfExists(temporary);
        }
        return new Result(binary, downloaded, null);
    }

    /** Extracts the {@code remote-agent} entry of a gzip-compressed ustar/GNU tar archive. */
    static byte[] extract(byte[] archive) throws IOException {
        try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            String longName = null;
            while (true) {
                byte[] header = stream.readNBytes(TAR_BLOCK);
                if (header.length < TAR_BLOCK || isZero(header)) {
                    break;
                }
                String name = longName != null ? longName : string(header, 0, 100);
                longName = null;
                String prefix = string(header, 345, 155);
                if (!prefix.isEmpty() && header[257] == 'u') {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                byte type = header[156];
                if (size < 0 || size > 512L * 1024 * 1024) {
                    throw new IOException("Invalid agent archive entry size");
                }
                byte[] content = stream.readNBytes((int) size);
                if (content.length != size) {
                    throw new IOException("Truncated agent archive");
                }
                stream.skipNBytes((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK);
                if (type == 'L') {
                    longName = string(content, 0, content.length);
                    continue;
                }
                boolean regular = type == 0 || type == '0';
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (regular && fileName.equals(BINARY)) {
                    return content;
                }
            }
        }
        throw new IOException("Agent archive contains no " + BINARY + " executable");
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] bytes, int offset, int length) throws IOException {
        String text = string(bytes, offset, length).strip();
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text, 8);
        }
        catch (NumberFormatException e) {
            throw new IOException("Invalid agent archive header", e);
        }
    }

    private static long parseLong(@Nullable String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }
}
