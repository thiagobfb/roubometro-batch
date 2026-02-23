-- Roubometro Batch: tables owned exclusively by the batch service.
-- IMPORTANT: monthly_stats, categories, municipalities, etc. are managed by Knex (roubometro-back).
-- The batch NEVER creates or alters those tables.

CREATE TABLE IF NOT EXISTS batch_file_metadata (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    file_name       VARCHAR(255)    NOT NULL,
    file_url        VARCHAR(1024)   NOT NULL,
    file_hash       VARCHAR(64)     NOT NULL COMMENT 'SHA-256 hash of file contents',
    file_size_bytes BIGINT          NULL,
    downloaded_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed       TINYINT(1)      NOT NULL DEFAULT 0,
    processed_at    DATETIME        NULL,
    row_count       INT             NULL COMMENT 'Number of CSV rows (excluding header)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS batch_job_execution_report (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    job_execution_id    BIGINT      NOT NULL COMMENT 'Logical FK to BATCH_JOB_EXECUTION',
    file_metadata_id    BIGINT      NULL,
    status              VARCHAR(20) NOT NULL COMMENT 'COMPLETED, FAILED, SKIPPED',
    rows_read           INT         NOT NULL DEFAULT 0,
    rows_written        INT         NOT NULL DEFAULT 0,
    rows_skipped        INT         NOT NULL DEFAULT 0,
    rows_errors         INT         NOT NULL DEFAULT 0,
    duration_ms         BIGINT      NULL,
    error_message       TEXT        NULL,
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_execution_id (job_execution_id),
    CONSTRAINT fk_report_file_metadata
        FOREIGN KEY (file_metadata_id)
        REFERENCES batch_file_metadata (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
