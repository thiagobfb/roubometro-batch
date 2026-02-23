package br.com.roubometro.application.service;

import br.com.roubometro.domain.exception.SchemaValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SchemaValidationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidationService.class);

    private static final Set<String> REQUIRED_MONTHLY_STATS_COLUMNS = Set.of(
            "id", "municipality_id", "year", "month", "category_id", "category_value", "source_file", "created_at"
    );

    private static final Set<String> REQUIRED_CATEGORIES_COLUMNS = Set.of(
            "id", "name"
    );

    private final JdbcTemplate jdbcTemplate;

    public SchemaValidationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void validate() {
        validateTable("monthly_stats", REQUIRED_MONTHLY_STATS_COLUMNS);
        validateTable("categories", REQUIRED_CATEGORIES_COLUMNS);
        log.info("Schema validation passed: monthly_stats and categories tables are compatible");
    }

    private void validateTable(String tableName, Set<String> requiredColumns) {
        List<String> actualColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName
        );

        if (actualColumns.isEmpty()) {
            throw new SchemaValidationException("Table '" + tableName + "' does not exist in the database");
        }

        Set<String> actualSet = Set.copyOf(actualColumns);
        for (String required : requiredColumns) {
            if (!actualSet.contains(required)) {
                throw new SchemaValidationException(
                        "Table '" + tableName + "' is missing required column: " + required
                );
            }
        }
    }
}
