package br.com.roubometro.infrastructure.writer;

import br.com.roubometro.domain.model.MonthlyStat;
import br.com.roubometro.integration.AbstractBatchIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyStatItemWriterIntegrationTest extends AbstractBatchIntegrationTest {

    @Autowired
    private JdbcBatchItemWriter<MonthlyStat> monthlyStatJdbcWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void writesStatsToDatabase() throws Exception {
        var stats = List.of(
                new MonthlyStat(3300100L, (short) 2024, (byte) 1, 1L, 85, "test.csv"),
                new MonthlyStat(3300100L, (short) 2024, (byte) 1, 2L, 3, "test.csv"),
                new MonthlyStat(3300100L, (short) 2024, (byte) 1, 3L, 5, "test.csv")
        );

        monthlyStatJdbcWriter.write(new Chunk<>(stats));

        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_stats WHERE municipality_id = 3300100 AND year = 2024 AND month = 1",
                Integer.class
        );
        assertThat(count).isEqualTo(3);
    }

    @Test
    void upsertUpdatesExistingValue() throws Exception {
        var initial = List.of(
                new MonthlyStat(3304557L, (short) 2024, (byte) 2, 1L, 50, "v1.csv")
        );
        monthlyStatJdbcWriter.write(new Chunk<>(initial));

        var updated = List.of(
                new MonthlyStat(3304557L, (short) 2024, (byte) 2, 1L, 99, "v2.csv")
        );
        monthlyStatJdbcWriter.write(new Chunk<>(updated));

        var value = jdbcTemplate.queryForObject(
                "SELECT category_value FROM monthly_stats WHERE municipality_id = 3304557 AND year = 2024 AND month = 2 AND category_id = 1",
                Integer.class
        );
        assertThat(value).isEqualTo(99);

        var sourceFile = jdbcTemplate.queryForObject(
                "SELECT source_file FROM monthly_stats WHERE municipality_id = 3304557 AND year = 2024 AND month = 2 AND category_id = 1",
                String.class
        );
        assertThat(sourceFile).isEqualTo("v2.csv");
    }

    @Test
    void idempotentWrite() throws Exception {
        var stats = List.of(
                new MonthlyStat(3303302L, (short) 2024, (byte) 3, 1L, 10, "same.csv")
        );

        monthlyStatJdbcWriter.write(new Chunk<>(stats));
        monthlyStatJdbcWriter.write(new Chunk<>(stats));

        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monthly_stats WHERE municipality_id = 3303302 AND year = 2024 AND month = 3 AND category_id = 1",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }
}
