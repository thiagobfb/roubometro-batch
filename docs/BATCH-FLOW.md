# Roubometro Batch — Fluxo de Classes

Documentacao do fluxo completo do job batch que ingere dados de estatisticas de seguranca publica do ISP-RJ.

---

## Visao Geral

O sistema e um **Spring Batch** job que:

1. Faz download de um CSV do portal ISP-RJ
2. Verifica se o arquivo e novo (via hash SHA-256)
3. Le o CSV, transforma cada linha em N registros (pivot) e persiste no MySQL

```
SchedulingConfig (cron)
    |
    v
BatchJobConfig [roubometroDataSyncJob]
    |
    +---> Step 1: dataAcquisitionStep (Tasklet)
    |         |
    |         v
    |     DataAcquisitionTasklet
    |         |-- SchemaValidationService.validate()
    |         |-- FileDownloadService.download()
    |         |       |-- PortalHttpClient.download()
    |         |-- FileMetadataService.isNewFile()
    |         +-- FileMetadataService.register()
    |
    +---> NewFileDecider
    |         |-- PROCESS (arquivo novo) --+
    |         |-- SKIP (mesmo hash) -------+---> Step 3
    |                                      |
    +---> Step 2: dataProcessingStep       | (chunk-oriented)
    |         |                            |
    |         |-- LookupInitializerListener.beforeStep()
    |         |       |-- CategoryLookupService.initialize()
    |         |       +-- MunicipalityLookupService.initialize()
    |         |
    |         |-- [READER]  CsvItemReader (FlatFileItemReader)
    |         |-- [PROCESSOR] EstatisticaItemProcessor
    |         |-- [WRITER] ListUnpackingItemWriter
    |         |       +-- JdbcBatchItemWriter (UPSERT)
    |         |
    |         +-- LookupInitializerListener.afterStep()
    |
    +---> Step 3: finalizationStep (Tasklet)
              |
              v
          FinalizationTasklet
              |-- Coleta metricas (read/write/skip counts)
              |-- Salva BatchJobExecutionReport
              |-- FileMetadataService.markProcessed()
              +-- Limpa arquivo temporario
```

---

## Ponto de Entrada

### `SchedulingConfig`
- **Pacote**: `config`
- **Trigger**: Cron configuravel (`roubometro.schedule.cron`)
- **Condicao**: So ativa quando `roubometro.schedule.enabled=true`
- Cria `JobParameters` com timestamp unico e lanca o job via `JobLauncher`

### `BatchJobConfig`
- **Pacote**: `config`
- Define o job `roubometroDataSyncJob` com o fluxo condicional:
  - `dataAcquisitionStep` → `NewFileDecider` → `dataProcessingStep` | `finalizationStep`

---

## Step 1 — Data Acquisition (Tasklet)

### `DataAcquisitionTasklet`
- **Pacote**: `application.step`
- **Responsabilidade**: Orquestrar download, deduplicacao e registro de metadados
- **Fluxo interno**:

```
execute()
   |
   |-- 1. SchemaValidationService.validate()
   |       Verifica se as tabelas monthly_stats e categories existem
   |       e possuem as colunas esperadas (via information_schema)
   |
   |-- 2. downloadWithRetry(csvUrl)
   |       Retry com backoff exponencial (configuravel)
   |       |
   |       +-- FileDownloadService.download(url)
   |               |
   |               +-- PortalHttpClient.download(url)
   |               |       - Valida URL (whitelist de hosts, rejeita IPs, so HTTP/HTTPS)
   |               |       - HTTP GET com timeout configuravel
   |               |       - Rejeita redirects (seguranca)
   |               |
   |               +-- Stream para disco com:
   |                       - Calculo SHA-256 incremental
   |                       - Validacao de tamanho maximo (50MB)
   |                       - Nome de arquivo gerado (UUID, sem input do usuario)
   |                       - Protecao contra path traversal
   |
   |-- 3. FileMetadataService.isNewFile(hash)
   |       Compara hash com o ultimo arquivo processado (batch_file_metadata)
   |
   +-- 4a. [NOVO] FileMetadataService.register() → salva metadados, coloca no ExecutionContext:
   |        - newFileAvailable = true
   |        - csvFilePath, csvFileName, fileMetadataId
   |
   +-- 4b. [MESMO] Deleta temp file, newFileAvailable = false
```

### Classes envolvidas

| Classe | Pacote | Papel |
|--------|--------|-------|
| `SchemaValidationService` | `application.service` | Valida schema do banco antes de operar |
| `FileDownloadService` | `application.service` | Download seguro com hash e limite de tamanho |
| `PortalHttpClient` | `infrastructure.client` | HTTP client com validacoes de seguranca |
| `FileMetadataService` | `application.service` | Gerencia `batch_file_metadata` (registro e dedup por hash) |

---

## Decisor — NewFileDecider

### `NewFileDecider`
- **Pacote**: `config`
- **Interface**: `JobExecutionDecider`
- Le `newFileAvailable` do `ExecutionContext`
- Retorna `PROCESS` (vai pro Step 2) ou `SKIP` (pula direto pro Step 3)

---

## Step 2 — Data Processing (Chunk-Oriented)

Este e o step principal. Processa o CSV em chunks de 50 linhas.

### Listener: `LookupInitializerListener`
- **Pacote**: `config`
- **Quando**: `beforeStep()` — antes do primeiro chunk
- **O que faz**: Pre-carrega caches em memoria:
  - `CategoryLookupService` — carrega/cria categorias no banco, monta cache `Map<nome, id>`
  - `MunicipalityLookupService` — carrega IDs de municipios, monta cache `Set<id>`
- **afterStep()**: Loga metricas finais do step

### Reader: `CsvItemReaderConfig` → `FlatFileItemReader<CsvEstatisticaRow>`
- **Pacote**: `infrastructure.reader`
- **Bean**: `@StepScope` (recebe `csvFilePath` do ExecutionContext em runtime)
- **Configuracao**:
  - Encoding: ISO-8859-1 (padrao do ISP-RJ)
  - Delimitador: `;` (ponto-e-virgula)
  - Skip header: 1 linha
  - 62 colunas mapeadas via `CsvColumnNames.COLUMNS`
- **Output**: `CsvEstatisticaRow` (DTO com 62 campos String)
- **Regra**: Reader e "burro" — nao faz validacao nem transformacao

### Processor: `EstatisticaItemProcessor`
- **Pacote**: `application.processor`
- **Interface**: `ItemProcessor<CsvEstatisticaRow, List<MonthlyStat>>`
- **Toda a logica de negocio esta aqui**
- **Fluxo por linha CSV**:

```
process(CsvEstatisticaRow row) → List<MonthlyStat>
   |
   |-- 1. Sanitize: remove control chars, trim whitespace
   |
   |-- 2. Validar campos obrigatorios (fmun_cod, ano, mes)
   |       Lanca CsvParsingException se invalido
   |
   |-- 3. Parse numerico (Long, Short, Byte)
   |       Valida ranges: ano [2000-2100], mes [1-12]
   |
   |-- 4. Validar municipio existe (via MunicipalityLookupService cache)
   |       Retorna null (skip) se nao encontrado
   |
   +-- 5. PIVOT: Para cada uma das 51 colunas de crime:
           |
           |-- Ler valor via reflection (getter do campo)
           |-- Sanitize e parse para int
           |-- Ignorar se vazio, nao-numerico ou zero (configuravel)
           |-- Rejeitar valores negativos (CsvParsingException)
           |-- Resolver categoryId via CategoryLookupService cache
           +-- Criar MonthlyStat via Builder
```

- **Transformacao 1:N**: 1 linha CSV (~51 categorias) → ate 51 objetos `MonthlyStat`
- **Mapeamento de colunas**: `CategoryColumnMapping` (mapa estatico campo→nome legivel)

### Writer: `ListUnpackingItemWriter<MonthlyStat>` → `JdbcBatchItemWriter<MonthlyStat>`
- **Pacote**: `infrastructure.writer`
- **Problema resolvido**: O processor retorna `List<MonthlyStat>` por linha, mas o writer espera itens individuais
- **Solucao**: `ListUnpackingItemWriter` "desempacota" `List<List<MonthlyStat>>` em `List<MonthlyStat>` e delega

### Writer delegate: `MonthlyStatItemWriterConfig` → `JdbcBatchItemWriter`
- **Pacote**: `infrastructure.writer`
- **SQL**: `INSERT ... ON DUPLICATE KEY UPDATE` (UPSERT no MySQL)
- **Deduplicacao**: UK `uk_mun_date_category(municipality_id, year, month, category_id)`
- **Idempotencia**: Rodar N vezes com o mesmo arquivo produz o mesmo resultado

### Tratamento de erros no Step 2

| Tipo | Tratamento |
|------|------------|
| `CsvParsingException` | Skip (ate `skipLimit`) |
| `DataIntegrityException` | Skip |
| `NumberFormatException` | Skip |
| `DeadlockLoserDataAccessException` | Retry (ate 3x) |
| `TransientDataAccessException` | Retry (ate 3x) |

---

## Step 3 — Finalization (Tasklet)

### `FinalizationTasklet`
- **Pacote**: `application.step`
- **Sempre executado** (tanto no fluxo PROCESS quanto no SKIP)
- **Responsabilidades**:

```
execute()
   |
   |-- 1. Coletar metricas do dataProcessingStep
   |       (readCount, writeCount, skipCount)
   |
   |-- 2. Calcular duracao total do job
   |
   |-- 3. Salvar BatchJobExecutionReport no banco
   |       Status: "COMPLETED" ou "SKIPPED"
   |
   |-- 4. Marcar arquivo como processado
   |       FileMetadataService.markProcessed(id, rowCount)
   |
   +-- 5. Limpar arquivo CSV temporario
```

---

## Modelo de Dados

### Tabelas do batch (Flyway)

```
batch_file_metadata          batch_job_execution_report
+-------------------+       +---------------------------+
| id                |       | id                        |
| file_name         |       | job_execution_id          |
| file_url          |       | file_metadata_id (FK)     |
| file_hash (SHA256)|       | status                    |
| file_size_bytes   |       | rows_read                 |
| downloaded_at     |       | rows_written              |
| processed         |       | rows_skipped              |
| processed_at      |       | rows_errors               |
| row_count         |       | duration_ms               |
| created_at        |       | error_message             |
| updated_at        |       | created_at                |
+-------------------+       +---------------------------+
```

### Tabelas compartilhadas (Knex — roubometro-back)

```
categories                   monthly_stats
+-----------+               +-------------------+
| id        |               | id                |
| name      |               | municipality_id   |
+-----------+               | year              |
                            | month             |
municipalities              | category_id (FK)  |
+-----------+               | category_value    |
| id (IBGE) |               | source_file       |
| name      |               | created_at        |
| state_id  |               +-------------------+
| region    |               UK(municipality_id, year,
+-----------+                  month, category_id)
```

---

## Entidades JPA

| Classe | Tabela | Dono | Anotacoes Lombok |
|--------|--------|------|------------------|
| `MonthlyStat` | `monthly_stats` | Knex (API) | `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` |
| `Category` | `categories` | Knex (API) | idem |
| `Municipality` | `municipalities` | Knex (API) | idem |
| `BatchFileMetadata` | `batch_file_metadata` | Flyway (Batch) | idem |
| `BatchJobExecutionReport` | `batch_job_execution_report` | Flyway (Batch) | idem |
| `CsvEstatisticaRow` | — (DTO) | — | `@Getter @Setter @NoArgsConstructor` |

---

## Diagrama de Dependencias entre Classes

```
SchedulingConfig
    +---> JobLauncher
    +---> Job (roubometroDataSyncJob)

BatchJobConfig
    +---> Step (dataAcquisitionStep)
    +---> Step (dataProcessingStep)
    +---> Step (finalizationStep)
    +---> NewFileDecider

DataAcquisitionTasklet
    +---> SchemaValidationService ---> JdbcTemplate
    +---> FileDownloadService ---> PortalHttpClient ---> HttpClient
    +---> FileMetadataService ---> BatchFileMetadataRepository
    +---> AppProperties

EstatisticaItemProcessor
    +---> CategoryLookupService ---> JdbcTemplate
    +---> MunicipalityLookupService ---> JdbcTemplate
    +---> CategoryColumnMapping (static)

FinalizationTasklet
    +---> BatchJobExecutionReportRepository
    +---> FileMetadataService
```

---

## Fluxo de Dados (CSV → Banco)

```
Portal ISP-RJ (HTTP)
    |
    | download
    v
/tmp/roubometro/batch_<uuid>.csv
    |
    | FlatFileItemReader (ISO-8859-1, delimitador ";")
    v
CsvEstatisticaRow (62 campos String)
    |
    | EstatisticaItemProcessor (sanitize + validate + pivot)
    v
List<MonthlyStat> (ate 51 por linha CSV)
    |
    | ListUnpackingItemWriter (flatten)
    v
List<MonthlyStat> (flat)
    |
    | JdbcBatchItemWriter (INSERT ... ON DUPLICATE KEY UPDATE)
    v
MySQL: monthly_stats
```

---

## Numeros Tipicos

| Metrica | Valor |
|---------|-------|
| Linhas no CSV | ~12.144 (92 municipios x 12 meses x ~11 anos) |
| Categorias de crime | 51 |
| Registros por linha | ate 51 `MonthlyStat` |
| Total potencial de INSERTs | ~619.344 |
| Chunk size | 50 linhas CSV (~2.550 INSERTs/chunk) |
| Chunks | ~243 |
| Pool de conexoes | max 3 (HikariCP) |
