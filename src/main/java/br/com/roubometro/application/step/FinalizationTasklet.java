package br.com.roubometro.application.step;

import br.com.roubometro.application.service.FileMetadataService;
import br.com.roubometro.domain.model.BatchJobExecutionReport;
import br.com.roubometro.infrastructure.repository.BatchJobExecutionReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalizationTasklet implements Tasklet {

    private final BatchJobExecutionReportRepository reportRepository;
    private final FileMetadataService fileMetadataService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("FinalizationTasklet started");

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
        BatchJobExecutionReport report = BatchJobExecutionReport.builder()
                .jobExecutionId(jobExecution.getId())
                .fileMetadataId(fileMetadataId > 0 ? fileMetadataId : null)
                .status(status)
                .rowsRead(rowsRead)
                .rowsWritten(rowsWritten)
                .rowsSkipped(rowsSkipped)
                .rowsErrors(0)
                .durationMs(durationMs)
                .createdAt(LocalDateTime.now())
                .build();
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
