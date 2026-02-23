package br.com.roubometro.infrastructure.repository;

import br.com.roubometro.domain.model.Municipality;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalityRepository extends JpaRepository<Municipality, Long> {
}
