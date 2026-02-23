package br.com.roubometro.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class MunicipalityLookupService {

    private static final Logger log = LoggerFactory.getLogger(MunicipalityLookupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Set<Long> validIds = new HashSet<>();

    public MunicipalityLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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
