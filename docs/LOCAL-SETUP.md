# Guia de Execucao Local

Passo a passo para executar o **roubometro-batch** em ambiente de desenvolvimento local.

## Pre-requisitos

| Ferramenta | Versao minima | Verificacao |
|------------|---------------|-------------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker + Docker Compose | 20+ / v2 | `docker compose version` |
| IntelliJ IDEA (opcional) | 2024+ | - |

## 1. Subir o banco de dados MySQL

O projeto inclui um `docker-compose.yml` que provisiona um MySQL 8.0 com o schema completo.

```bash
docker compose up -d
```

Isso cria:
- Container `roubometro-mysql` na porta `3306` (bind apenas em `127.0.0.1`)
- Database `roubometro` com usuario `roubometro` / senha `roubometro`
- Execucao automatica dos scripts de inicializacao:
  - `docker/init-db/01_api_schema.sql` — tabelas do roubometro-back (regions, states, municipalities, categories, monthly_stats, etc.)
  - `docker/init-db/02_seed_data.sql` — dados seed (1 regiao, 1 estado, 10 municipios, 12 categorias)

Aguarde o healthcheck ficar saudavel:

```bash
docker compose ps
# STATUS deve ser "healthy"
```

### Resetar o banco (se necessario)

Se precisar recriar o banco do zero (ex: Flyway corrompido, tabelas inconsistentes):

```bash
docker compose down -v    # remove volumes (apaga todos os dados)
docker compose up -d      # recria tudo limpo
```

## 2. Verificar conexao com o banco

```bash
docker exec roubometro-mysql mysql -uroubometro -proubometro roubometro -e "SHOW TABLES;"
```

Resultado esperado apos o Docker init:

```
+----------------------+
| Tables_in_roubometro |
+----------------------+
| categories           |
| monthly_stats        |
| municipalities       |
| refresh_tokens       |
| regions              |
| states               |
| user_reports         |
| users                |
+----------------------+
```

> As tabelas `batch_file_metadata`, `batch_job_execution_report`, `BATCH_*` e `flyway_schema_history_batch` serao criadas automaticamente na primeira execucao da aplicacao (via Flyway e Spring Batch).

## 3. Configurar o profile `local`

O profile `local` (`application-local.yml`) ativa:
- Conexao com o MySQL local (localhost:3306)
- Execucao automatica do job ao iniciar (`spring.batch.job.enabled: true`)
- Logs em nivel DEBUG para `br.com.roubometro` e `org.springframework.batch`

### Via linha de comando (Maven)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Via IntelliJ IDEA

1. Abra **Run > Edit Configurations**
2. Na configuracao do Spring Boot (`RoubometroBatchApplication`):
   - Em **Active profiles**, digite: `local`
   - Ou em **VM options**, adicione: `-Dspring.profiles.active=local`
3. Clique em **Run**

### Via variavel de ambiente

```bash
export SPRING_PROFILES_ACTIVE=local
mvn spring-boot:run
```

> **Importante**: sem o profile `local`, a aplicacao falha ao iniciar com o erro `Failed to determine a suitable driver class` pois nao ha datasource configurado no profile default.

## 4. Primeira execucao

Na primeira execucao, a aplicacao:

1. **Flyway** cria as tabelas do batch:
   - `flyway_schema_history_batch` (controle de migracoes)
   - `batch_file_metadata` (rastreamento de downloads)
   - `batch_job_execution_report` (metricas do job)

2. **Spring Batch** cria as tabelas de metadados:
   - `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`
   - `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_EXECUTION_CONTEXT`

3. **Job `roubometroDataSyncJob`** executa automaticamente com o fluxo:

```
dataAcquisitionStep
   |-- Download do CSV do portal ISP-RJ
   |-- Calculo do hash SHA-256
   |-- Verificacao se o arquivo ja foi processado
   v
NewFileDecider
   |-- PROCESS (arquivo novo) --> dataProcessingStep
   |-- SKIP (arquivo ja processado) --> finalizationStep
   v
dataProcessingStep (se PROCESS)
   |-- Leitura do CSV (62 colunas, delimitador ";", encoding ISO-8859-1)
   |-- Processamento: cada linha CSV vira ~55 registros MonthlyStat
   |-- Escrita em batch no monthly_stats (UPSERT com ON DUPLICATE KEY UPDATE)
   v
finalizationStep
   |-- Registro do relatorio de execucao em batch_job_execution_report
```

### Log de sucesso esperado

```
DataAcquisitionTasklet started
Successfully connected to portal: url=https://www.ispdados.rj.gov.br/Arquivos/BaseMunicipioMensal.csv, status=200
File downloaded: path=..., size=~2.3MB, hash=...
New file detected, proceeding with processing
DataAcquisitionTasklet finished
NewFileDecider: newFileAvailable=true, decision=PROCESS
Executing step: [dataProcessingStep]
...
Step: [dataProcessingStep] executed in ~Xs
FinalizationTasklet started
Job roubometroDataSyncJob finalized. Status=COMPLETED, rowsRead=..., rowsWritten=...
Job: [FlowJob: [name=roubometroDataSyncJob]] completed with status: [COMPLETED]
```

## 5. Verificar os dados

Apos a execucao com sucesso, verifique se `monthly_stats` foi populada:

```bash
docker exec roubometro-mysql mysql -uroubometro -proubometro roubometro -e "
  SELECT COUNT(*) AS total_registros FROM monthly_stats;
"
```

Consulta mais detalhada:

```bash
docker exec roubometro-mysql mysql -uroubometro -proubometro roubometro -e "
  SELECT m.name AS municipio, ms.year, ms.month, c.name AS categoria, ms.category_value
  FROM monthly_stats ms
  JOIN municipalities m ON m.id = ms.municipality_id
  JOIN categories c ON c.id = ms.category_id
  WHERE m.name = 'Rio de Janeiro' AND ms.year = 2024 AND ms.month = 1
  LIMIT 10;
"
```

## 6. Re-execucao do job

### Cenario: CSV nao mudou

Se o CSV do portal ISP-RJ nao mudou desde a ultima execucao, o job detecta que o hash e o mesmo e **pula o processamento** (decision=SKIP). Isso e o comportamento esperado de idempotencia.

### Forcar reprocessamento

Para forcar o reprocessamento (ex: apos correcao de bug), limpe a tabela de metadados:

```bash
docker exec roubometro-mysql mysql -uroubometro -proubometro roubometro -e "
  DELETE FROM batch_file_metadata;
"
```

Em seguida, execute a aplicacao novamente.

### Limpar metadados do Spring Batch

Se o Spring Batch recusar re-executar o job (ex: `JobInstanceAlreadyCompleteException`), limpe as tabelas de controle:

```bash
docker exec roubometro-mysql mysql -uroubometro -proubometro roubometro -e "
  DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
  DELETE FROM BATCH_STEP_EXECUTION;
  DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
  DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
  DELETE FROM BATCH_JOB_EXECUTION;
  DELETE FROM BATCH_JOB_INSTANCE;
"
```

## 7. Executar testes

```bash
# Testes unitarios (22 testes, nao requer Docker)
mvn test

# Testes de integracao (13 testes, usa Testcontainers — requer Docker rodando)
mvn verify -Pintegration-tests
```

## Troubleshooting

### `Failed to determine a suitable driver class`

**Causa**: aplicacao iniciada sem o profile `local`.
**Solucao**: adicionar `-Dspring.profiles.active=local` ou configurar no IntelliJ.

### `Table 'roubometro.batch_file_metadata' doesn't exist`

**Causa**: Flyway nao executou a migracao V1 (geralmente por baseline incorreto).
**Solucao**: resetar o banco com `docker compose down -v && docker compose up -d`.

### `IncorrectTokenCountException: expected N actual M`

**Causa**: o CSV do portal ISP-RJ adicionou novas colunas que nao estao mapeadas.
**Solucao**: atualizar `CsvColumnNames.java`, `CsvEstatisticaRow.java` e `CategoryColumnMapping.java` com as novas colunas.

### `File unchanged (same hash), skipping processing`

**Causa**: o CSV ja foi processado anteriormente (hash identico em `batch_file_metadata`).
**Solucao**: se quiser forcar, limpe `batch_file_metadata` (ver secao 6).

### Job nao executa (fica idle e encerra)

**Causa**: `spring.batch.job.enabled=false` (default em producao).
**Solucao**: verificar que o profile `local` esta ativo (ele seta `enabled: true`).
