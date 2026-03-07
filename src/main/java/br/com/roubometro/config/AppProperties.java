package br.com.roubometro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roubometro")
public record AppProperties(
        PortalProperties portal,
        BatchProperties batch
) {
    public record PortalProperties(
            String csvUrl,
            String scrapingUrl,
            int connectTimeoutMs,
            int readTimeoutMs,
            int retryAttempts,
            long retryBackoffMs
    ) {}

    public record BatchProperties(
            int chunkSize,
            int skipLimit,
            String tempDir,
            String csvEncoding,
            String csvDelimiter,
            boolean includeZeroValues
    ) {}

}
