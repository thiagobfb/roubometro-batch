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
                MonthlyStat.builder().municipalityId(3300100L).year((short) 2024).month((byte) 1).categoryId(1L).categoryValue(85).sourceFile("test.csv").build(),
                MonthlyStat.builder().municipalityId(3300100L).year((short) 2024).month((byte) 1).categoryId(2L).categoryValue(3).sourceFile("test.csv").build(),
                MonthlyStat.builder().municipalityId(3300100L).year((short) 2024).month((byte) 1).categoryId(3L).categoryValue(5).sourceFile("test.csv").build()
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
                MonthlyStat.builder().municipalityId(3304557L).year((short) 2024).month((byte) 2).categoryId(1L).categoryValue(50).sourceFile("v1.csv").build()
        );
        monthlyStatJdbcWriter.write(new Chunk<>(initial));

        var updated = List.of(
                MonthlyStat.builder().municipalityId(3304557L).year((short) 2024).month((byte) 2).categoryId(1L).categoryValue(99).sourceFile("v2.csv").build()
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
                MonthlyStat.builder().municipalityId(3303302L).year((short) 2024).month((byte) 3).categoryId(1L).categoryValue(10).sourceFile("same.csv").build()
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
