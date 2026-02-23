package br.com.roubometro.infrastructure.writer;

import br.com.roubometro.domain.model.MonthlyStat;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class MonthlyStatItemWriterConfig {

    private static final String UPSERT_SQL = """
            INSERT INTO monthly_stats (municipality_id, year, month, category_id, category_value, source_file, created_at)
            VALUES (:municipalityId, :year, :month, :categoryId, :categoryValue, :sourceFile, NOW())
            ON DUPLICATE KEY UPDATE
                category_value = VALUES(category_value),
                source_file = VALUES(source_file)
            """;

    @Bean
    public JdbcBatchItemWriter<MonthlyStat> monthlyStatJdbcWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<MonthlyStat>()
                .dataSource(dataSource)
                .sql(UPSERT_SQL)
                .beanMapped()
                .build();
    }
}
