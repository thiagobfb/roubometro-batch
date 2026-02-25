package br.com.roubometro.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryLookupService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    public void initialize() {
        cache.clear();
        // Pre-load all existing categories
        jdbcTemplate.query("SELECT id, name FROM categories", rs -> {
            cache.put(rs.getString("name"), rs.getLong("id"));
        });

        // Ensure all required categories from the mapping exist
        for (String categoryName : CategoryColumnMapping.getColumnToCategory().values()) {
            if (!cache.containsKey(categoryName)) {
                jdbcTemplate.update("INSERT IGNORE INTO categories (name) VALUES (?)", categoryName);
            }
        }

        // Reload cache after inserts
        cache.clear();
        jdbcTemplate.query("SELECT id, name FROM categories", rs -> {
            cache.put(rs.getString("name"), rs.getLong("id"));
        });

        log.info("CategoryLookupService initialized with {} categories", cache.size());
    }

    public Long getCategoryId(String categoryName) {
        return cache.get(categoryName);
    }

    public int getCacheSize() {
        return cache.size();
    }
}
