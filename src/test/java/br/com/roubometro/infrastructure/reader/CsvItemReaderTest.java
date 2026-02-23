package br.com.roubometro.infrastructure.reader;

import br.com.roubometro.domain.model.CsvEstatisticaRow;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvItemReaderTest {

    private FlatFileItemReader<CsvEstatisticaRow> buildReader(String fixturePath) {
        BeanWrapperFieldSetMapper<CsvEstatisticaRow> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(CsvEstatisticaRow.class);

        return new FlatFileItemReaderBuilder<CsvEstatisticaRow>()
                .name("testCsvReader")
                .resource(new ClassPathResource(fixturePath))
                .encoding("ISO-8859-1")
                .linesToSkip(1)
                .delimited()
                .delimiter(";")
                .names(CsvColumnNames.COLUMNS)
                .fieldSetMapper(mapper)
                .build();
    }

    private List<CsvEstatisticaRow> readAll(FlatFileItemReader<CsvEstatisticaRow> reader) throws Exception {
        reader.open(new ExecutionContext());
        try {
            List<CsvEstatisticaRow> items = new ArrayList<>();
            CsvEstatisticaRow item;
            while ((item = reader.read()) != null) {
                items.add(item);
            }
            return items;
        } finally {
            reader.close();
        }
    }

    @Test
    void readsValidCsvWith10Rows() throws Exception {
        var reader = buildReader("fixtures/sample_valid.csv");
        var rows = readAll(reader);
        assertThat(rows).hasSize(10);
    }

    @Test
    void parsesFieldsCorrectly() throws Exception {
        var reader = buildReader("fixtures/sample_valid.csv");
        var rows = readAll(reader);

        CsvEstatisticaRow first = rows.get(0);
        assertThat(first.getFmunCod()).isEqualTo("3300100");
        assertThat(first.getFmun()).isEqualTo("Angra dos Reis");
        assertThat(first.getAno()).isEqualTo("2024");
        assertThat(first.getMes()).isEqualTo("1");
        assertThat(first.getRegiao()).isEqualTo("Interior");
        assertThat(first.getHomDoloso()).isEqualTo("5");
        assertThat(first.getFase()).isEqualTo("3");
    }

    @Test
    void rioDeJaneiroParsedCorrectly() throws Exception {
        var reader = buildReader("fixtures/sample_valid.csv");
        var rows = readAll(reader);

        CsvEstatisticaRow rj = rows.get(2);
        assertThat(rj.getFmunCod()).isEqualTo("3304557");
        assertThat(rj.getFmun()).isEqualTo("Rio de Janeiro");
        assertThat(rj.getRegiao()).isEqualTo("Capital");
        assertThat(rj.getHomDoloso()).isEqualTo("85");
    }

    @Test
    void emptyCsvReturnsNoRows() throws Exception {
        var reader = buildReader("fixtures/sample_empty.csv");
        var rows = readAll(reader);
        assertThat(rows).isEmpty();
    }

    @Test
    void allFieldsAreStrings() throws Exception {
        var reader = buildReader("fixtures/sample_valid.csv");
        var rows = readAll(reader);

        CsvEstatisticaRow row = rows.get(0);
        // Numeric fields are still String at this stage
        assertThat(row.getHomDoloso()).isInstanceOf(String.class);
        assertThat(row.getAno()).isInstanceOf(String.class);
        assertThat(row.getMes()).isInstanceOf(String.class);
    }
}
