package br.com.roubometro.application.step;

import br.com.roubometro.application.service.FileDownloadService;
import br.com.roubometro.application.service.FileMetadataService;
import br.com.roubometro.application.service.SchemaValidationService;
import br.com.roubometro.config.AppProperties;
import br.com.roubometro.domain.exception.PortalAccessException;
import br.com.roubometro.domain.model.BatchFileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

@Component
public class DataAcquisitionTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DataAcquisitionTasklet.class);

    private final FileDownloadService fileDownloadService;
    private final FileMetadataService fileMetadataService;
    private final SchemaValidationService schemaValidationService;
    private final AppProperties appProperties;

    public DataAcquisitionTasklet(
            FileDownloadService fileDownloadService,
            FileMetadataService fileMetadataService,
            SchemaValidationService schemaValidationService,
            AppProperties appProperties
    ) {
        this.fileDownloadService = fileDownloadService;
        this.fileMetadataService = fileMetadataService;
        this.schemaValidationService = schemaValidationService;
        this.appProperties = appProperties;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        var executionContext = chunkContext.getStepContext()
                .getStepExecution().getJobExecution().getExecutionContext();

        // Validate schema before downloading
        schemaValidationService.validate();

        String csvUrl = appProperties.portal().csvUrl();
        log.info("Starting data acquisition from: {}", csvUrl);

        // Download with retry
        FileDownloadService.DownloadResult result = downloadWithRetry(csvUrl);

        // Check if file is new
        boolean isNew = fileMetadataService.isNewFile(result.fileHash());

        if (isNew) {
            BatchFileMetadata metadata = fileMetadataService.register(
                    result.fileName(), csvUrl, result.fileHash(), result.fileSizeBytes()
            );

            executionContext.put("newFileAvailable", true);
            executionContext.putString("csvFilePath", result.filePath().toString());
            executionContext.putString("csvFileName", result.fileName());
            executionContext.putLong("fileMetadataId", metadata.getId());

            log.info("New file detected: hash={}, size={} bytes", result.fileHash(), result.fileSizeBytes());
        } else {
            // Same file — clean up and skip processing
            Files.deleteIfExists(result.filePath());

            executionContext.put("newFileAvailable", false);
            executionContext.putString("csvFilePath", "");
            executionContext.putString("csvFileName", "");
            executionContext.putLong("fileMetadataId", 0L);

            log.info("File unchanged (same hash), skipping processing: hash={}", result.fileHash());
        }

        return RepeatStatus.FINISHED;
    }

    private FileDownloadService.DownloadResult downloadWithRetry(String url) {
        int maxAttempts = appProperties.portal().retryAttempts();
        long backoffMs = appProperties.portal().retryBackoffMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fileDownloadService.download(url);
            } catch (PortalAccessException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                log.warn("Download attempt {}/{} failed: {}. Retrying in {}ms...",
                        attempt, maxAttempts, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PortalAccessException("Download interrupted", ie);
                }
                backoffMs *= 2; // Exponential backoff
            }
        }
        throw new PortalAccessException("All download attempts failed for: " + url);
    }
}
