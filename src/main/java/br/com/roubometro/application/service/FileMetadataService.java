package br.com.roubometro.application.service;

import br.com.roubometro.domain.model.BatchFileMetadata;
import br.com.roubometro.infrastructure.repository.BatchFileMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FileMetadataService {

    private static final Logger log = LoggerFactory.getLogger(FileMetadataService.class);

    private final BatchFileMetadataRepository repository;

    public FileMetadataService(BatchFileMetadataRepository repository) {
        this.repository = repository;
    }

    public boolean isNewFile(String fileHash) {
        return repository.findTopByOrderByDownloadedAtDesc()
                .map(metadata -> !metadata.getFileHash().equals(fileHash))
                .orElse(true);
    }

    public BatchFileMetadata register(String fileName, String fileUrl, String fileHash, long fileSizeBytes) {
        BatchFileMetadata metadata = new BatchFileMetadata();
        metadata.setFileName(fileName);
        metadata.setFileUrl(fileUrl);
        metadata.setFileHash(fileHash);
        metadata.setFileSizeBytes(fileSizeBytes);
        metadata.setDownloadedAt(LocalDateTime.now());
        metadata.setProcessed(false);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setUpdatedAt(LocalDateTime.now());

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
