package br.com.roubometro.infrastructure.repository;

import br.com.roubometro.domain.model.BatchJobExecutionReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchJobExecutionReportRepository extends JpaRepository<BatchJobExecutionReport, Long> {
}
