package br.com.roubometro.application.processor;

import br.com.roubometro.application.service.CategoryColumnMapping;
import br.com.roubometro.application.service.CategoryLookupService;
import br.com.roubometro.application.service.MunicipalityLookupService;
import br.com.roubometro.domain.exception.CsvParsingException;
import br.com.roubometro.domain.model.CsvEstatisticaRow;
import br.com.roubometro.domain.model.MonthlyStat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class EstatisticaItemProcessor implements ItemProcessor<CsvEstatisticaRow, List<MonthlyStat>> {

    private static final Map<String, String> COLUMN_TO_CATEGORY = CategoryColumnMapping.getColumnToCategory();
    private static final Map<String, MethodHandle> METHOD_HANDLE_CACHE = new HashMap<>();

    static {
        // Pre-cache all field getters for CsvEstatisticaRow to avoid reflection overhead
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            for (String fieldName : COLUMN_TO_CATEGORY.keySet()) {
                String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                java.lang.reflect.Method method = CsvEstatisticaRow.class.getMethod(getterName);
                MethodHandle handle = lookup.unreflect(method);
                METHOD_HANDLE_CACHE.put(fieldName, handle);
            }
        } catch (Exception e) {
            log.warn("Failed to pre-cache MethodHandles", e);
        }
    }

    private final CategoryLookupService categoryLookupService;
    private final MunicipalityLookupService municipalityLookupService;
    private final boolean includeZeroValues;
    private final String sourceFile;

    public EstatisticaItemProcessor(
            CategoryLookupService categoryLookupService,
            MunicipalityLookupService municipalityLookupService,
            boolean includeZeroValues,
            String sourceFile
    ) {
        this.categoryLookupService = categoryLookupService;
        this.municipalityLookupService = municipalityLookupService;
        this.includeZeroValues = includeZeroValues;
        this.sourceFile = sourceFile;
    }

    @Override
    public List<MonthlyStat> process(CsvEstatisticaRow row) throws Exception {
        log.debug("Processing CSV row: fmun_cod={}, ano={}, mes={}", row.getFmunCod(), row.getAno(), row.getMes());

        // Sanitize string fields
        String fmunCodRaw = sanitize(row.getFmunCod());
        String anoRaw = sanitize(row.getAno());
        String mesRaw = sanitize(row.getMes());

        // Validate required fields
        if (fmunCodRaw == null || fmunCodRaw.isEmpty()) {
            throw new CsvParsingException("Missing required field: fmun_cod");
        }
        if (anoRaw == null || anoRaw.isEmpty()) {
            throw new CsvParsingException("Missing required field: ano");
        }
        if (mesRaw == null || mesRaw.isEmpty()) {
            throw new CsvParsingException("Missing required field: mes");
        }

        // Parse and validate numeric fields
        long municipalityId;
        try {
            municipalityId = Long.parseLong(fmunCodRaw);
        } catch (NumberFormatException e) {
            throw new CsvParsingException("Invalid fmun_cod: " + fmunCodRaw, e);
        }

        short year;
        try {
            year = Short.parseShort(anoRaw);
        } catch (NumberFormatException e) {
            throw new CsvParsingException("Invalid ano: " + anoRaw, e);
        }
        if (year < 2000 || year > 2100) {
            throw new CsvParsingException("Year out of range [2000-2100]: " + year);
        }

        byte month;
        try {
            month = Byte.parseByte(mesRaw);
        } catch (NumberFormatException e) {
            throw new CsvParsingException("Invalid mes: " + mesRaw, e);
        }
        if (month < 1 || month > 12) {
            throw new CsvParsingException("Month out of range [1-12]: " + month);
        }

        // Validate municipality exists
        if (!municipalityLookupService.exists(municipalityId)) {
            log.warn("Municipality not found, skipping row: fmun_cod={}, fmun={}", fmunCodRaw, row.getFmun());
            return null;
        }

        // Pivot: for each crime column, create a MonthlyStat
        List<MonthlyStat> stats = new ArrayList<>();

        for (Map.Entry<String, String> entry : COLUMN_TO_CATEGORY.entrySet()) {
            String fieldName = entry.getKey();
            String categoryName = entry.getValue();

            String rawValue = getFieldValue(row, fieldName);
            rawValue = sanitize(rawValue);

            if (rawValue == null || rawValue.isEmpty()) {
                continue;
            }

            int value;
            try {
                value = Integer.parseInt(rawValue);
            } catch (NumberFormatException e) {
                log.warn("Non-numeric value for column {} in row fmun_cod={}: '{}'", fieldName, fmunCodRaw, rawValue);
                continue;
            }

            if (value < 0) {
                throw new CsvParsingException("Negative value for column " + fieldName + ": " + value);
            }

            if (value == 0 && !includeZeroValues) {
                continue;
            }

            Long categoryId = categoryLookupService.getCategoryId(categoryName);
            if (categoryId == null) {
                log.warn("Category not found: {} (column {})", categoryName, fieldName);
                continue;
            }

            stats.add(MonthlyStat.builder()
                    .municipalityId(municipalityId)
                    .year(year)
                    .month(month)
                    .categoryId(categoryId)
                    .categoryValue(value)
                    .sourceFile(sourceFile)
                    .build());
        }

        if (stats.isEmpty()) {
            log.debug("No stats produced for row: fmun_cod={}, ano={}, mes={}", fmunCodRaw, anoRaw, mesRaw);
            return null;
        }

        log.debug("Produced {} stats for row: fmun_cod={}, ano={}, mes={}", stats.size(), fmunCodRaw, anoRaw, mesRaw);
        return stats;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        // Strip control characters (SEC-CSV-07), trim whitespace (SEC-CSV-08)
        return value.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").trim();
    }

    private static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
        try {
            MethodHandle handle = METHOD_HANDLE_CACHE.get(fieldName);
            if (handle == null) {
                return null;
            }
            return (String) handle.invoke(row);
        } catch (Throwable e) {
            return null;
        }
    }
}
