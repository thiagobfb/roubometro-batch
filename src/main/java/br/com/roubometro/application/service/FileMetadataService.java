package br.com.roubometro.application.service;

import br.com.roubometro.domain.model.BatchFileMetadata;
import br.com.roubometro.infrastructure.repository.BatchFileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileMetadataService {

    private final BatchFileMetadataRepository repository;

    public boolean isNewFile(String fileHash) {
        return repository.findTopByProcessedTrueOrderByDownloadedAtDesc()
                .map(metadata -> !metadata.getFileHash().equals(fileHash))
                .orElse(true);
    }

    @Transactional
    public void cleanupUnprocessed() {
        repository.deleteByProcessedFalse();
        log.info("Cleaned up unprocessed file metadata records");
    }

    public BatchFileMetadata register(String fileName, String fileUrl, String fileHash, long fileSizeBytes) {
        var now = LocalDateTime.now();
        BatchFileMetadata metadata = BatchFileMetadata.builder()
                .fileName(fileName)
                .fileUrl(fileUrl)
                .fileHash(fileHash)
                .fileSizeBytes(fileSizeBytes)
                .downloadedAt(now)
                .processed(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        metadata = repository.save(metadata);
        log.info("Registered file metadata: id={}, fileName={}, hash={}", metadata.getId(), fileName, fileHash);
        return metadata;
    }

    public void markProcessed(Long metadataId, int rowCount) {
        repository.findById(metadataId).ifPresent(metadata -> {
            metadata.setProcessed(true);
            metadata.setProcessedAt(LocalDateTime.now());
            metadata.setRowCount(rowCount);
            metadata.setUpdatedAt(LocalDateTime.now());
            repository.save(metadata);
            log.info("Marked file as processed: id={}, rowCount={}", metadataId, rowCount);
        });
    }
}
