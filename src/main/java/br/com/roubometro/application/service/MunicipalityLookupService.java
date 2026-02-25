package br.com.roubometro.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MunicipalityLookupService {

    private final JdbcTemplate jdbcTemplate;
    private final Set<Long> validIds = new HashSet<>();

    public void initialize() {
        validIds.clear();
        validIds.addAll(jdbcTemplate.queryForList("SELECT id FROM municipalities", Long.class));
        log.info("MunicipalityLookupService initialized with {} municipalities", validIds.size());
    }

    public boolean exists(Long municipalityId) {
        return validIds.contains(municipalityId);
    }

    public int getCacheSize() {
        return validIds.size();
    }
}
