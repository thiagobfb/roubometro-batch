# Performance Optimization Plan — Phase 3

**Generated**: 2026-03-19
**Status**: READY FOR IMPLEMENTATION
**Target**: Reduce 10h53min → <5min execution (10x improvement)

---

## Current State

| Metric | Value |
|--------|-------|
| Last execution | 2026-03-13 (Locaweb production) |
| Duration | 10h 52m 54s |
| Throughput | 0.31 CSV rows/sec (12.4k rows = 652.9 min) |
| CSV→DB ratio | 1:26 (12.4k rows → 323k monthly_stats) |
| Chunks | 249 chunks × 157s each |

---

## Root Cause Analysis

### Time Breakdown Per Chunk (50 rows)

| Phase | Duration | Details |
|-------|----------|---------|
| Read CSV | 1s | Disk/buffer I/O |
| **Process** | **50s** | 50 rows × 62 columns × reflection overhead |
| **Write SQL** | **50s** | 1 SQL INSERT with 1.3k rows (Locaweb latency dominates) |
| Commit/sync | 50s | Transaction commit + HikariCP pooling |
| **Total** | **~157s** | 249 chunks × 157s ≈ 10h53m |

### Primary Bottlenecks (Ranked by Impact)

1. **Reflection Overhead in EstatisticaItemProcessor** (25-30% of time)
   - `getFieldValue()` uses `Method.getMethod(getterName) + invoke()` per column
   - Called 3,100 times per chunk (50 rows × 62 columns)
   - Each reflection call: ~10-20ms overhead
   - **Can be eliminated via MethodHandle or code-gen**

2. **Network Latency (AWS↔Locaweb)** (30-40% of time)
   - SQL write per chunk: ~50s (latency-bound)
   - Current batching: 1 SQL per chunk with 1.3k items ✓ (good)
   - But: 249 separate round-trips (no pipelining)
   - **Can be improved via async writes or larger batches**

3. **Chunk Count Overhead** (10-15% of time)
   - Current: 50 rows per chunk = 249 chunks
   - Each chunk = overhead (context switch, JdbcBatchItemWriter setup, transaction commit)
   - **Can be improved by increasing chunk size to 500**

---

## Optimization Strategy

### Fix 1: Eliminate Reflection (25% improvement)

**Change**: Replace `getFieldValue()` with direct field access

**Current Code**:
```java
private static String getFieldValue(CsvEstatisticaRow row, String fieldName) {
    try {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Method getter = CsvEstatisticaRow.class.getMethod(getterName);
        return (String) getter.invoke(row);  // ← Reflection overhead: 10-20ms per call
    } catch (Exception e) {
        return null;
    }
}
```

**Proposed Solutions** (pick one):

**Option A: MethodHandle (Java 7+, ~2x faster)**
- Use `MethodHandles.lookup()` + cache handles
- ~5-10ms per call (vs 10-20ms for reflection)
- Effort: Medium
- Risk: Low

**Option B: Code Generation (Compile-time, zero overhead)**
- Annotation processor or Lombok-like tool
- Generated CsvEstatisticaRowAccessor with if/switch on fieldName
- ~1-2ms per call
- Effort: Medium-High
- Risk: Low

**Option C: Simplest (if CsvEstatisticaRow uses Lombok @Getter)**
- Cache `MethodHandle` instances in static map
- Reuse handles across rows
- Effort: Low
- Risk: Very Low

**Expected Impact**:
- 3,100 reflection calls × 0.01s saved ≈ 31-62s saved per chunk
- 249 chunks × 0.25s ≈ **62.2 min saved** (9.6% of total)
- **New total: ~591 min (9h51m)** — but combined with Fix 2 & 3 below

---

### Fix 2: Increase Chunk Size (15% improvement)

**Change**: `chunk-size: 50` → `chunk-size: 500`

**Impact**:
- Reduces chunk count: 249 → 25 chunks
- Reduces per-chunk overhead (fewer SQL executes, commits, context switches)
- Memory impact: ~13k objects vs 1.3k (still <20MB heap, acceptable)
- HikariCP pool (max=3) still sufficient for serial processing

**Configuration**:
```yaml
roubometro:
  batch:
    chunk-size: 500  # was 50
```

**Expected Impact**:
- Fewer context switches, fewer commits
- ~25 chunks × 157s = 3925s ≈ 65.4 min
- **Save ~98 min** (15% improvement)
- **New total: ~554 min (9h14m)** when combined with Fix 1

---

### Fix 3: Async ItemWriter or Stored Procedure (30% improvement)

**Problem**: Each chunk waits for SQL execution (blocking). 249 chunks × 50s write latency = significant waste.

**Current Flow**:
```
Chunk 1: Read → Process (50s) → Write (50s) [BLOCK] → Commit
Chunk 2: [WAIT FOR CHUNK 1] → Read → Process → Write [BLOCK] → Commit
...
```

**Proposed Solution A: Async ItemWriter (Spring Batch 5+)**

Queue writes asynchronously while processing next chunk:
```
Chunk 1: Read → Process → Write [ASYNC: don't block] → Commit [wait for write callback]
Chunk 2: [Start immediately] → Read → Process → Write [ASYNC] → Commit
Chunk 3: [Overlap with Chunk 2] → Read → Process → Write [ASYNC] → Commit
```

- Pipelining effect: chunk N processing can overlap with chunk N-1 write
- Expected speedup: **2-3x** on write phase (50s → 20-25s per chunk due to overlap)
- Effort: **High** (requires custom AsyncItemWriter + retry logic)
- Risk: **Medium** (failure handling, thread safety)

**Proposed Solution B: Stored Procedure**

If Locaweb supports stored procedures, batch 13k rows into single call:
```sql
CALL batch_upsert_monthly_stats(@json_array_of_records);
```

- Reduces round-trips: 249 → 1 SQL call
- Expected speedup: **70% reduction** (50s × 249 → 50s × 1 = 49.5 min saved)
- Effort: **Medium** (need to write SP + JSON marshalling)
- Risk: **Low** (isolated to writer layer)

**Proposed Solution C: Parallel Chunks (if max-pool allows)**

Increase HikariCP max-pool to 5-10, process multiple chunks in parallel:
```
Thread 1: Chunk 1 → Process → Write
Thread 2: Chunk 2 → Process → Write [parallel]
Thread 3: Chunk 3 → Process → Write [parallel]
```

- Expected speedup: 2-3x (3-5 parallel workers)
- Effort: **High** (requires Spring Batch parallel step setup + chunk reader redesign)
- Risk: **High** (ordering issues, FK constraints, connection pool exhaustion)

**Recommendation**: Start with **Solution B (Stored Proc)** as a quick win, then **Solution A (Async)** if needed.

**Expected Impact**:
- Fix B (Stored Proc): 249 SQL → 1 SQL = ~49.5 min saved (7.6% improvement)
- Fix A (Async pipelining): ~50% of write latency = ~61.5 min saved (9.4% improvement)
- **Combined Fix 3: ~30% improvement → ~456 min (7h36m)**

---

## Cumulative Improvements

| Fix | Type | Delta | Cumulative | New Duration |
|-----|------|-------|------------|--------------|
| Baseline | — | — | 0% | 10h53m (653 min) |
| Fix 1: Reflection | -25% | -163 min | -25% | 9h50m (590 min) |
| Fix 2: Chunk size | -15% | -98 min | -37% | 9h12m (492 min) |
| Fix 3: Async/SP | -30% | -196 min | **-60%** | **7h36m (456 min)** |

**Status**: 60% improvement gets to **7h36m** (still >5min target)

### If 5min Target is Absolute Requirement

Additional optimizations needed:
- **Fix 4: Parallel chunks** (3-5x workers) → -50% more → **~3h48m**
- **Fix 5: Connection pooling tuning** (reduce latency) → -10% more → **~3h24m**
- **Fix 6: Database indexing** (if FK lookups are slow) → -5% more → **~3h15m**

Combined Fixes 1-6: **~70% improvement → 3h15m** (still >5min but much better)

---

## Recommendation: Phased Implementation

### Phase 3 Sprint 1 (LOW RISK)
- [ ] Fix 1: MethodHandle caching (eliminate reflection)
  - Estimated effort: 4-6 hours
  - Estimated gain: 25%
  - Test: Unit tests + integration test

- [ ] Fix 2: Chunk size 50→500
  - Estimated effort: 30 minutes
  - Estimated gain: 15%
  - Test: Integration test

**Expected result after Sprint 1**: ~9h12m (down from 10h53m) ✓ **37% improvement**

### Phase 3 Sprint 2 (MEDIUM RISK)
- [ ] Fix 3A: Investigate Locaweb stored procedure support
  - If YES: Implement batch UPSERT stored proc
    - Effort: 4-6 hours
    - Gain: 30%
  - If NO: Implement async ItemWriter
    - Effort: 8-12 hours
    - Gain: 30%

**Expected result after Sprint 2**: ~7h36m (down from 10h53m) ✓ **60% improvement**

### Phase 3 Sprint 3 (IF NEEDED, HIGH RISK)
- [ ] Fix 4: Parallel chunks (only if 5min absolute requirement)
  - Effort: 12-16 hours
  - Gain: 50%
  - Risk: High (FK ordering, connection pool, idempotence)

**Expected result after Sprint 3**: ~3h24m ✓ **70% improvement**

---

## Testing Strategy

Before/after each fix:

1. **Unit Tests** (22 existing must pass)
   ```bash
   mvn test
   ```

2. **Integration Test** (run with Docker MySQL + sample CSV)
   ```bash
   mvn verify -Pintegration-tests
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   # Monitor: log "Completed in X sec"
   ```

3. **Production Validation** (if available)
   - Use real CSV + Locaweb connection
   - Measure end-to-end time
   - Verify idempotence (UPSERT)

---

## Constraints & Non-Goals

✓ **IN SCOPE**:
- Processor performance
- Writer batching
- Chunk size tuning
- Connection pool config

✗ **OUT OF SCOPE**:
- CSV download latency (already cached)
- Database schema changes
- roubometro-back integration (FK constraints must remain)
- HikariCP beyond config tuning

---

## Files to Modify

| Fix | File(s) |
|-----|---------|
| 1 | `src/main/java/.../processor/EstatisticaItemProcessor.java` |
| 2 | `src/main/resources/application.yml` |
| 3A | `src/main/java/.../writer/MonthlyStatItemWriterConfig.java` (add SP) |
| 3B | `src/main/java/.../writer/MonthlyStatItemWriterConfig.java` (async) |
| 4 | `src/main/java/config/StepConfig.java` (parallel job) |

---

## Success Criteria

| Criteria | Target | Status |
|----------|--------|--------|
| Duration | <5 min (300s) | ❌ Current: 10h53m |
| After Fix 1+2 | ~9h12m | ⏳ Pending |
| After Fix 1+2+3 | <8h (preferable) | ⏳ Pending |
| All 22 unit tests pass | 100% | ✓ Current: 22/22 |
| Integration test passes | 1 run | ⏳ Pending |
| Idempotence verified | N/A | ✓ Confirmed 2026-03-13 (UPSERT works) |

---

## References

- Baseline: `docs/INCIDENT-REPORT-2026-03-13.md`
- Detailed analysis: `PERFORMANCE-ANALYSIS.json`
- Code: `src/main/java/br/com/roubometro/`
- Config: `src/main/resources/application*.yml`
