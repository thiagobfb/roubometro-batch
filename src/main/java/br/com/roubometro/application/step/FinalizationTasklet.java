package br.com.roubometro.application.step;

import br.com.roubometro.application.service.FileMetadataService;
import br.com.roubometro.domain.model.BatchJobExecutionReport;
import br.com.roubometro.infrastructure.repository.BatchJobExecutionReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;

@Component
public class FinalizationTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FinalizationTasklet.class);

    private final BatchJobExecutionReportRepository reportRepository;
    private final FileMetadataService fileMetadataService;

    public FinalizationTasklet(
            BatchJobExecutionReportRepository reportRepository,
            FileMetadataService fileMetadataService
    ) {
        this.reportRepository = reportRepository;
        this.fileMetadataService = fileMetadataService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        JobExecution jobExecution = chunkContext.getStepContext()
                .getStepExecution().getJobExecution();
        var executionContext = jobExecution.getExecutionContext();

        boolean newFileAvailable = executionContext.containsKey("newFileAvailable")
                && Boolean.TRUE.equals(executionContext.get("newFileAvailable"));
        long fileMetadataId = executionContext.containsKey("fileMetadataId")
                ? executionContext.getLong("fileMetadataId") : 0L;
        String csvFilePath = executionContext.getString("csvFilePath", "");

        // Collect metrics from the data processing step
        int rowsRead = 0;
        int rowsWritten = 0;
        int rowsSkipped = 0;

        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        for (StepExecution step : stepExecutions) {
            if ("dataProcessingStep".equals(step.getStepName())) {
                rowsRead = (int) step.getReadCount();
                rowsWritten = (int) step.getWriteCount();
                rowsSkipped = (int) step.getSkipCount();
            }
        }

        // Calculate duration
        long durationMs = 0;
        if (jobExecution.getStartTime() != null) {
            durationMs = System.currentTimeMillis()
                    - jobExecution.getStartTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        }

        // Determine status
        String status = newFileAvailable ? "COMPLETED" : "SKIPPED";

        // Save report
        BatchJobExecutionReport report = new BatchJobExecutionReport();
        report.setJobExecutionId(jobExecution.getId());
        report.setFileMetadataId(fileMetadataId > 0 ? fileMetadataId : null);
        report.setStatus(status);
        report.setRowsRead(rowsRead);
        report.setRowsWritten(rowsWritten);
        report.setRowsSkipped(rowsSkipped);
        report.setRowsErrors(0);
        report.setDurationMs(durationMs);
        report.setCreatedAt(LocalDateTime.now());
        reportRepository.save(report);

        // Mark file as processed
        if (fileMetadataId > 0 && newFileAvailable) {
            fileMetadataService.markProcessed(fileMetadataId, rowsRead);
        }

        // Cleanup temp file
        if (!csvFilePath.isEmpty()) {
            try {
                Files.deleteIfExists(Path.of(csvFilePath));
                log.debug("Cleaned up temp file: {}", csvFilePath);
            } catch (Exception e) {
                log.warn("Failed to clean up temp file: {}", csvFilePath, e);
            }
        }

        log.info("Job roubometroDataSyncJob finalized. Status={}, rowsRead={}, rowsWritten={}, rowsSkipped={}, durationMs={}",
                status, rowsRead, rowsWritten, rowsSkipped, durationMs);

        return RepeatStatus.FINISHED;
    }
}
