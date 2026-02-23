package br.com.roubometro.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSetupIntegrationTest extends AbstractBatchIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void apiTablesExist() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'roubometro' AND table_name IN ('regions','states','municipalities','categories','monthly_stats')",
                String.class
        );
        assertThat(tables).containsExactlyInAnyOrder(
                "regions", "states", "municipalities", "categories", "monthly_stats"
        );
    }

    @Test
    void flywayAppliedV1Migration() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'roubometro' AND table_name = 'batch_file_metadata'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void batchJobExecutionReportTableExists() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'roubometro' AND table_name = 'batch_job_execution_report'",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void seedDataPresent() {
        var municipalityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM municipalities", Integer.class
        );
        assertThat(municipalityCount).isGreaterThanOrEqualTo(10);

        var categoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories", Integer.class
        );
        assertThat(categoryCount).isGreaterThanOrEqualTo(10);
    }

    @Test
    void springBatchTablesExist() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'roubometro' AND table_name LIKE 'BATCH_%'",
                String.class
        );
        assertThat(tables).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void monthlyStatsUniqueKeyExists() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = 'roubometro' AND table_name = 'monthly_stats' AND index_name = 'uk_mun_date_category'",
                Integer.class
        );
        assertThat(count).isGreaterThan(0);
    }
}
