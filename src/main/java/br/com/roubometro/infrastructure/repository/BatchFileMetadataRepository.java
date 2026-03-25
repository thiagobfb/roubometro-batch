package br.com.roubometro.infrastructure.repository;

import br.com.roubometro.domain.model.BatchFileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatchFileMetadataRepository extends JpaRepository<BatchFileMetadata, Long> {
    Optional<BatchFileMetadata> findTopByOrderByDownloadedAtDesc();
    Optional<BatchFileMetadata> findTopByProcessedTrueOrderByDownloadedAtDesc();
    void deleteByProcessedFalse();
}
