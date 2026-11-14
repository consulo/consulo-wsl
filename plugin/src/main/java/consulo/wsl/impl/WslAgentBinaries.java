package consulo.wsl.impl;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.container.boot.ContainerPathManager;
import consulo.http.HttpRequests;
import consulo.logging.Logger;
import consulo.wsl.agent.AgentBinaryCache;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Downloads the published Linux agent on Windows, honoring the IDE proxy settings, and caches it in the system directory.
 */
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public final class WslAgentBinaries {
    private static final Logger LOG = Logger.getInstance(WslAgentBinaries.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(24);

    /**
     * Mirror of consulo/binaries; {@code linux-<arch>/remote-agent.tar.gz} is appended.
     */
    private static final String BASE_URL_PROPERTY = "consulo.wsl.agent.baseUrl";

    private final AgentBinaryCache myCache;

    @Inject
    public WslAgentBinaries() {
        Path root = ContainerPathManager.get().getSystemDir().resolve("wsl-agent");
        String baseUrl = normalize(System.getProperty(BASE_URL_PROPERTY, AgentBinaryCache.DEFAULT_BASE_URL));
        myCache = new AgentBinaryCache(root, baseUrl, WslAgentBinaries::fetch, REFRESH_INTERVAL, Clock.systemUTC());
    }

    /**
     * Blocking; call off the UI thread.
     */
    public Path resolve(String architecture, boolean forceRefresh) throws IOException {
        AgentBinaryCache.Result result = myCache.resolve(architecture, forceRefresh);
        if (result.refreshFailure() != null) {
            LOG.warn("Cannot refresh the WSL remote agent, using the cached copy", result.refreshFailure());
        }
        else if (result.downloaded()) {
            LOG.info("Downloaded WSL remote agent for linux-" + architecture);
        }
        return result.binary();
    }

    private static String normalize(String baseUrl) {
        String url = baseUrl.isBlank() ? AgentBinaryCache.DEFAULT_BASE_URL : baseUrl.strip();
        return url.endsWith("/") ? url : url + "/";
    }

    private static AgentBinaryCache.@Nullable Response fetch(String url, @Nullable String etag) throws IOException {
        Optional<AgentBinaryCache.Response> response = HttpRequests.request(url)
            .productNameAsUserAgent()
            .allowErrorCodes(true)
            .header("If-None-Match", etag)
            .connect(request -> {
                int code = request.statusCode();
                if (code == 304) {
                    return Optional.<AgentBinaryCache.Response>empty();
                }
                if (code != 200 && code != 0) {
                    throw new IOException("HTTP " + code + " for " + url);
                }
                return Optional.of(new AgentBinaryCache.Response(request.readBytes(null), request.headerValue("ETag")));
            });
        return response.orElse(null);
    }
}
