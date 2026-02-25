package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "batch_job_execution_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
}
