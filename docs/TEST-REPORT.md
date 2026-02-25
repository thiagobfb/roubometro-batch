# Relatorio de Testes — Roubometro Batch

**Build**: `mvn clean verify -Pintegration-tests`
**Java**: 21.0.10 | **Spring Boot**: 3.4.3 | **Testcontainers**: 1.21.4
**Resultado**: **BUILD SUCCESS** | **Tempo total**: 37.7s

---

## Testes Unitarios (22/22 PASS)

| # | Classe | Teste | Status |
|---|--------|-------|--------|
| 1 | `EstatisticaItemProcessorTest` | processValidRow | PASS |
| 2 | | processRowWithZeroValues_excludedByDefault | PASS |
| 3 | | processRowWithZeroValues_includedWhenConfigured | PASS |
| 4 | | processRowWithMissingFmunCod | PASS |
| 5 | | processRowWithInvalidYear | PASS |
| 6 | | processRowWithInvalidMonth | PASS |
| 7 | | processRowWithUnknownMunicipality | PASS |
| 8 | | processRowWithNegativeValue | PASS |
| 9 | | processRowWithControlChars | PASS |
| 10 | | processRowWithNullFields | PASS |
| 11 | | processRowWithEmptyAno | PASS |
| 12 | | processRowWithEmptyMes | PASS |
| 13 | `PortalHttpClientTest` | rejectsNonWhitelistedHost | PASS |
| 14 | | rejectsIpLiteralUrl | PASS |
| 15 | | rejectsFtpScheme | PASS |
| 16 | | rejectsInvalidUrl | PASS |
| 17 | | acceptsWhitelistedHost | PASS |
| 18 | `CsvItemReaderTest` | readsAllRows | PASS |
| 19 | | parsesFieldsCorrectly | PASS |
| 20 | | emptyCsvReturnsNoRows | PASS |
| 21 | | allFieldsAreStrings | PASS |
| 22 | | readsAccentedCharacters | PASS |

---

## Testes de Integracao (13/13 PASS)

| # | Classe | Teste | Status | Tempo |
|---|--------|-------|--------|-------|
| 23 | `MonthlyStatItemWriterIntegrationTest` | writesStatsToDatabase | PASS | |
| 24 | | upsertUpdatesExistingRecord | PASS | |
| 25 | | idempotentWrite | PASS | 21.1s |
| 26 | `DatabaseSetupIntegrationTest` | apiTablesExist | PASS | |
| 27 | | flywayAppliedV1Migration | PASS | |
| 28 | | batchJobExecutionReportTableExists | PASS | |
| 29 | | seedDataPresent | PASS | |
| 30 | | springBatchTablesExist | PASS | |
| 31 | | monthlyStatsUniqueKeyExists | PASS | 0.2s |
| 32 | `JobIntegrationTest` | happyPath_downloadsAndProcessesCsv | PASS | |
| 33 | | rerunSameFile_skipsProcessing | PASS | |
| 34 | | portalDown_jobFails | PASS | |
| 35 | | idempotency_twoRunsSameResult | PASS | 7.1s |

---

## Resumo

| Metrica | Valor |
|---------|-------|
| Total de testes | **35** |
| Passou | **35** |
| Falhou | **0** |
| Erros | **0** |
| Ignorados | **0** |
| Taxa de sucesso | **100%** |
| Unitarios | 22 (0.4s) |
| Integracao | 13 (28.3s) |

## Cobertura por camada

| Camada | Classe testada | Testes |
|--------|---------------|--------|
| **Processor** | EstatisticaItemProcessor | 12 |
| **Reader** | CsvItemReaderConfig | 5 |
| **Writer** | MonthlyStatItemWriter | 3 |
| **Client** | PortalHttpClient (seguranca) | 5 |
| **Infra DB** | Flyway, schema, seed | 6 |
| **Job E2E** | Job completo (WireMock + Testcontainers) | 4 |

## Ambiente de execucao

- **OS**: Linux 6.6.87.2 (WSL2)
- **JDK**: Oracle JDK 21.0.10 LTS
- **Maven**: 3.9.12
- **Docker**: Docker Desktop 4.55.0 (Engine 29.1.3)
- **MySQL (Testcontainers)**: 8.0
- **WireMock**: 3.10.0

## Como reproduzir

```bash
# Testes unitarios apenas
mvn test

# Testes unitarios + integracao (requer Docker)
mvn clean verify -Pintegration-tests
```

> **Nota WSL2**: Requer Testcontainers 1.21.4+ para compatibilidade com Docker Desktop WSL2 proxy socket. JDK 21 recomendado (`JAVA_HOME=/home/thiago/jdk/jdk-21.0.10`).
