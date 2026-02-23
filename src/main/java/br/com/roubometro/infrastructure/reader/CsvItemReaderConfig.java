package br.com.roubometro.infrastructure.reader;

import br.com.roubometro.domain.model.CsvEstatisticaRow;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.Charset;

@Configuration
public class CsvItemReaderConfig {

    @Bean
    @StepScope
    public FlatFileItemReader<CsvEstatisticaRow> csvItemReader(
            @Value("#{jobExecutionContext['csvFilePath']}") String csvFilePath,
            @Value("${roubometro.batch.csv-encoding:ISO-8859-1}") String encoding,
            @Value("${roubometro.batch.csv-delimiter:;}") String delimiter
    ) {
        BeanWrapperFieldSetMapper<CsvEstatisticaRow> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(CsvEstatisticaRow.class);

        return new FlatFileItemReaderBuilder<CsvEstatisticaRow>()
                .name("csvEstatisticaReader")
                .resource(new FileSystemResource(csvFilePath))
                .encoding(Charset.forName(encoding).name())
                .linesToSkip(1)
                .delimited()
                .delimiter(delimiter)
                .names(CsvColumnNames.COLUMNS)
                .fieldSetMapper(mapper)
                .build();
    }
}
