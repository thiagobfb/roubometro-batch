package br.com.roubometro.application.service;

import br.com.roubometro.config.AppProperties;
import br.com.roubometro.domain.exception.FileDownloadException;
import br.com.roubometro.infrastructure.client.PortalHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileDownloadService {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB (SEC-DL-05)

    private final PortalHttpClient portalHttpClient;
    private final AppProperties appProperties;

    public DownloadResult download(String url) {
        Path tempDir = Path.of(appProperties.batch().tempDir());

        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new FileDownloadException("Cannot create temp directory: " + tempDir, e);
        }

        // SEC-DL-07: Generated file name (no user-controlled names)
        String safeFileName = "batch_" + UUID.randomUUID() + ".csv";
        Path targetPath = tempDir.resolve(safeFileName);

        // SEC-DL-10: Path traversal protection
        if (!targetPath.normalize().startsWith(tempDir.normalize())) {
            throw new FileDownloadException("Path traversal detected for file: " + safeFileName);
        }

        try (InputStream input = portalHttpClient.download(url)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;

            try (OutputStream output = Files.newOutputStream(targetPath)) {
                while ((bytesRead = input.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                    if (totalBytes > MAX_FILE_SIZE) {
                        Files.deleteIfExists(targetPath);
                        throw new FileDownloadException("File exceeds max size of " + MAX_FILE_SIZE + " bytes (SEC-DL-05)");
                    }
                    digest.update(buffer, 0, bytesRead);
                    output.write(buffer, 0, bytesRead);
                }
            }

            String hash = HexFormat.of().formatHex(digest.digest());

            log.info("File downloaded: path={}, size={}, hash={}", targetPath, totalBytes, hash);

            return new DownloadResult(targetPath, hash, totalBytes, safeFileName);

        } catch (FileDownloadException e) {
            throw e;
        } catch (NoSuchAlgorithmException e) {
            throw new FileDownloadException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new FileDownloadException("Failed to download file from " + url, e);
        }
    }

    public record DownloadResult(
            Path filePath,
            String fileHash,
            long fileSizeBytes,
            String fileName
    ) {}
}
