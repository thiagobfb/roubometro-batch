package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "batch_job_execution_report")
public class BatchJobExecutionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "file_metadata_id")
    private Long fileMetadataId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead;

    @Column(name = "rows_written", nullable = false)
    private int rowsWritten;

    @Column(name = "rows_skipped", nullable = false)
    private int rowsSkipped;

    @Column(name = "rows_errors", nullable = false)
    private int rowsErrors;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }

    public Long getFileMetadataId() { return fileMetadataId; }
    public void setFileMetadataId(Long fileMetadataId) { this.fileMetadataId = fileMetadataId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRowsRead() { return rowsRead; }
    public void setRowsRead(int rowsRead) { this.rowsRead = rowsRead; }

    public int getRowsWritten() { return rowsWritten; }
    public void setRowsWritten(int rowsWritten) { this.rowsWritten = rowsWritten; }

    public int getRowsSkipped() { return rowsSkipped; }
    public void setRowsSkipped(int rowsSkipped) { this.rowsSkipped = rowsSkipped; }

    public int getRowsErrors() { return rowsErrors; }
    public void setRowsErrors(int rowsErrors) { this.rowsErrors = rowsErrors; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
