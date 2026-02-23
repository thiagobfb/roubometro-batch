package br.com.roubometro.infrastructure.repository;

import br.com.roubometro.domain.model.MonthlyStat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyStatRepository extends JpaRepository<MonthlyStat, Long> {
}
