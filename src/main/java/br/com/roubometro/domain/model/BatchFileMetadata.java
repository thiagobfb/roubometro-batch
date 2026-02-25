package br.com.roubometro.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "batch_file_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchFileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_url", nullable = false, length = 1024)
    private String fileUrl;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;

    @Column(nullable = false)
    private boolean processed;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
