package br.com.roubometro.application.processor;

import br.com.roubometro.application.service.CategoryLookupService;
import br.com.roubometro.application.service.MunicipalityLookupService;
import br.com.roubometro.domain.exception.CsvParsingException;
import br.com.roubometro.domain.model.CsvEstatisticaRow;
import br.com.roubometro.domain.model.MonthlyStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstatisticaItemProcessorTest {

    private StubCategoryLookup categoryLookup;
    private StubMunicipalityLookup municipalityLookup;
    private EstatisticaItemProcessor processor;

    @BeforeEach
    void setUp() {
        categoryLookup = new StubCategoryLookup();
        municipalityLookup = new StubMunicipalityLookup();
        // Add known municipality
        municipalityLookup.validIds.add(3304557L);
        municipalityLookup.validIds.add(3300100L);
        // Add known categories
        categoryLookup.cache.put("Homicidio doloso", 1L);
        categoryLookup.cache.put("Latrocinio", 3L);
        categoryLookup.cache.put("Estupro", 9L);

        processor = new EstatisticaItemProcessor(
                categoryLookup, municipalityLookup, false, "test.csv"
        );
    }

    private CsvEstatisticaRow validRow() {
        CsvEstatisticaRow row = new CsvEstatisticaRow();
        row.setFmunCod("3304557");
        row.setFmun("Rio de Janeiro");
        row.setAno("2024");
        row.setMes("1");
        row.setRegiao("Capital");
        row.setHomDoloso("85");
        row.setLatrocinio("5");
        row.setEstupro("0");
        return row;
    }

    @Test
    void processesValidRow() throws Exception {
        List<MonthlyStat> result = processor.process(validRow());

        assertThat(result).isNotNull();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);

        MonthlyStat hom = result.stream()
                .filter(s -> s.getCategoryId() == 1L)
                .findFirst().orElseThrow();
        assertThat(hom.getMunicipalityId()).isEqualTo(3304557L);
        assertThat(hom.getYear()).isEqualTo((short) 2024);
        assertThat(hom.getMonth()).isEqualTo((byte) 1);
        assertThat(hom.getCategoryValue()).isEqualTo(85);
        assertThat(hom.getSourceFile()).isEqualTo("test.csv");
    }

    @Test
    void skipsZeroValuesWhenConfigured() throws Exception {
        List<MonthlyStat> result = processor.process(validRow());
        // estupro=0 should be skipped (includeZeroValues=false)
        assertThat(result).noneMatch(s -> s.getCategoryValue() == 0);
    }

    @Test
    void includesZeroValuesWhenConfigured() throws Exception {
        processor = new EstatisticaItemProcessor(
                categoryLookup, municipalityLookup, true, "test.csv"
        );

        List<MonthlyStat> result = processor.process(validRow());
        assertThat(result).isNotNull();
        assertThat(result).anyMatch(s -> s.getCategoryValue() == 0);
    }

    @Test
    void throwsOnMissingFmunCod() {
        CsvEstatisticaRow row = validRow();
        row.setFmunCod("");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("fmun_cod");
    }

    @Test
    void throwsOnMissingAno() {
        CsvEstatisticaRow row = validRow();
        row.setAno(null);

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("ano");
    }

    @Test
    void throwsOnInvalidYear() {
        CsvEstatisticaRow row = validRow();
        row.setAno("abc");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("ano");
    }

    @Test
    void throwsOnYearOutOfRange() {
        CsvEstatisticaRow row = validRow();
        row.setAno("1999");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("Year out of range");
    }

    @Test
    void throwsOnInvalidMonth() {
        CsvEstatisticaRow row = validRow();
        row.setMes("13");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("Month out of range");
    }

    @Test
    void throwsOnMonthZero() {
        CsvEstatisticaRow row = validRow();
        row.setMes("0");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("Month out of range");
    }

    @Test
    void returnsNullForUnknownMunicipality() throws Exception {
        CsvEstatisticaRow row = validRow();
        row.setFmunCod("9999999");

        List<MonthlyStat> result = processor.process(row);
        assertThat(result).isNull();
    }

    @Test
    void throwsOnNegativeValue() {
        CsvEstatisticaRow row = validRow();
        row.setHomDoloso("-5");

        assertThatThrownBy(() -> processor.process(row))
                .isInstanceOf(CsvParsingException.class)
                .hasMessageContaining("Negative value");
    }

    @Test
    void sanitizesControlCharacters() throws Exception {
        CsvEstatisticaRow row = validRow();
        row.setFmunCod(" \u00003304557\u0001 ");
        row.setHomDoloso("85");

        List<MonthlyStat> result = processor.process(row);
        assertThat(result).isNotNull();
        assertThat(result.get(0).getMunicipalityId()).isEqualTo(3304557L);
    }

    // --- Test doubles ---

    static class StubCategoryLookup extends CategoryLookupService {
        final Map<String, Long> cache = new HashMap<>();

        StubCategoryLookup() {
            super(null);
        }

        @Override
        public Long getCategoryId(String categoryName) {
            return cache.get(categoryName);
        }

        @Override
        public void initialize() { }

        @Override
        public int getCacheSize() { return cache.size(); }
    }

    static class StubMunicipalityLookup extends MunicipalityLookupService {
        final Set<Long> validIds = new HashSet<>();

        StubMunicipalityLookup() {
            super(null);
        }

        @Override
        public boolean exists(Long municipalityId) {
            return validIds.contains(municipalityId);
        }

        @Override
        public void initialize() { }

        @Override
        public int getCacheSize() { return validIds.size(); }
    }
}
