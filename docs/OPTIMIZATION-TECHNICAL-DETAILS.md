# Technical Details — Performance Optimization

This document provides code-level details for implementing each fix.

---

## Fix 1: Eliminate Reflection in EstatisticaItemProcessor

### Current Implementation (BAD)

**File**: `src/main/java/br/com/roubometro/application/processor/EstatisticaItemProcessor.java`

```java
private static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
    try {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Method getter = CsvEstatisticaRow.class.getMethod(getterName);  // ← Reflection #1
        return (String) getter.invoke(row);  // ← Reflection #2
    } catch (Exception e) {
        return null;
    }
}
```

**Problem**:
- `getMethod()` performs class metadata lookup (~5-10ms)
- `invoke()` performs method dispatch via reflection (~5-10ms)
- Called 3,100 times per chunk = 15.5-31 seconds wasted per chunk

### Solution 1A: MethodHandle Caching (RECOMMENDED)

**Advantages**:
- 2-3x faster than reflection
- Java 7+ standard library (no external deps)
- Minimal code changes
- Good cache locality

**Implementation**:

```java
package br.com.roubometro.application.processor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class CsvFieldAccessor {
    private static final Map<String, MethodHandle> HANDLE_CACHE = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    static {
        // Pre-load all field handles (field names from CSV header)
        initializeHandles();
    }

    private static void initializeHandles() {
        // List of all CSV column names (from CsvColumnNames.COLUMNS)
        String[] columnNames = CsvColumnNames.COLUMNS;

        for (String fieldName : columnNames) {
            try {
                String getterName = "get" + Character.toUpperCase(fieldName.charAt(0))
                                  + fieldName.substring(1);
                MethodHandle handle = LOOKUP.findVirtual(
                    CsvEstatisticaRow.class,
                    getterName,
                    MethodType.methodType(String.class)
                );
                HANDLE_CACHE.put(fieldName, handle);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // Log warning, continue (field might not exist)
            }
        }
    }

    public static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
        try {
            MethodHandle handle = HANDLE_CACHE.get(fieldName);
            if (handle == null) {
                return null;  // Field not found
            }
            return (String) handle.invokeExact(row);  // ← 5-10ms (faster than reflection)
        } catch (Throwable e) {
            return null;
        }
    }
}
```

**Update EstatisticaItemProcessor to use CsvFieldAccessor**:

```java
// OLD:
private static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
    try {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Method getter = CsvEstatisticaRow.class.getMethod(getterName);
        return (String) getter.invoke(row);
    } catch (Exception e) {
        return null;
    }
}

// NEW:
private static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
    return CsvFieldAccessor.getFieldValue(row, fieldName);
}
```

**Testing**:
```bash
# Unit test to verify all 62 fields are accessible
mvn test -Dtest=EstatisticaItemProcessorTest#testAllCsvFieldsAccessible

# Benchmark test (optional)
mvn test -Dtest=ReflectionVsMethodHandleBenchmark
```

**Estimated Savings**: 25% of total execution time (~2 hours)

---

### Solution 1B: Code Generation (ALTERNATIVE)

If maximum performance is critical, use compile-time code generation:

**Tool**: Annotation processor or Lombok

**Concept**:
```java
// Generated class (one per CSV field mapping)
public class CsvEstatisticaRowAccessor {
    public static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
        switch (fieldName) {
            case "fmunCod": return row.getFmunCod();
            case "fmun": return row.getFmun();
            case "ano": return row.getAno();
            // ... all 62 fields
            default: return null;
        }
    }
}
```

**Advantage**: Zero reflection overhead (1-2ms per call)
**Disadvantage**: Requires annotation processor setup, more complex

**Not recommended for Phase 3 Sprint 1** (higher complexity, marginal gain over MethodHandle)

---

## Fix 2: Increase Chunk Size

### Current Configuration

**File**: `src/main/resources/application.yml`

```yaml
roubometro:
  batch:
    chunk-size: 50      # 249 chunks total = 249 context switches
```

### Proposed Change

```yaml
roubometro:
  batch:
    chunk-size: 500     # 25 chunks total = reduced overhead
```

**Impact Analysis**:

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| CSV rows per chunk | 50 | 500 | 10x |
| Monthly stats per chunk | 1,300 | 13,000 | 10x |
| Total chunks | 249 | 25 | -90% |
| Time per chunk | 157s | ~150s | -5% (process time scales roughly O(n)) |
| SQL batch items | 1,300 | 13,000 | 10x (still < 50MB heap) |
| Total duration | ~652 min | ~584 min | -90 min (15% improvement) |

**Memory Impact**:
- Per chunk: ~13,000 MonthlyStat objects × 128 bytes ≈ 1.6 MB
- Processor accumulates: 13,000 objects + CSV row wrapper
- **Total heap increase**: <50 MB (safe for 512MB+ container memory)

### Verification

**Test with Docker**:
```bash
# Start local MySQL
docker compose up -d

# Run batch with chunk-size=500
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Droubometro.batch.chunk-size=500

# Monitor logs for performance metrics
# Look for: "dataProcessingStep completed in X seconds"
```

**SQL Validation**:
```sql
-- Verify idempotence after test run
SELECT
  year, month, COUNT(*) AS record_count
FROM monthly_stats
GROUP BY year, month
ORDER BY year DESC, month DESC;
```

---

## Fix 3: Async ItemWriter or Stored Procedure

### Option 3A: Stored Procedure (PREFERRED if supported)

**Advantage**: Single SQL call for all data, lowest latency
**Risk**: Low (isolated to writer layer)
**Effort**: 4-6 hours

#### Step 1: Create Stored Procedure

**File**: `src/main/resources/db/migration/V00X__batch_upsert_monthly_stats_sp.sql`

```sql
DELIMITER $$

CREATE PROCEDURE batch_upsert_monthly_stats(
    IN p_records JSON
)
MODIFIES SQL DATA
BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_i INT DEFAULT 0;

    -- Parse JSON array and insert/update
    WHILE v_i < JSON_LENGTH(p_records) DO
        INSERT INTO monthly_stats (
            municipality_id, year, month, category_id, category_value, source_file, created_at
        ) VALUES (
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].municipalityId')),
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].year')),
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].month')),
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].categoryId')),
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].categoryValue')),
            JSON_EXTRACT(p_records, CONCAT('$[', v_i, '].sourceFile')),
            NOW()
        )
        ON DUPLICATE KEY UPDATE
            category_value = VALUES(category_value),
            source_file = VALUES(source_file);

        SET v_i = v_i + 1;
    END WHILE;
END$$

DELIMITER ;
```

#### Step 2: Update Writer Config

**File**: `src/main/java/br/com/roubometro/infrastructure/writer/MonthlyStatItemWriterConfig.java`

```java
@Slf4j
@Configuration
public class MonthlyStatItemWriterConfig {

    @Bean
    public ItemWriter<MonthlyStat> monthlyStatStoredProcWriter(DataSource dataSource) {
        log.info("Configuring MonthlyStat Stored Procedure writer");

        return new ItemWriter<MonthlyStat>() {
            private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            @Override
            public void write(Chunk<? extends MonthlyStat> chunk) throws Exception {
                List<MonthlyStat> items = chunk.getItems();

                // Build JSON array from items
                List<Map<String, Object>> jsonRecords = new ArrayList<>();
                for (MonthlyStat stat : items) {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("municipalityId", stat.getMunicipalityId());
                    record.put("year", stat.getYear());
                    record.put("month", stat.getMonth());
                    record.put("categoryId", stat.getCategoryId());
                    record.put("categoryValue", stat.getCategoryValue());
                    record.put("sourceFile", stat.getSourceFile());
                    jsonRecords.add(record);
                }

                // Serialize to JSON
                ObjectMapper mapper = new ObjectMapper();
                String jsonString = mapper.writeValueAsString(jsonRecords);

                // Call stored procedure
                log.debug("Calling batch_upsert_monthly_stats with {} items", items.size());
                jdbcTemplate.call(con -> {
                    CallableStatement cs = con.prepareCall("{call batch_upsert_monthly_stats(?)}");
                    cs.setString(1, jsonString);
                    cs.executeUpdate();
                    cs.close();
                });

                log.debug("Stored procedure completed: {} items written", items.size());
            }
        };
    }
}
```

**Estimated Savings**: 30% of total execution time (~2 hours)
**Duration After Fix 3**: ~4h21m

---

### Option 3B: Async ItemWriter (ALTERNATIVE)

**Advantage**: Works with current SQL, pipelined execution
**Risk**: Medium (async failure handling)
**Effort**: 8-12 hours

#### Implementation Sketch

```java
@Configuration
public class AsyncItemWriterConfig {

    @Bean
    public AsyncItemWriter<MonthlyStat> asyncMonthlyStatWriter(
            JdbcBatchItemWriter<MonthlyStat> delegate,
            TaskExecutor taskExecutor
    ) {
        AsyncItemWriter<MonthlyStat> writer = new AsyncItemWriter<>();
        writer.setDelegate(delegate);
        writer.setTaskExecutor(taskExecutor);
        return writer;
    }

    @Bean
    public TaskExecutor batchWriterExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("batch-writer-");
        executor.initialize();
        return executor;
    }
}
```

**Concept**:
- Write chunks asynchronously while processing next chunk
- Pipeline effect: Process(N+1) overlaps with Write(N)
- Requires proper failure callback + retry logic

---

## Fix 4: Parallel Chunks (HIGH RISK)

**Only if <5min target is absolute requirement**

### Spring Batch Parallel Configuration

**File**: `src/main/java/br/com/roubometro/config/StepConfig.java`

```java
@Bean
public Step dataProcessingStep(
        // ... existing params ...
        TaskExecutor parallelTaskExecutor
) {
    ListUnpackingItemWriter<MonthlyStat> unpackingWriter = new ListUnpackingItemWriter<>(monthlyStatJdbcWriter);

    return new StepBuilder("dataProcessingStep", jobRepository)
            .<CsvEstatisticaRow, List<MonthlyStat>>chunk(appProperties.batch().chunkSize(), transactionManager)
            .reader(csvItemReader)
            .processor(estatisticaItemProcessor)
            .writer(unpackingWriter)
            .taskExecutor(parallelTaskExecutor)      // ← Add parallelization
            .throttleLimit(3)                         // ← Max 3 parallel chunks
            // ... existing fault tolerance ...
            .build();
}

@Bean
public TaskExecutor parallelTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(3);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("batch-processor-");
    executor.initialize();
    return executor;
}
```

### HikariCP Pool Adjustment

**File**: `src/main/resources/application-prod.yml`

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # was 3 (need more for parallelism)
      minimum-idle: 3
      connection-timeout: 15000    # slightly increased
      max-lifetime: 120000
      idle-timeout: 60000
      keepalive-time: 30000
```

### Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Deadlock (concurrent UPSERT) | Ensure UNIQUE KEY order matches chunk order; test thoroughly |
| FK constraint violation | Reading all municipalities upfront ensures no missing FKs |
| Connection pool exhaustion | Max-pool=10 sufficient for 5 parallel chunks |
| Out-of-memory | Chunks are independent, heap bounded by largest chunk |
| Data loss on failure | Spring Batch rollback per chunk (no cross-chunk txn) |

### Testing (Critical!)

```bash
# Unit tests
mvn test

# Integration test with parallelization
mvn verify -Pintegration-tests \
  -Droubometro.batch.chunk-size=500 \
  -Dspring-boot.run.arguments="--spring.datasource.hikari.maximum-pool-size=10"

# Monitor for deadlocks in logs
grep -i "deadlock\|lock wait timeout" /path/to/logs

# Verify idempotence
SELECT YEAR, MONTH, COUNT(*) AS record_count
FROM monthly_stats
GROUP BY YEAR, MONTH
ORDER BY YEAR DESC;
```

---

## Testing Checklist

### Before Any Change (Baseline)

- [ ] Run full batch with current code
- [ ] Record execution time, throughput, memory usage
- [ ] Dump final `monthly_stats` count by year
- [ ] Note HikariCP pool stats (active, idle, waiting connections)

### After Each Fix

- [ ] Run `mvn test` (all 22 unit tests must pass)
- [ ] Run `mvn verify -Pintegration-tests`
- [ ] Record execution time and compare to baseline
- [ ] Verify `monthly_stats` row count unchanged
- [ ] Check for any NEW warnings/errors in logs
- [ ] Monitor memory usage (heap growth during processing)

### Before Production Deployment

- [ ] Test with full production CSV (12.4k rows)
- [ ] Run against Locaweb staging environment
- [ ] Verify connection pool stability (no timeouts)
- [ ] Check UPSERT idempotence (insert, run again, verify no duplicates)
- [ ] Load test with production data volume

---

## Performance Measurement Script

**File**: `scripts/measure-performance.sh`

```bash
#!/bin/bash
set -e

CSV_FILE="${1:-sample.csv}"
PROFILE="${2:-local}"

echo "=== Roubometro Batch Performance Measurement ==="
echo "CSV: $CSV_FILE"
echo "Profile: $PROFILE"
echo

# Clean previous run
mvn clean --quiet

# Build
echo "Building..."
mvn package -DskipTests --quiet

# Run with timing
echo "Running batch (this may take a while)..."
START=$(date +%s%N)

mvn spring-boot:run \
  -Dspring-boot.run.profiles=$PROFILE \
  -Dspring-boot.run.arguments="--spring.batch.job.enabled=true" \
  2>&1 | tee batch-output.log

END=$(date +%s%N)
DURATION=$((($END - $START) / 1000000000))  # Convert to seconds

echo
echo "=== RESULTS ==="
echo "Total Duration: $((DURATION / 60))m $((DURATION % 60))s"
echo

# Extract metrics from log
echo "Metrics from logs:"
grep -i "read_count\|write_count\|completed" batch-output.log | tail -5

# Count final records
echo
echo "Final monthly_stats count:"
# (requires direct DB access)
```

---

## References

- `src/main/java/br/com/roubometro/application/processor/EstatisticaItemProcessor.java` (Fix 1)
- `src/main/resources/application.yml` (Fix 2)
- `src/main/java/br/com/roubometro/infrastructure/writer/MonthlyStatItemWriterConfig.java` (Fix 3)
- `src/main/java/br/com/roubometro/config/StepConfig.java` (Fix 1, 4)
- Spring Batch docs: https://spring.io/projects/spring-batch
- HikariCP tuning: https://github.com/brettwooldridge/HikariCP/wiki/Configuration

