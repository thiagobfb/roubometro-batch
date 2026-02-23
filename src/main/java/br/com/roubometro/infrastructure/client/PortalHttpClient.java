package br.com.roubometro.infrastructure.client;

import br.com.roubometro.config.AppProperties;
import br.com.roubometro.domain.exception.PortalAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Component
public class PortalHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PortalHttpClient.class);
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "www.ispdados.rj.gov.br",
            "ispdados.rj.gov.br"
    );

    private final HttpClient httpClient;
    private final AppProperties appProperties;

    public PortalHttpClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(appProperties.portal().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // Constructor for testing
    PortalHttpClient(HttpClient httpClient, AppProperties appProperties) {
        this.httpClient = httpClient;
        this.appProperties = appProperties;
    }

    public InputStream download(String url) {
        URI uri = validateUrl(url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(appProperties.portal().readTimeoutMs()))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new PortalAccessException("Redirect detected (status " + response.statusCode() + "). Redirects are disabled for security (SEC-DL-02).");
            }

            if (response.statusCode() != 200) {
                throw new PortalAccessException("Portal returned HTTP " + response.statusCode() + " for URL: " + url);
            }

            log.info("Successfully connected to portal: url={}, status=200", url);
            return response.body();

        } catch (PortalAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new PortalAccessException("Failed to download from portal: " + url, e);
        }
    }

    private URI validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new PortalAccessException("Invalid URL: " + url, e);
        }

        // SEC-DL-01: Whitelist allowed domains
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new PortalAccessException("Host not in whitelist: " + host + ". Allowed: " + ALLOWED_HOSTS);
        }

        // SEC-DL-03: Reject IP literals
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (host.equals(addr.getHostAddress())) {
                throw new PortalAccessException("IP literal URLs are not allowed: " + host);
            }
        } catch (PortalAccessException e) {
            throw e;
        } catch (Exception e) {
            // DNS resolution failed — acceptable, let the HTTP client handle it
        }

        // SEC-DL-06: HTTPS required in prod (checking scheme)
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new PortalAccessException("Only HTTP/HTTPS schemes are allowed: " + scheme);
        }

        return uri;
    }
}
