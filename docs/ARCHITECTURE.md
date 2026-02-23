# Roubometro Batch -- Documento de Arquitetura

> **Versao**: 1.1.0
> **Data**: 2026-02-23
> **Autor**: system-architect agent
> **Status**: Proposta para aprovacao
> **Alteracoes v1.1.0**: CSV mensal (BaseMunicipioMensal.csv) em vez de anual; FK direta municipalities.id=IBGE code; tabela de mapeamento removida; riscos A1/A2/A3/R12/R13/R15 resolvidos

---

## Sumario

1. [Resultado da Varredura do roubometro-back](#1-resultado-consolidado-da-varredura-do-roubometro-back)
2. [Mapa de Tabelas (Knex vs Flyway)](#2-mapa-de-tabelas)
3. [Estrutura CSV Real (ISP-RJ)](#3-estrutura-do-csv-real-isp-rj)
4. [Estrutura de Pacotes](#4-estrutura-de-pacotes)
5. [Modelo de Dados (DDL)](#5-modelo-de-dados-ddl)
6. [Especificacao dos Steps](#6-especificacao-dos-steps)
7. [ADRs](#7-adrs--decisoes-tecnicas)
8. [Docker Compose](#8-docker-compose)
9. [Configuracao de Profiles](#9-configuracao-de-profiles)
10. [Riscos e Premissas](#10-registro-de-riscos-e-premissas)

---

## 1. Resultado Consolidado da Varredura do roubometro-back

### 1.1 Stack e Versoes

| Componente | Versao |
|------------|--------|
| Runtime | Node.js |
| Framework | Fastify 5.7.1 |
| DB driver | mysql (via Knex 3.1.0) |
| Validacao | Zod 4.3.5 |
| TypeScript | 5.9.3 |
| Auth | JWT (jsonwebtoken 9.0.3) + cookies |
| Formatacao | Biome 2.3.11 |

### 1.2 Tabelas Existentes (Knex/API)

Todas usam **snake_case** (minusculo). Convencoes identificadas:

- Timestamps: `created_at`, `updated_at`
- Foreign keys: `{tabela_singular}_id` (ex: `municipality_id`, `category_id`)
- IDs: `id` (auto-increment integer)

```
regions          (id, name, abbreviation, created_at, updated_at)
states           (id, name, abbreviation, region_id, created_at, updated_at)
municipalities   (id, name, state_id, region?, created_at, updated_at)
users            (id, email, cpf, name, state_id?, municipality_id?, phone?, active, profile, photo?, token_temp?, created_at, updated_at)
categories       (id, name)
monthly_stats    (id, municipality_id, year, month, category_id, category_value, source_file?, created_at)
user_reports     (id, user_id, municipality_id, reported_at, created_at, category_id, description?, status, has_occurred, moderation_notes?)
refresh_tokens   (id, user_id, token_hash, expires_at, revoked, created_at, last_used_at?, user_agent?, ip_address?)
```

### 1.3 Descoberta Critica

As tabelas `categories` e `monthly_stats` **ja existem** e sao gerenciadas pelo Knex (API). A API ja le dessas tabelas. O batch **deve inserir dados nessas tabelas** no formato esperado, sem assumir ownership de migrations sobre elas. Isso e tratado como decisao arquitetural critica no ADR-007.

### 1.4 Rotas da API

- `/auth/*` -- autenticacao JWT
- `/ibge/*` -- regioes, estados, municipios (leitura de regions, states, municipalities)
- `/user/*` -- CRUD de usuarios

A API consome `monthly_stats` via Knex fazendo JOIN com `categories` e `municipalities`. O batch precisa gravar dados que sejam compativeis com esses JOINs.

### 1.5 Configuracao de Banco (API)

- Client: `mysql` (driver mysql, nao mysql2)
- Conexao via env vars: `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`
- Sem SSL no dev local

---

## 2. Mapa de Tabelas

### 2.1 Ownership

| Tabela | Owner | Migration Tool | Quem Le | Quem Escreve |
|--------|-------|---------------|---------|--------------|
| `regions` | API | Knex | API + Batch (lookup) | API |
| `states` | API | Knex | API + Batch (lookup) | API |
| `municipalities` | API | Knex | API + Batch (lookup) | API |
| `users` | API | Knex | API | API |
| `categories` | API | Knex | API + Batch (lookup) | API (seed) / Batch (insert if missing) |
| `monthly_stats` | **Compartilhada** | Knex (DDL) | API | **Batch** (insert/upsert) |
| `user_reports` | API | Knex | API | API |
| `refresh_tokens` | API | Knex | API | API |
| `batch_file_metadata` | **Batch** | Flyway | Batch | Batch |
| `batch_job_execution_report` | **Batch** | Flyway | Batch | Batch |
| `BATCH_*` (Spring Batch) | **Batch** | Spring Batch auto-schema | Batch | Batch |

### 2.2 Regras de Convivencia

1. **O batch NUNCA executa ALTER TABLE, DROP TABLE ou DELETE em tabelas da API.**
2. **O batch faz INSERT em `monthly_stats`** usando `INSERT IGNORE` ou `ON DUPLICATE KEY UPDATE` (MySQL) para garantir idempotencia.
3. **O batch faz SELECT em `municipalities` e `categories`** para resolver foreign keys (lookup).
4. **O batch cria via Flyway apenas tabelas com prefixo `batch_`** (exceto tabelas `BATCH_*` do framework Spring Batch).
5. **Categorias**: se o batch encontrar uma categoria no CSV que nao existe em `categories`, ele a insere. Caso contrario, reutiliza o `id` existente. Isso e feito via `INSERT IGNORE` na tabela `categories`.

---

## 3. Estrutura do CSV Real (ISP-RJ)

### 3.1 Fonte

- **URL**: `https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv`
- **Descricao**: Estatisticas de seguranca: serie historica mensal por municipio desde 2014 (valores absolutos)
- **Delimitador**: Ponto-e-virgula (`;`)
- **Encoding**: Provavel ISO-8859-1 ou Windows-1252 (dados brasileiros)
- **Valores**: Inteiros (contagens absolutas, nao taxas) — compativel com `monthly_stats.category_value INT`
- **Texto**: Aspas duplas opcionais (ex: `"Angra dos Reis"`)
- **Vantagens sobre BaseMunicipioMensal.csv**: valores inteiros (cabem em INT), campo `mes` (1-12) mapeia direto para `monthly_stats.month`

### 3.2 Colunas do CSV (62 colunas)

```
fmun_cod                    -- Codigo IBGE do municipio (int, ex: 3300100) → FK direta para municipalities.id
fmun                        -- Nome do municipio (string, ex: "Angra dos Reis")
ano                         -- Ano da estatistica (int, ex: 2014)
mes                         -- Mes da estatistica (int, 1-12) → mapeia direto para monthly_stats.month
mes_ano                     -- Mes/Ano concatenado (string, ex: "01/2014") — informativo, nao usado
regiao                      -- Regiao do estado (string: "Capital", "Interior", etc.)
hom_doloso                  -- Homicidio doloso (inteiro absoluto)
lesao_corp_morte            -- Lesao corporal seguida de morte (inteiro)
latrocinio                  -- Latrocinio (inteiro)
cvli                        -- Crimes Violentos Letais Intencionais (inteiro, agregado)
hom_por_interv_policial     -- Homicidio por intervencao policial (inteiro)
letalidade_violenta         -- Letalidade violenta (inteiro, agregado)
tentat_hom                  -- Tentativa de homicidio (inteiro)
lesao_corp_dolosa           -- Lesao corporal dolosa (inteiro)
estupro                     -- Estupro (inteiro)
hom_culposo                 -- Homicidio culposo (inteiro)
lesao_corp_culposa          -- Lesao corporal culposa (inteiro)
roubo_transeunte            -- Roubo a transeunte (inteiro)
roubo_celular               -- Roubo de celular (inteiro)
roubo_em_coletivo           -- Roubo em coletivo (inteiro)
roubo_rua                   -- Roubo de rua (inteiro, agregado)
roubo_carga                 -- Roubo de carga (inteiro)
roubo_comercio              -- Roubo a comercio (inteiro)
roubo_residencia            -- Roubo a residencia (inteiro)
roubo_banco                 -- Roubo a banco (inteiro)
roubo_cx_eletronico         -- Roubo a caixa eletronico (inteiro)
roubo_conducao_saque        -- Roubo com conducao para saque (inteiro)
roubo_apos_saque            -- Roubo apos saque (inteiro)
roubo_bicicleta             -- Roubo de bicicleta (inteiro)
outros_roubos               -- Outros roubos (inteiro)
total_roubos                -- Total de roubos (inteiro, agregado)
furto_veiculos              -- Furto de veiculos (inteiro)
furto_transeunte            -- Furto a transeunte (inteiro)
furto_coletivo              -- Furto em coletivo (inteiro)
furto_celular               -- Furto de celular (inteiro)
furto_bicicleta             -- Furto de bicicleta (inteiro)
outros_furtos               -- Outros furtos (inteiro)
total_furtos                -- Total de furtos (inteiro, agregado)
sequestro                   -- Sequestro (inteiro)
extorsao                    -- Extorsao (inteiro)
sequestro_relampago         -- Sequestro relampago (inteiro)
estelionato                 -- Estelionato (inteiro)
apreensao_drogas            -- Apreensao de drogas (inteiro)
posse_drogas                -- Posse de drogas (inteiro)
trafico_drogas              -- Trafico de drogas (inteiro)
apreensao_drogas_sem_autor  -- Apreensao de drogas sem autor (inteiro)
recuperacao_veiculos        -- Recuperacao de veiculos (inteiro)
apf                         -- Auto de prisao em flagrante (inteiro)
aaapai                      -- Autos de apreensao de adolescentes (inteiro)
cmp                         -- Cumprimento de mandado de prisao (inteiro)
cmba                        -- Cumprimento de mandado de busca e apreensao (inteiro)
ameaca                      -- Ameaca (inteiro)
pessoas_desaparecidas       -- Pessoas desaparecidas (inteiro)
encontro_cadaver            -- Encontro de cadaver (inteiro)
encontro_ossada             -- Encontro de ossada (inteiro)
pol_militares_mortos_serv   -- Policiais militares mortos em servico (inteiro)
pol_civis_mortos_serv       -- Policiais civis mortos em servico (inteiro)
registro_ocorrencias        -- Total de registros de ocorrencia (inteiro)
fase                        -- Fase dos dados (int: 1=preliminar, 2=parcial, 3=consolidado)
```

### 3.3 Amostra de Dados

```csv
fmun_cod;fmun;ano;mes;mes_ano;regiao;hom_doloso;lesao_corp_morte;latrocinio;cvli;...;fase
3300100;"Angra dos Reis";2014;1;"01/2014";"Interior";5;0;1;6;...;3
3300100;"Angra dos Reis";2014;2;"02/2014";"Interior";3;0;0;3;...;3
```

### 3.4 Mapeamento CSV -> Modelo de Dados

**Mapeamento**: O CSV tem ~55 colunas de categorias de crime, mas a tabela `monthly_stats` da API usa um modelo normalizado (EAV -- Entity-Attribute-Value):

```
monthly_stats: municipality_id, year, month, category_id, category_value
```

Cada linha do CSV precisa ser "pivotada": uma linha do CSV com ~55 categorias gera **ate 55 registros** em `monthly_stats`.

**Decisao**: O `ItemProcessor` e responsavel por:
1. Usar `fmun_cod` diretamente como `municipality_id` (FK em `municipalities` — confirmado que usa codigo IBGE como PK)
2. Para cada coluna de crime, resolver o nome da categoria -> `category_id` (FK em `categories`)
3. Converter valor String para int (valores absolutos, sem decimais)
4. Usar campo `mes` do CSV diretamente como `month` (1-12)
5. Gerar N objetos `MonthlyStat` (um por categoria) a partir de 1 linha CSV
6. Ignorar valores zerados ou nulos (opcional, decisao de negocio)

---

## 4. Estrutura de Pacotes

```
src/main/java/br/com/roubometro/
├── config/
│   ├── BatchJobConfig.java              -- Definicao do Job e Steps
│   ├── StepConfig.java                  -- Configuracao detalhada de cada Step
│   ├── DataSourceConfig.java            -- HikariCP, SSL, profiles
│   └── AppProperties.java              -- @ConfigurationProperties (portal URL, paths, chunk-size)
├── domain/
│   ├── model/
│   │   ├── CsvEstatisticaRow.java       -- DTO: 1 linha do CSV (62 campos)
│   │   ├── MonthlyStat.java             -- Entity JPA: mapeia monthly_stats
│   │   ├── Category.java                -- Entity JPA: mapeia categories (read-only + insert if missing)
│   │   ├── Municipality.java            -- Entity JPA: mapeia municipalities (read-only)
│   │   └── BatchFileMetadata.java       -- Entity JPA: batch_file_metadata
│   │   (NOTA: batch_municipality_ibge_mapping REMOVIDA — FK direta municipalities.id = IBGE code)
│   └── exception/
│       ├── RoubometroException.java     -- Base exception
│       ├── PortalAccessException.java   -- Falha ao acessar portal
│       ├── CsvParsingException.java     -- CSV malformado
│       ├── FileDownloadException.java   -- Falha no download
│       └── DataIntegrityException.java  -- FK nao encontrada, etc.
├── application/
│   ├── step/
│   │   ├── DataAcquisitionTasklet.java  -- Step 1: scraping + download
│   │   └── FinalizationTasklet.java     -- Step 3: relatorio + limpeza
│   ├── processor/
│   │   └── EstatisticaItemProcessor.java -- TODA logica de negocio
│   └── service/
│       ├── PortalScraperService.java    -- Extrai link de download do portal
│       ├── FileDownloadService.java     -- Baixa arquivo, calcula hash
│       ├── FileMetadataService.java     -- Controle de versao do arquivo
│       ├── CategoryLookupService.java   -- Cache de categories (nome -> id)
│       └── MunicipalityLookupService.java -- Cache de municipalities.id (valida existencia do codigo IBGE)
├── infrastructure/
│   ├── reader/
│   │   └── CsvItemReaderConfig.java     -- FlatFileItemReader configurado
│   ├── writer/
│   │   └── MonthlyStatItemWriterConfig.java -- JdbcBatchItemWriter com INSERT IGNORE
│   ├── repository/
│   │   ├── MonthlyStatRepository.java   -- JpaRepository para monthly_stats
│   │   ├── CategoryRepository.java      -- JpaRepository para categories
│   │   ├── MunicipalityRepository.java  -- JpaRepository para municipalities
│   │   └── BatchFileMetadataRepository.java -- JpaRepository para batch_file_metadata
│   └── client/
│       └── PortalHttpClient.java        -- HTTP client para ispdados.rj.gov.br
└── RoubometroBatchApplication.java

src/main/resources/
├── application.yml                      -- Config comum
├── application-local.yml                -- Config dev local (Docker MySQL)
├── application-prod.yml                 -- Config producao (hospedagem externa + SSL)
├── db/migration/
│   └── V1__create_batch_tables.sql      -- Flyway: apenas tabelas do batch
└── META-INF/
    └── additional-spring-configuration-metadata.json

src/test/java/br/com/roubometro/
├── application/
│   ├── processor/
│   │   └── EstatisticaItemProcessorTest.java
│   └── step/
│       ├── DataAcquisitionTaskletTest.java
│       └── FinalizationTaskletTest.java
├── infrastructure/
│   ├── reader/
│   │   └── CsvItemReaderTest.java
│   └── writer/
│       └── MonthlyStatItemWriterTest.java
├── integration/
│   └── JobIntegrationTest.java          -- Testcontainers MySQL
└── service/
    ├── PortalScraperServiceTest.java    -- WireMock
    └── FileDownloadServiceTest.java     -- WireMock

src/test/resources/
├── fixtures/
│   ├── sample.csv                       -- 5-10 linhas validas do CSV real
│   ├── sample_empty.csv                 -- Apenas header
│   ├── sample_malformed.csv             -- Linhas com erros
│   └── portal_page.html                 -- HTML mock do portal
└── application-test.yml
```

---

## 5. Modelo de Dados (DDL)

### 5.1 Flyway Migration V1 -- Tabelas exclusivas do batch

```sql
-- V1__create_batch_tables.sql
-- Roubometro Batch: tabelas de controle exclusivas do batch.
-- IMPORTANTE: as tabelas monthly_stats, categories, municipalities, etc.
-- sao gerenciadas pelo Knex (roubometro-back). O batch NAO cria nem altera essas tabelas.

-- Controle de versao dos arquivos baixados do portal
CREATE TABLE IF NOT EXISTS batch_file_metadata (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    file_name       VARCHAR(255)    NOT NULL,
    file_url        VARCHAR(1024)   NOT NULL,
    file_hash       VARCHAR(64)     NOT NULL COMMENT 'SHA-256 do conteudo do arquivo',
    file_size_bytes BIGINT          NULL,
    downloaded_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed       TINYINT(1)      NOT NULL DEFAULT 0,
    processed_at    DATETIME        NULL,
    row_count       INT             NULL COMMENT 'Numero de linhas no CSV (excluindo header)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Relatorio de execucao do job (complementa as tabelas BATCH_* do Spring)
CREATE TABLE IF NOT EXISTS batch_job_execution_report (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    job_execution_id    BIGINT      NOT NULL COMMENT 'FK logica para BATCH_JOB_EXECUTION',
    file_metadata_id    BIGINT      NULL,
    status              VARCHAR(20) NOT NULL COMMENT 'COMPLETED, FAILED, SKIPPED',
    rows_read           INT         NOT NULL DEFAULT 0,
    rows_written        INT         NOT NULL DEFAULT 0,
    rows_skipped        INT         NOT NULL DEFAULT 0,
    rows_errors         INT         NOT NULL DEFAULT 0,
    duration_ms         BIGINT      NULL,
    error_message       TEXT        NULL,
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_execution_id (job_execution_id),
    CONSTRAINT fk_report_file_metadata
        FOREIGN KEY (file_metadata_id)
        REFERENCES batch_file_metadata (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 5.2 Mapeamento do batch para tabelas existentes (Knex)

O batch **insere dados** em `monthly_stats` usando `ON DUPLICATE KEY UPDATE`. A constraint UNIQUE `uk_mun_date_category (municipality_id, year, month, category_id)` **ja existe** na tabela (criada pelo `initial_schema.sql` do roubometro-back). Nenhuma alteracao necessaria.

**Resolucao de FKs**:
- `municipality_id`: usa `fmun_cod` do CSV diretamente (confirmado que `municipalities.id` = codigo IBGE)
- `category_id`: lookup via `CategoryLookupService` (cache em memoria)
- `year` e `month`: campos `ano` e `mes` do CSV, diretos

### 5.3 Insercao idempotente em monthly_stats

```sql
INSERT INTO monthly_stats (municipality_id, year, month, category_id, category_value, source_file, created_at)
VALUES (?, ?, ?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
    category_value = VALUES(category_value),
    source_file = VALUES(source_file);
```

### 5.4 Insercao de categorias novas

```sql
INSERT IGNORE INTO categories (name) VALUES (?);
```

Apos o INSERT IGNORE, o batch faz SELECT para obter o `id` (ja existente ou recem-criado). Esse lookup e cacheado em memoria por `CategoryLookupService` durante a execucao do job.

### 5.5 Mapeamento municipio_cod (IBGE) -> municipality_id

**RESOLVIDO**: Confirmado que `municipalities.id` = codigo IBGE do municipio. O campo `fmun_cod` do CSV pode ser usado diretamente como FK para `municipalities.id`, sem necessidade de tabela de mapeamento.

Validacao no `ItemProcessor`: verificar se `fmun_cod` existe em `municipalities` (cache em memoria via `MunicipalityLookupService`). Se nao existir, loga warning e faz skip da linha.

### 5.6 Tabelas Spring Batch (auto-criadas)

Configuracao no `application.yml`:

```yaml
spring:
  batch:
    jdbc:
      initialize-schema: always
      table-prefix: BATCH_
```

Isso cria automaticamente: `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_EXECUTION_CONTEXT`.

---

## 6. Especificacao dos Steps

### 6.1 Step 1: dataAcquisitionStep (Tasklet)

**Tipo**: Tasklet (nao chunk-oriented)

**Input**:
- URL do portal ISP-RJ: `https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv`
- Ultimo hash registrado em `batch_file_metadata`

**Output**:
- Arquivo CSV baixado em diretorio temporario local (ou path configuravel)
- Registro em `batch_file_metadata` com hash SHA-256 do arquivo baixado
- Flag `newFileAvailable` no `ExecutionContext` do Step para decisao no Step 2

**Comportamento em sucesso**:
1. `PortalHttpClient` faz HEAD request para obter `Content-Length` e/ou `ETag`/`Last-Modified`
2. Se nao houver indicadores de mudanca confiaveis, faz GET e calcula SHA-256
3. `FileMetadataService` compara hash com ultimo registro em `batch_file_metadata`
4. Se hash diferente (arquivo novo):
   - Salva arquivo em disco
   - Insere registro em `batch_file_metadata` (processed=false)
   - Seta `newFileAvailable=true` no `ExecutionContext`
5. Se hash igual (arquivo identico):
   - Seta `newFileAvailable=false` no `ExecutionContext`
   - Job pode continuar para Step 3 (finalizacao) pulando Step 2, ou encerrar

**Comportamento em falha**:
- Portal inacessivel: `PortalAccessException` com retry (3 tentativas, backoff exponencial)
- Timeout de download: `FileDownloadException` com retry
- Apos esgotar retries: Step falha, job marcado como FAILED, proximo agendamento retenta
- Nenhum dado parcial e persistido em caso de falha

**Dependencias**:
- `PortalHttpClient` (HTTP client com timeout e retry)
- `FileDownloadService` (download + calculo de hash)
- `FileMetadataService` (consulta e persiste em `batch_file_metadata`)

**Diagrama de sequencia**:
```
DataAcquisitionTasklet
    |
    +--> PortalHttpClient.head(url)          -- verifica disponibilidade
    |         |
    |         +--> HTTP HEAD -> 200 OK
    |
    +--> FileDownloadService.download(url)   -- baixa arquivo
    |         |
    |         +--> HTTP GET -> salva em /tmp/roubometro/
    |         +--> calcula SHA-256 do arquivo
    |
    +--> FileMetadataService.isNewFile(hash) -- compara com ultimo hash
    |         |
    |         +--> SELECT FROM batch_file_metadata ORDER BY downloaded_at DESC LIMIT 1
    |
    +--> [se novo] FileMetadataService.register(metadata)
    |         |
    |         +--> INSERT INTO batch_file_metadata
    |
    +--> ExecutionContext.put("newFileAvailable", true/false)
    +--> ExecutionContext.put("csvFilePath", "/tmp/roubometro/BaseMunicipioMensal.csv")
    +--> ExecutionContext.put("fileMetadataId", 42)
```

---

### 6.2 Step 2: dataProcessingStep (Chunk-oriented)

**Tipo**: Chunk `<CsvEstatisticaRow, List<MonthlyStat>>`

**Nota sobre tipagem**: cada linha do CSV (`CsvEstatisticaRow`) gera N registros `MonthlyStat` (um por categoria). O `ItemProcessor` retorna uma `List<MonthlyStat>`. Para lidar com a expansao 1:N, usar um `ItemProcessor` que retorna `List` e um `ItemWriter` adaptado (ou usar `ClassifierCompositeItemWriter`). Alternativa: usar `ItemProcessor<CsvEstatisticaRow, List<MonthlyStat>>` com `ItemListUnpacker` delegando ao writer.

**Condicao de execucao**: Step 2 so executa se `newFileAvailable=true` no `ExecutionContext` do Step 1. Implementado via `StepExecutionListener.beforeStep()` ou `@StepScope` com `@Value("#{jobExecutionContext['newFileAvailable']}")`.

**Input**:
- Arquivo CSV em `csvFilePath` (do ExecutionContext)
- Delimitador: `;`
- Encoding: ISO-8859-1 (configuravel)
- Skip header: sim (primeira linha)

**ItemReader** (`FlatFileItemReader<CsvEstatisticaRow>`):
- Le o CSV linha a linha
- Mapeia para `CsvEstatisticaRow` (DTO com 57 campos String)
- NAO faz validacao, NAO filtra, NAO converte tipos
- Configuracao: `DelimitedLineTokenizer` com delimiter `;`, `BeanWrapperFieldSetMapper`

**ItemProcessor** (`EstatisticaItemProcessor`):
- Recebe `CsvEstatisticaRow`, retorna `List<MonthlyStat>` (ou `null` para skip)
- Logica:
  1. Valida campos obrigatorios (`fmun_cod`, `ano`, `mes` nao podem ser nulos/vazios)
  2. Usa `fmun_cod` diretamente como `municipality_id` (FK para `municipalities.id` = codigo IBGE)
     - Valida existencia via `MunicipalityLookupService` (cache em memoria)
     - Se municipio nao encontrado: loga warning, retorna `null` (skip)
  3. Para cada coluna de crime (~55 colunas):
     a. Resolve nome da coluna -> `category_id` via `CategoryLookupService`
     b. Converte valor String para int (valores absolutos inteiros)
     c. Se valor = 0 ou vazio: decide se inclui ou pula (configuravel)
     d. Cria objeto `MonthlyStat` com: municipality_id=fmun_cod, year=ano, month=mes, category_id, category_value, source_file
  4. Retorna lista de `MonthlyStat`

**ItemWriter** (`JdbcBatchItemWriter` ou custom):
- Recebe `List<List<MonthlyStat>>` (lista de chunks, cada chunk e lista de stats)
- Faz flatten da lista
- Executa batch INSERT com `ON DUPLICATE KEY UPDATE`:

```sql
INSERT INTO monthly_stats (municipality_id, year, month, category_id, category_value, source_file, created_at)
VALUES (?, ?, ?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
    category_value = VALUES(category_value),
    source_file = VALUES(source_file)
```

**Chunk size**: 50 linhas CSV (cada linha gera ~55 registros, total ~2750 INSERTs por chunk). Ver ADR-001.

**Comportamento em sucesso**:
- Cada chunk commitado independentemente
- Contadores atualizados: read, write, skip
- Ao final, marca `batch_file_metadata.processed = true`

**Comportamento em falha**:
- Linha malformada: `CsvParsingException`, skip policy aplica (max 100 skips)
- FK nao encontrada (municipio): skip com log de warning
- Erro de banco: retry policy (3 retries por chunk)
- Apos esgotar skips ou retries: Step falha
- Job e restartavel: Spring Batch registra ultimo chunk commitado, restart continua de onde parou

**Dependencias**:
- `CategoryLookupService` (cache em memoria, carregado no inicio do Step)
- `MunicipalityLookupService` (cache em memoria dos IDs existentes em `municipalities`)
- `MonthlyStatRepository` (ou `JdbcTemplate` direto para INSERT IGNORE)

---

### 6.3 Step 3: finalizationStep (Tasklet)

**Tipo**: Tasklet

**Input**:
- `StepExecution` do Step 2 (contadores)
- `fileMetadataId` do ExecutionContext

**Output**:
- Registro em `batch_job_execution_report`
- Log estruturado com resumo da execucao

**Comportamento em sucesso**:
1. Coleta metricas do `StepExecution` do Step 2 (readCount, writeCount, skipCount)
2. Calcula duracao total do job
3. Insere registro em `batch_job_execution_report`
4. Loga resumo:
   ```
   Job roubometroDataSyncJob completed. Status=COMPLETED,
   file=BaseMunicipioMensal.csv, rowsRead=12144, rowsWritten=667920,
   rowsSkipped=0, duration=120000ms
   ```
5. Limpa arquivo CSV temporario (se configurado)

**Comportamento em falha**:
- Se Step 3 falha, o job e marcado como FAILED, mas os dados do Step 2 ja foram commitados (chunk por chunk). Isso e aceitavel -- os dados estao corretos, so o relatorio nao foi gerado.
- Retry nao e necessario neste step (e puramente informativo).

**Dependencias**:
- `BatchFileMetadataRepository`
- `ExecutionContext` (dados dos steps anteriores)

---

## 7. ADRs -- Decisoes Tecnicas

### ADR-001: Chunk Size Inicial

**Contexto**: O CSV tem ~92 municipios x ~11 anos x 12 meses = ~12.144 linhas. Cada linha gera ~55 registros em `monthly_stats`. Total estimado: ~667.920 registros por execucao completa.

**Opcoes**:
| Opcao | Chunk size (linhas CSV) | INSERTs por chunk | Pros | Contras |
|-------|------------------------|--------------------|------|---------|
| A | 10 | ~530 | Baixo uso de memoria, commits frequentes | Muitos round-trips ao banco |
| B | 50 | ~2750 | Equilibrio memoria/round-trips | - |
| C | 200 | ~11000 | Poucos round-trips | Transacoes longas, lock contention |

**Decisao**: **Opcao B (50 linhas CSV por chunk)**. Equilibra memoria, numero de transacoes e latencia de rede (AWS <-> hospedagem). Com ~12.144 linhas totais (mensal), sao ~243 chunks -- aceitavel.

**Nota**: sera refinado pelo `performance-optimizer` com dados reais.

---

### ADR-002: Estrategia de Download (Jsoup vs Playwright vs Python)

**Contexto**: O portal dados.gov.br/ISP-RJ precisa ser acessado para baixar o CSV. Nao ha API publica.

**Investigacao realizada**: O CSV esta hospedado em URL direta (`https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv`), acessivel via GET HTTP simples. A pagina `https://www.ispdados.rj.gov.br/estatistica.html` lista os links de download em HTML estatico. Testado e confirmado: URL retorna CSV com 62 colunas, delimitador `;`.

**Opcoes**:
| Opcao | Tecnologia | Cenario |
|-------|-----------|---------|
| A | URL direta + HTTP client Java | Link e estavel e acessivel via GET |
| B | Jsoup (scraping HTML) | Link muda e precisa ser extraido do HTML |
| C | Playwright/Selenium | Site exige JS rendering |
| D | Script Python | Ultimo recurso |

**Decisao**: **Opcao A (URL direta) com fallback para Opcao B (Jsoup)**.

**Justificativa**:
1. O CSV esta em URL direta e previsivel. Nao precisa de scraping para o caso comum.
2. A URL e configuravel via `application.yml`. Se mudar, basta atualizar a config.
3. Se um dia a URL deixar de ser direta, o `PortalScraperService` pode usar Jsoup para extrair o link da pagina HTML (fallback).
4. Jsoup e uma dependencia leve (jar ~400KB), sem overhead de browser.
5. Playwright/Selenium sao pesados e desnecessarios -- a pagina do ISP e HTML estatico.

**Riscos**:
- Se o ISP mudar a URL sem aviso, o Step 1 falha. Mitigacao: alerta + fallback Jsoup.
- Se o ISP adicionar captcha/JS obrigatorio, sera necessario migrar para Playwright.

---

### ADR-003: Estrategia de Deduplicacao

**Contexto**: O batch deve ser idempotente. Rodar N vezes com o mesmo arquivo deve produzir o mesmo resultado. A latencia AWS <-> hospedagem torna SELECTs individuais inaceitaveis.

**Opcoes**:
| Opcao | Estrategia | Round-trips | Complexidade |
|-------|-----------|-------------|-------------|
| A | SELECT antes de cada INSERT | N (1 por registro) | Baixa |
| B | `INSERT IGNORE` | 1 por batch | Baixa |
| C | `ON DUPLICATE KEY UPDATE` | 1 por batch | Baixa |
| D | Tabela staging + MERGE | 2 (insert staging + merge) | Alta |

**Decisao**: **Opcao C (`ON DUPLICATE KEY UPDATE`)**.

**Justificativa**:
1. Zero round-trips extras -- o upsert e atomico no MySQL.
2. Se o registro ja existe, atualiza `category_value` e `source_file` (dados podem ser corrigidos pelo ISP).
3. Depende de UNIQUE KEY em `monthly_stats(municipality_id, year, month, category_id)`.
4. `INSERT IGNORE` seria suficiente se os dados nunca mudassem, mas o ISP pode corrigir valores retroativamente (campo `fase` indica isso: 1=preliminar, 2=parcial, 3=consolidado).

**Pre-requisito**: UNIQUE KEY na tabela `monthly_stats`. Ver secao 5.2.

---

### ADR-004: Estrategia de Conexao com Banco Remoto

**Contexto**: O banco MySQL de producao esta em hospedagem externa (tipo Locaweb). O batch roda na AWS. Conexao via internet publica.

**Decisoes**:

| Aspecto | Decisao | Justificativa |
|---------|---------|---------------|
| Pool size | HikariCP `maximumPoolSize=3` | Hospedagem com limite conservador (10-30 conexoes). API tambem consome. |
| `minimumIdle` | 1 | Manter 1 conexao viva para reduzir latencia do primeiro chunk |
| `connectionTimeout` | 10000ms (10s) | Latencia de rede AWS<->hospedagem pode ser alta |
| `maxLifetime` | 1800000ms (30min) | Evitar conexoes recicladas durante execucao longa |
| `idleTimeout` | 600000ms (10min) | Liberar conexoes ociosas para a API usar |
| SSL | `useSSL=true&requireSSL=true&verifyServerCertificate=false` | Obrigatorio em prod. `verifyServerCertificate=false` porque hospedagens baratas usam certificados self-signed |
| Retry | Spring Retry com 3 tentativas, backoff exponencial (1s, 2s, 4s) | Conexao pode cair por instabilidade da hospedagem |
| Keepalive | `spring.datasource.hikari.keepaliveTime=300000` (5min) | Evitar que firewall da hospedagem mate conexoes idle |

**Configuracao HikariCP**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 3
      minimum-idle: 1
      connection-timeout: 10000
      max-lifetime: 1800000
      idle-timeout: 600000
      keepalive-time: 300000
      connection-test-query: SELECT 1
```

---

### ADR-005: Skip/Retry Policy

**Contexto**: O CSV pode ter linhas malformadas, municipios nao mapeados ou erros transientes de banco.

**Skip Policy**:
| Excecao | Skip? | Limite | Justificativa |
|---------|-------|--------|---------------|
| `CsvParsingException` | Sim | 100 | Linha malformada nao deve travar o job |
| `DataIntegrityException` | Sim | 100 | Municipio nao mapeado = skip com log |
| `NumberFormatException` | Sim | 100 | Valor numerico invalido |
| Qualquer outra `Exception` | Nao | - | Erro inesperado = falha imediata |

**Retry Policy**:
| Excecao | Retry? | Tentativas | Backoff |
|---------|--------|------------|---------|
| `DeadlockLoserDataAccessException` | Sim | 3 | 1s fixo |
| `TransientDataAccessException` | Sim | 3 | Exponencial (1s, 2s, 4s) |
| `CannotAcquireLockException` | Sim | 3 | 2s fixo |

**Implementacao**:
```java
.<CsvEstatisticaRow, List<MonthlyStat>>chunk(50)
    .reader(csvReader)
    .processor(processor)
    .writer(writer)
    .faultTolerant()
    .skipLimit(100)
    .skip(CsvParsingException.class)
    .skip(DataIntegrityException.class)
    .skip(NumberFormatException.class)
    .retryLimit(3)
    .retry(DeadlockLoserDataAccessException.class)
    .retry(TransientDataAccessException.class)
```

---

### ADR-006: Agendamento

**Contexto**: O batch deve rodar periodicamente para capturar atualizacoes do ISP-RJ.

**Opcoes**:
| Opcao | Tecnologia | Custo | Complexidade |
|-------|-----------|-------|-------------|
| A | Cron local (Spring `@Scheduled`) | Zero | Requer instancia rodando 24/7 |
| B | AWS EventBridge + ECS Task | Baixo | Paga apenas por execucao |
| C | AWS EventBridge + Lambda | Muito baixo | Limite de 15min, cold start |

**Decisao**: **Opcao B (EventBridge + ECS Task)** para producao. **Opcao A (cron local)** para dev.

**Justificativa**:
- O ISP atualiza dados mensalmente (ou menos). Uma execucao diaria as 03:00 UTC e suficiente.
- ECS Task: sobe container, executa job, desliga. Sem custo ocioso.
- Lambda tem limite de 15min, que pode ser insuficiente com latencia de rede.
- Em dev local, `@Scheduled` com cron expression configuravel.

**Schedule**: `cron(0 3 * * ? *)` -- diariamente as 03:00 UTC (00:00 BRT).

---

### ADR-007: Ownership de Tabelas Compartilhadas (monthly_stats e categories)

**Contexto**: As tabelas `monthly_stats` e `categories` foram criadas pelo Knex (roubometro-back) e sao lidas pela API. O batch precisa inserir dados nessas tabelas.

**Problema**:
1. Quem e o "owner" da DDL dessas tabelas?
2. O batch pode adicionar constraints (UNIQUE KEY) a tabelas do Knex?
3. O batch pode inserir em `categories`?

**Decisao**:

| Aspecto | Decisao | Justificativa |
|---------|---------|---------------|
| DDL ownership | **Knex (API)** continua owner da DDL | Principio: quem criou, mantem |
| Unique key em monthly_stats | **Ja existe** (`uk_mun_date_category`) no initial_schema.sql | Nenhuma acao necessaria |
| INSERT em monthly_stats | **Permitido** -- batch e o unico produtor de dados nessa tabela | API so le; nao ha conflito de escrita |
| INSERT em categories | **Permitido via INSERT IGNORE** -- batch adiciona categorias novas se necessario | Idem: API so le categories |

**Risco residual**: Se o roubometro-back fizer um migration que altera `monthly_stats` de forma incompativel (ex: remove coluna), o batch quebra. Mitigacao: testes de integracao que validam o schema antes de executar.

**Nota**: UNIQUE KEY ja existe. CSV mensal tem campo `mes` (1-12). Nenhuma acao pendente com o roubometro-back para V1.

---

### ADR-008: Restart/Recovery

**Contexto**: O job pode falhar no meio da execucao (rede, banco, etc.).

**Estrategia**:
1. **Spring Batch job repository** persiste o estado de cada Step e chunk no MySQL (tabelas `BATCH_*`).
2. **Step 1 (Tasklet)**: idempotente por design -- re-download + re-calculo de hash. Se falhar, restart re-executa do inicio.
3. **Step 2 (Chunk)**: restartavel. Spring Batch registra ultimo chunk commitado. Restart continua do proximo chunk. `ON DUPLICATE KEY UPDATE` garante que re-processar chunks ja commitados nao causa duplicatas.
4. **Step 3 (Tasklet)**: idempotente -- se falhar, restart re-gera o relatorio.

**Configuracao**:
```java
@Bean
public Job roubometroDataSyncJob() {
    return new JobBuilder("roubometroDataSyncJob", jobRepository)
        .start(dataAcquisitionStep)
        .next(decider)  // verifica newFileAvailable
        .on("PROCESS").to(dataProcessingStep)
        .from(decider).on("SKIP").to(finalizationStep)
        .from(dataProcessingStep).next(finalizationStep)
        .build();
}
```

**allowStartIfComplete**: Step 1 = true (sempre re-verifica o portal). Steps 2 e 3 = false (padrao Spring Batch -- so re-executa se FAILED).

---

### ADR-009: Formato de Logs e Metricas

**Contexto**: Observabilidade e requisito. O batch roda na AWS (CloudWatch).

**Formato de logs**: JSON estruturado (Logback + logstash-encoder).

```json
{
  "timestamp": "2026-02-23T03:00:15.123Z",
  "level": "INFO",
  "logger": "br.com.roubometro.application.step.DataAcquisitionTasklet",
  "message": "File downloaded successfully",
  "jobExecutionId": 42,
  "stepName": "dataAcquisitionStep",
  "fileName": "BaseMunicipioMensal.csv",
  "fileHash": "abc123...",
  "fileSizeBytes": 524288,
  "durationMs": 3200
}
```

**Metricas**:
| Metrica | Tipo | Onde |
|---------|------|------|
| `job.duration.ms` | Timer | FinalizationTasklet |
| `step.read.count` | Counter | Spring Batch built-in |
| `step.write.count` | Counter | Spring Batch built-in |
| `step.skip.count` | Counter | Spring Batch built-in |
| `file.download.duration.ms` | Timer | DataAcquisitionTasklet |
| `file.is_new` | Gauge (0/1) | DataAcquisitionTasklet |
| `db.connection.pool.active` | Gauge | HikariCP (via Actuator) |

**Stack de observabilidade**:
- Logback com `logstash-logback-encoder` para JSON
- Spring Boot Actuator para metricas JMX/Prometheus-ready
- CloudWatch Logs para centralizacao em producao

---

## 8. Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: roubometro-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: roubometro
      MYSQL_USER: roubometro
      MYSQL_PASSWORD: roubometro
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./docker/init-db:/docker-entrypoint-initdb.d
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --default-authentication-plugin=mysql_native_password
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-proot"]
      interval: 10s
      timeout: 5s
      retries: 5

  batch:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: roubometro-batch
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: local
      DB_HOST: mysql
      DB_PORT: 3306
      DB_NAME: roubometro
      DB_USER: roubometro
      DB_PASSWORD: roubometro
      PORTAL_CSV_URL: https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv
    volumes:
      - ./data:/app/data

volumes:
  mysql_data:
    driver: local
```

**Nota sobre init-db**: O diretorio `docker/init-db/` pode conter scripts SQL para criar as tabelas da API (simular o schema do Knex) no ambiente local. Isso permite que o batch funcione localmente sem rodar o roubometro-back.

---

## 9. Configuracao de Profiles

### 9.1 application.yml (comum)

```yaml
spring:
  application:
    name: roubometro-batch
  batch:
    jdbc:
      initialize-schema: always
      table-prefix: BATCH_
    job:
      enabled: false  # nao executar automaticamente ao subir

  jpa:
    hibernate:
      ddl-auto: none  # Flyway gerencia DDL
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    table: flyway_schema_history_batch  # separar do Knex
    out-of-order: false

roubometro:
  portal:
    csv-url: https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv
    scraping-url: https://www.ispdados.rj.gov.br/estatistica.html
    connect-timeout-ms: 10000
    read-timeout-ms: 30000
    retry-attempts: 3
    retry-backoff-ms: 2000
  batch:
    chunk-size: 50
    skip-limit: 100
    temp-dir: /tmp/roubometro
    csv-encoding: ISO-8859-1
    csv-delimiter: ";"
    include-zero-values: false
  schedule:
    cron: "0 0 3 * * *"  # 03:00 UTC diariamente
```

### 9.2 application-local.yml

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:roubometro}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo&characterEncoding=UTF-8
    username: ${DB_USER:roubometro}
    password: ${DB_PASSWORD:roubometro}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2
      connection-timeout: 5000
      max-lifetime: 1800000
      idle-timeout: 600000

logging:
  level:
    br.com.roubometro: DEBUG
    org.springframework.batch: DEBUG
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

### 9.3 application-prod.yml

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT:3306}/${DB_NAME}?useSSL=true&requireSSL=true&verifyServerCertificate=false&serverTimezone=America/Sao_Paulo&characterEncoding=UTF-8
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 3
      minimum-idle: 1
      connection-timeout: 10000
      max-lifetime: 1800000
      idle-timeout: 600000
      keepalive-time: 300000
      connection-test-query: SELECT 1

logging:
  level:
    br.com.roubometro: INFO
    org.springframework.batch: INFO
    root: WARN
  pattern:
    # JSON via logstash-logback-encoder (configurado no logback-spring.xml)
    console: ""

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

---

## 10. Registro de Riscos e Premissas

### 10.1 Riscos Atualizados

| # | Risco | Prob. | Impacto | Mitigacao | Status |
|---|-------|-------|---------|-----------|--------|
| R1 | Portal ISP-RJ muda URL do CSV | Media | Alto | URL configuravel; fallback Jsoup para scraping | Aberto |
| R2 | CSV muda formato/colunas | Baixa | Alto | Validacao de header no reader; alerta; skip policy | Aberto |
| R3 | Portal fora do ar | Baixa | Medio | Retry com backoff (3x); alerta; proximo agendamento retenta | Aberto |
| R4 | CSV muito grande (memoria) | Baixa | Medio | Chunk-oriented; nunca carrega tudo em memoria | Mitigado por design |
| R5 | Conflito de migrations Knex vs Flyway | Media | Alto | Flyway usa tabela separada `flyway_schema_history_batch`; batch so cria tabelas `batch_*` | Mitigado por design |
| R6 | Batch corrompe dados que a API usa | Baixa | Critico | Batch so faz INSERT/UPDATE em monthly_stats e categories; nunca DELETE; testes de integracao | Mitigado por design |
| R7 | Lock contention no MySQL compartilhado | Media | Medio | Batch roda as 03:00 UTC; transacoes curtas por chunk; pool max 3 | Mitigado por design |
| R8 | Estouro de conexoes na hospedagem | Media | Alto | Pool max 3 (batch) + monitoramento via Actuator | Aberto |
| R9 | Latencia AWS <-> hospedagem | Alta | Medio | INSERT batch (ON DUPLICATE KEY UPDATE); chunks de 50; minimizar round-trips | Mitigado por design |
| R10 | IP do batch bloqueado pela hospedagem | Baixa | Alto | Elastic IP fixo na AWS; liberar no painel da hospedagem | Aberto |
| R11 | Hospedagem faz manutencao sem aviso | Baixa | Medio | Retry policy; Spring Batch restart; janela de execucao flexivel | Mitigado por design |
| R12 | Tabela monthly_stats sem UNIQUE KEY | ~~Alta~~ | ~~Alto~~ | ~~RESOLVIDO~~: UK `uk_mun_date_category` ja existe no initial_schema.sql | **Resolvido** |
| R13 | Mapeamento IBGE <-> municipality_id | ~~Media~~ | ~~Alto~~ | ~~RESOLVIDO~~: municipalities.id = codigo IBGE. FK direta, sem tabela de mapeamento | **Resolvido** |
| R14 | Encoding do CSV muda (ISO-8859-1 vs UTF-8) | Baixa | Medio | Encoding configuravel; deteccao automatica como melhoria futura | Aberto |
| R15 | Campo `month` = 0 nao e aceito pela API | ~~Media~~ | ~~Medio~~ | ~~RESOLVIDO~~: CSV mensal tem campo `mes` (1-12). Sem necessidade de convencao month=0 | **Resolvido** |

### 10.2 Premissas Atualizadas

| # | Premissa | Validada? | Observacao |
|---|----------|-----------|------------|
| P1 | CSV acessivel via GET direto (sem captcha, sem login) | **Sim** | Testado: URL `https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv` retorna CSV |
| P2 | Formato do CSV e estavel (mesmas colunas desde 2014) | Parcial | CSV atual tem 57 colunas. Precisa monitorar mudancas |
| P3 | Banco MySQL compartilhado com API | Sim | Confirmado pelo prompt e varredura |
| P4 | Banco em hospedagem externa | Sim | Confirmado pelo prompt |
| P5 | Hospedagem permite conexoes externas MySQL | Nao validada | Depende do provedor real |
| P6 | SSL disponivel na hospedagem | Nao validada | Depende do provedor real |
| P7 | Limite de conexoes da hospedagem: 10-30 | Nao validada | Premissa conservadora |
| P8 | API ja le de monthly_stats e categories | Sim | Confirmado pela varredura do roubometro-back |
| P9 | Delimitador do CSV e ponto-e-virgula | **Sim** | Confirmado no CSV real |
| P10 | Valores decimais usam virgula (padrao brasileiro) | **Sim** | Confirmado: `"40,55"` |
| P11 | ~92 municipios x ~11 anos = ~1012 linhas no CSV | Estimativa | Precisa confirmar com CSV completo |
| P12 | Campo `fase` indica qualidade do dado (1=preliminar, 3=consolidado) | Provavel | Inferido da amostra (todos `fase=3` nos exemplos) |

### 10.3 Acoes Pendentes

| # | Acao | Responsavel | Prioridade | Status |
|---|------|-------------|------------|--------|
| A1 | ~~Confirmar UNIQUE KEY em monthly_stats~~ | - | - | **Resolvido** (ja existe `uk_mun_date_category`) |
| A2 | ~~Criar seed para batch_municipality_ibge_mapping~~ | - | - | **Resolvido** (FK direta, municipalities.id = IBGE code) |
| A3 | ~~Alinhar month=0 com roubometro-back~~ | - | - | **Resolvido** (CSV mensal tem campo `mes` 1-12) |
| A4 | Validar que hospedagem aceita conexoes externas com SSL | Dono do produto / infra | Alta | Aberto |
| A5 | Definir Elastic IP na AWS e liberar no painel da hospedagem | Infra | Media | Aberto |

---

## Apendice A: Diagrama de Sequencia do Job Completo

```
┌─────────┐  ┌──────────────────┐  ┌────────────────┐  ┌──────────────┐  ┌────────────┐
│ Schedule │  │ DataAcquisition  │  │ DataProcessing │  │ Finalization │  │   MySQL    │
│ Trigger  │  │   Tasklet        │  │   Chunk Step   │  │   Tasklet    │  │            │
└────┬─────┘  └────────┬─────────┘  └───────┬────────┘  └──────┬───────┘  └─────┬──────┘
     │                 │                     │                  │                │
     │  launch job     │                     │                  │                │
     │────────────────>│                     │                  │                │
     │                 │                     │                  │                │
     │                 │  HTTP GET csv       │                  │                │
     │                 │─────────────────────────────────────────────────────>  ISP-RJ
     │                 │  <── csv file ──────────────────────────────────────   portal
     │                 │                     │                  │                │
     │                 │  SHA-256(file)      │                  │                │
     │                 │  compare hash       │                  │                │
     │                 │─────────────────────────────────────────────────────>│
     │                 │  SELECT hash FROM batch_file_metadata  │                │
     │                 │<────────────────────────────────────────────────────│
     │                 │                     │                  │                │
     │                 │  [new file]         │                  │                │
     │                 │  INSERT INTO batch_file_metadata       │                │
     │                 │─────────────────────────────────────────────────────>│
     │                 │                     │                  │                │
     │                 │  set context:       │                  │                │
     │                 │  newFileAvailable   │                  │                │
     │                 │  csvFilePath        │                  │                │
     │                 │─────────────────────>                  │                │
     │                 │                     │                  │                │
     │                 │                     │  [for each chunk]│                │
     │                 │                     │                  │                │
     │                 │                     │  read 50 CSV rows│                │
     │                 │                     │  (FlatFileReader) │                │
     │                 │                     │                  │                │
     │                 │                     │  process:        │                │
     │                 │                     │  resolve FK      │                │
     │                 │                     │  pivot 1:N       │                │
     │                 │                     │  validate        │                │
     │                 │                     │                  │                │
     │                 │                     │  INSERT ... ON DUPLICATE KEY UPDATE
     │                 │                     │─────────────────────────────────>│
     │                 │                     │  <── affected rows ─────────────│
     │                 │                     │                  │                │
     │                 │                     │  [repeat chunks] │                │
     │                 │                     │                  │                │
     │                 │                     │──────────────────>                │
     │                 │                     │  step complete   │                │
     │                 │                     │                  │                │
     │                 │                     │                  │  collect metrics│
     │                 │                     │                  │  write report   │
     │                 │                     │                  │───────────────>│
     │                 │                     │                  │  INSERT report  │
     │                 │                     │                  │<──────────────│
     │                 │                     │                  │                │
     │                 │                     │                  │  cleanup temp  │
     │                 │                     │                  │  log summary   │
     │                 │                     │                  │                │
     │  job complete   │                     │                  │                │
     │<────────────────────────────────────────────────────────│                │
```

---

## Apendice B: Mapeamento Completo CSV -> categories

As 53 colunas de crime do CSV devem ser mapeadas para registros na tabela `categories`. O batch fara `INSERT IGNORE INTO categories (name) VALUES (?)` para cada uma no inicio do Step 2 (pre-load via `CategoryLookupService`).

| Coluna CSV | Nome da categoria (categories.name) |
|------------|--------------------------------------|
| hom_doloso | Homicidio doloso |
| lesao_corp_morte | Lesao corporal seguida de morte |
| latrocinio | Latrocinio |
| cvli | CVLI |
| hom_por_interv_policial | Homicidio por intervencao policial |
| letalidade_violenta | Letalidade violenta |
| tentat_hom | Tentativa de homicidio |
| lesao_corp_dolosa | Lesao corporal dolosa |
| estupro | Estupro |
| hom_culposo | Homicidio culposo |
| lesao_corp_culposa | Lesao corporal culposa |
| roubo_transeunte | Roubo a transeunte |
| roubo_celular | Roubo de celular |
| roubo_em_coletivo | Roubo em coletivo |
| roubo_rua | Roubo de rua |
| roubo_carga | Roubo de carga |
| roubo_comercio | Roubo a comercio |
| roubo_residencia | Roubo a residencia |
| roubo_banco | Roubo a banco |
| roubo_cx_eletronico | Roubo a caixa eletronico |
| roubo_conducao_saque | Roubo com conducao para saque |
| roubo_apos_saque | Roubo apos saque |
| roubo_bicicleta | Roubo de bicicleta |
| outros_roubos | Outros roubos |
| total_roubos | Total de roubos |
| furto_veiculos | Furto de veiculos |
| furto_transeunte | Furto a transeunte |
| furto_coletivo | Furto em coletivo |
| furto_celular | Furto de celular |
| furto_bicicleta | Furto de bicicleta |
| outros_furtos | Outros furtos |
| total_furtos | Total de furtos |
| sequestro | Sequestro |
| extorsao | Extorsao |
| sequestro_relampago | Sequestro relampago |
| estelionato | Estelionato |
| apreensao_drogas | Apreensao de drogas |
| posse_drogas | Posse de drogas |
| trafico_drogas | Trafico de drogas |
| apreensao_drogas_sem_autor | Apreensao de drogas sem autor |
| recuperacao_veiculos | Recuperacao de veiculos |
| apf | Auto de prisao em flagrante |
| aaapai | Autos de apreensao de adolescentes |
| cmp | Cumprimento de mandado de prisao |
| cmba | Cumprimento de mandado de busca e apreensao |
| ameaca | Ameaca |
| pessoas_desaparecidas | Pessoas desaparecidas |
| encontro_cadaver | Encontro de cadaver |
| encontro_ossada | Encontro de ossada |
| pol_militares_mortos_serv | Policiais militares mortos em servico |
| pol_civis_mortos_serv | Policiais civis mortos em servico |
| registro_ocorrencias | Total de registros de ocorrencia |

**Nota**: A coluna `fase` NAO e uma categoria -- e metadado da qualidade do dado. Sera armazenada como campo adicional ou ignorada na V1.

---

## Apendice C: Compatibilidade do campo `month`

**RESOLVIDO**: Usando `BaseMunicipioMensal.csv`, o campo `mes` do CSV (1-12) mapeia diretamente para `monthly_stats.month`. Nao ha necessidade de convencao `month=0` para dados anuais.

**Evolucao futura**: Se houver necessidade de dados anuais agregados (taxas per 100k), pode-se:
1. Processar `BaseMunicipioTaxaAno.csv` em tabela separada (ex: `yearly_stats_rate`)
2. Ou calcular agregados anuais via query na API a partir dos dados mensais

---

> **Proximo passo**: Submeter este documento para revisao do dono do produto. Resolver acoes bloqueantes (A1-A5) antes de iniciar implementacao. Ativar `security-auditor` para revisao de seguranca.
