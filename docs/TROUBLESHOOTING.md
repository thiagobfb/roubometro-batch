# Troubleshooting — roubometro-batch

Guia de diagnostico e correcao de falhas do Spring Batch em producao.

---

## Indice

1. [Tabelas do Spring Batch](#tabelas-do-spring-batch)
2. [Diagnostico rapido](#diagnostico-rapido)
3. [Problema: Job com status STARTED (travado)](#problema-job-com-status-started-travado)
4. [Problema: JobExecutionAlreadyRunningException](#problema-jobexecutionalreadyrunningexception)
5. [Problema: Conexoes HikariCP morrendo](#problema-conexoes-hikaricp-morrendo)
6. [Problema: Job completou mas dados incompletos](#problema-job-completou-mas-dados-incompletos)
7. [Problema: Job marcado COMPLETED mas arquivo nao foi processado](#problema-job-marcado-completed-mas-arquivo-nao-foi-processado)
8. [Procedimento: Reset completo do Spring Batch](#procedimento-reset-completo-do-spring-batch)
9. [Referencia: Estrutura das tabelas BATCH_*](#referencia-estrutura-das-tabelas-batch)

---

## Tabelas do Spring Batch

O Spring Batch utiliza 9 tabelas para controle de execucao. **Nunca altere estas tabelas sem entender o impacto**.

```
BATCH_JOB_INSTANCE          → Uma entrada por combinacao job_name + job_parameters
BATCH_JOB_EXECUTION         → Uma entrada por tentativa de execucao (pode haver N por instance)
BATCH_JOB_EXECUTION_PARAMS  → Parametros passados ao job
BATCH_JOB_EXECUTION_CONTEXT → Contexto serializado do job (dados entre steps)
BATCH_STEP_EXECUTION        → Uma entrada por step executado
BATCH_STEP_EXECUTION_CONTEXT→ Contexto serializado do step (posicao do reader, etc)
BATCH_JOB_SEQ               → Sequencia para IDs de job
BATCH_JOB_EXECUTION_SEQ     → Sequencia para IDs de execution
BATCH_STEP_EXECUTION_SEQ    → Sequencia para IDs de step
```

Alem destas, o roubometro-batch usa a tabela `batch_file_metadata` para rastrear downloads de CSV.

---

## Diagnostico rapido

### Ver status das ultimas execucoes

```sql
SELECT
  je.JOB_EXECUTION_ID,
  ji.JOB_NAME,
  je.STATUS,
  je.EXIT_CODE,
  je.START_TIME,
  je.END_TIME,
  TIMESTAMPDIFF(SECOND, je.START_TIME, COALESCE(je.END_TIME, NOW())) AS duration_sec
FROM BATCH_JOB_EXECUTION je
JOIN BATCH_JOB_INSTANCE ji ON je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
ORDER BY je.JOB_EXECUTION_ID DESC
LIMIT 10;
```

### Ver steps de uma execucao especifica

```sql
SELECT
  STEP_EXECUTION_ID,
  STEP_NAME,
  STATUS,
  EXIT_CODE,
  READ_COUNT,
  WRITE_COUNT,
  COMMIT_COUNT,
  ROLLBACK_COUNT,
  START_TIME,
  END_TIME,
  EXIT_MESSAGE
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = <ID>
ORDER BY STEP_EXECUTION_ID;
```

### Ver se ha jobs "travados"

```sql
SELECT JOB_EXECUTION_ID, STATUS, START_TIME,
  TIMESTAMPDIFF(MINUTE, START_TIME, NOW()) AS minutes_running
FROM BATCH_JOB_EXECUTION
WHERE STATUS = 'STARTED' AND END_TIME IS NULL;
```

### Ver metadata de arquivos processados

```sql
SELECT * FROM batch_file_metadata ORDER BY id DESC LIMIT 5;
```

---

## Problema: Job com status STARTED (travado)

### Sintomas

- Task ECS parou (timeout, kill manual, OOM, erro de infra) mas o Spring Batch nao atualizou o status
- `BATCH_JOB_EXECUTION.STATUS = 'STARTED'` e `END_TIME = NULL`
- `BATCH_STEP_EXECUTION` tambem com `STATUS = 'STARTED'`

### Causa

O Spring Batch atualiza o status no banco **dentro da JVM**. Se o processo morre abruptamente (kill, OOM, timeout ECS), o status fica permanentemente como `STARTED`.

### Correcao

**Passo 1** — Identificar execucoes travadas:

```sql
SELECT JOB_EXECUTION_ID, STATUS, START_TIME
FROM BATCH_JOB_EXECUTION
WHERE STATUS = 'STARTED' AND END_TIME IS NULL
  AND TIMESTAMPDIFF(HOUR, START_TIME, NOW()) > 1;
```

**Passo 2** — Marcar a JOB EXECUTION como FAILED:

```sql
UPDATE BATCH_JOB_EXECUTION
SET STATUS = 'FAILED',
    EXIT_CODE = 'FAILED',
    EXIT_MESSAGE = 'Manually marked FAILED — process died without cleanup',
    END_TIME = NOW(),
    LAST_UPDATED = NOW(),
    VERSION = VERSION + 1
WHERE JOB_EXECUTION_ID = <ID>
  AND STATUS = 'STARTED';
```

**Passo 3** — Marcar os STEP EXECUTIONS como FAILED:

```sql
UPDATE BATCH_STEP_EXECUTION
SET STATUS = 'FAILED',
    EXIT_CODE = 'FAILED',
    EXIT_MESSAGE = 'Manually marked FAILED — parent job died',
    END_TIME = NOW(),
    LAST_UPDATED = NOW()
WHERE JOB_EXECUTION_ID = <ID>
  AND STATUS = 'STARTED';
```

**Passo 4** — Verificar o `batch_file_metadata`:

```sql
SELECT id, file_hash, processed, processed_at FROM batch_file_metadata
WHERE processed = 0;
```

Se houver registro com `processed = 0`, o arquivo foi baixado mas nao processado completamente. Delete o registro para forcar reprocessamento na proxima execucao:

```sql
DELETE FROM batch_file_metadata WHERE id = <ID> AND processed = 0;
```

**Passo 5** — Re-executar o job (via ECS ou pipeline).

> **IMPORTANTE**: O `VERSION = VERSION + 1` no UPDATE da `BATCH_JOB_EXECUTION` e obrigatorio. O Spring Batch usa optimistic locking e rejeita updates se a versao nao bater.

---

## Problema: JobExecutionAlreadyRunningException

### Sintomas

```
org.springframework.batch.core.repository.JobExecutionAlreadyRunningException:
A job execution for this job is already running: JobExecution: id=X, status=STARTED
```

### Causa

O Spring Batch verifica se existe uma `BATCH_JOB_EXECUTION` com `STATUS = STARTED` para o mesmo job antes de lancar uma nova execucao. Se existir, recusa iniciar.

### Correcao

Siga o procedimento da secao anterior: [Problema: Job com status STARTED (travado)](#problema-job-com-status-started-travado).

---

## Problema: Conexoes HikariCP morrendo

### Sintomas

Logs com warnings repetidos:
```
HikariPool-1 - Failed to validate connection ...
(No operations allowed after connection closed.)
Possibly consider using a shorter maxLifetime value.
```

### Causa

O MySQL da hospedagem externa (Locaweb) possui `wait_timeout` curto (60-120s). Se o HikariCP mantiver conexoes por mais tempo que o `wait_timeout`, o servidor MySQL fecha a conexao unilateralmente.

### Diagnostico

Verificar o `wait_timeout` do MySQL:

```sql
SHOW VARIABLES LIKE 'wait_timeout';
SHOW VARIABLES LIKE 'interactive_timeout';
```

### Correcao

Ajustar o `application-prod.yml` com valores menores que o `wait_timeout`:

```yaml
spring:
  datasource:
    hikari:
      max-lifetime: 120000       # 2 min (deve ser < wait_timeout do servidor)
      idle-timeout: 60000        # 1 min
      keepalive-time: 30000      # 30s (envia SELECT 1 para manter viva)
      connection-test-query: SELECT 1
```

**Regra geral**: `keepalive-time` < `wait_timeout` < `max-lifetime` (invertido no sentido logico: o keepalive deve executar ANTES do servidor matar a conexao, e o max-lifetime deve reciclar ANTES do servidor encerrar).

### Valores recomendados por cenario

| Cenario | `max-lifetime` | `idle-timeout` | `keepalive-time` |
|---------|---------------|----------------|-------------------|
| MySQL local (Docker) | 1.800.000 (30min) | 600.000 (10min) | — |
| Locaweb (wait_timeout ~120s) | 120.000 (2min) | 60.000 (1min) | 30.000 (30s) |
| RDS (wait_timeout ~28800s) | 600.000 (10min) | 300.000 (5min) | 60.000 (1min) |

---

## Problema: Job completou mas dados incompletos

### Sintomas

- `BATCH_JOB_EXECUTION.STATUS = COMPLETED` mas `monthly_stats` nao tem todos os anos esperados
- Ou: `WRITE_COUNT` no step e menor que o esperado

### Diagnostico

**Passo 1** — Verificar os contadores do step:

```sql
SELECT READ_COUNT, WRITE_COUNT, FILTER_COUNT, SKIP_COUNT, ROLLBACK_COUNT
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = <ID> AND STEP_NAME = 'dataProcessingStep';
```

- `READ_COUNT`: linhas lidas do CSV
- `WRITE_COUNT`: linhas que geraram inserts (excluindo filtrados)
- `FILTER_COUNT`: linhas descartadas pelo processor (retornou null)
- `SKIP_COUNT`: linhas com erro que foram puladas (dentro do skip-limit)
- `ROLLBACK_COUNT`: chunks que falharam e foram revertidos

**Passo 2** — Verificar distribuicao no banco:

```sql
SELECT year, COUNT(*) as registros
FROM monthly_stats
GROUP BY year
ORDER BY year;
```

**Passo 3** — Comparar com o CSV fonte:

```sql
-- Total esperado (aproximado): ~12.000 linhas CSV x ~49 categorias com valor > 0
SELECT COUNT(*) FROM monthly_stats;
```

### Correcao

Se os dados estao incompletos, force o reprocessamento:

```sql
-- Permite que o proximo job reprocesse o mesmo arquivo
DELETE FROM batch_file_metadata WHERE file_hash = '<hash_do_arquivo>';
```

O writer usa `ON DUPLICATE KEY UPDATE`, entao reprocessar e seguro — registros existentes sao atualizados, nao duplicados.

---

## Problema: Job marcado COMPLETED mas arquivo nao foi processado

### Sintomas

- Job completou mas pulou para `finalizationStep` sem executar `dataProcessingStep`
- Log mostra: `NewFileDecider: newFileAvailable=false, decision=SKIP`

### Causa

O `NewFileDecider` verificou que o arquivo ja existe no `batch_file_metadata` (mesmo hash) e decidiu pular o processamento. Isso pode acontecer se:

1. Uma execucao anterior baixou o arquivo e registrou o metadata, mas travou antes de processar
2. O `batch_file_metadata` tem `processed = 0` mas o decider so verifica a existencia do hash

### Correcao

```sql
-- Remover metadata incompleto para forcar reprocessamento
DELETE FROM batch_file_metadata WHERE processed = 0;
```

Depois re-execute o job.

---

## Procedimento: Reset completo do Spring Batch

> **CUIDADO**: Este procedimento apaga TODO o historico de execucoes. Use apenas em ultimo caso.

Caso o estado das tabelas esteja irrecuperavel (execucoes orfas, constraints violadas, etc):

```sql
-- Ordem importa (foreign keys)
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;

-- Resetar sequencias
UPDATE BATCH_JOB_SEQ SET ID = 0;
UPDATE BATCH_JOB_EXECUTION_SEQ SET ID = 0;
UPDATE BATCH_STEP_EXECUTION_SEQ SET ID = 0;

-- Resetar metadata de arquivos
DELETE FROM batch_file_metadata;
```

Apos o reset, o proximo job inicia como se fosse a primeira execucao.

> **NOTA**: Os dados em `monthly_stats` NAO sao afetados. O writer usa UPSERT, entao reprocessar apos reset e seguro.

---

## Referencia: Estrutura das tabelas BATCH_*

### BATCH_JOB_EXECUTION — Status possiveis

| Status | Significado |
|--------|-------------|
| `STARTING` | Job foi criado mas ainda nao iniciou |
| `STARTED` | Job esta em execucao |
| `STOPPING` | Job recebeu sinal de parada |
| `STOPPED` | Job foi parado (restartavel) |
| `COMPLETED` | Job terminou com sucesso |
| `FAILED` | Job terminou com erro |
| `ABANDONED` | Job foi abandonado (nao restartavel) |
| `UNKNOWN` | Status desconhecido |

### BATCH_STEP_EXECUTION — Contadores

| Campo | Descricao |
|-------|-----------|
| `READ_COUNT` | Itens lidos pelo reader |
| `WRITE_COUNT` | Itens escritos pelo writer |
| `FILTER_COUNT` | Itens filtrados pelo processor (retorno null) |
| `READ_SKIP_COUNT` | Erros de leitura tolerados (skip) |
| `WRITE_SKIP_COUNT` | Erros de escrita tolerados (skip) |
| `PROCESS_SKIP_COUNT` | Erros de processamento tolerados (skip) |
| `COMMIT_COUNT` | Chunks commitados com sucesso |
| `ROLLBACK_COUNT` | Chunks revertidos por erro |

### Relacao entre tabelas

```
BATCH_JOB_INSTANCE (1)
  └──→ BATCH_JOB_EXECUTION (N)          → uma por tentativa
         ├──→ BATCH_JOB_EXECUTION_PARAMS   → parametros do job
         ├──→ BATCH_JOB_EXECUTION_CONTEXT  → contexto serializado
         └──→ BATCH_STEP_EXECUTION (N)     → uma por step executado
                └──→ BATCH_STEP_EXECUTION_CONTEXT → posicao do reader, etc
```

### Chaves para investigacao

- **Job que nao inicia**: Verificar `BATCH_JOB_EXECUTION` por status `STARTED`
- **Step com poucos writes**: Verificar `FILTER_COUNT` e `SKIP_COUNT`
- **Job que roda mas nao grava dados**: Verificar `ROLLBACK_COUNT` > 0
- **Job restartavel**: Status `FAILED` permite restart. Status `COMPLETED` nao (precisaria novo JOB_INSTANCE)

---

## Comandos uteis AWS

### Ver logs da ultima execucao

```bash
# Listar log streams recentes
aws logs describe-log-streams \
  --log-group-name /ecs/roubometro-batch \
  --order-by LastEventTime --descending --limit 3 \
  --query 'logStreams[*].logStreamName' \
  --region us-east-1

# Ver logs de um stream especifico
aws logs get-log-events \
  --log-group-name /ecs/roubometro-batch \
  --log-stream-name "batch/roubometro-batch/<TASK_ID>" \
  --limit 50 --region us-east-1
```

### Verificar task ECS

```bash
# Listar tasks rodando
aws ecs list-tasks --cluster roubometro --desired-status RUNNING --region us-east-1

# Parar task travada
aws ecs stop-task --cluster roubometro --task <TASK_ID> \
  --reason "Manual stop - job stuck" --region us-east-1
```

### Lancar nova execucao manualmente

```bash
aws ecs run-task \
  --cluster roubometro \
  --task-definition roubometro-batch:2 \
  --launch-type FARGATE \
  --network-configuration '{
    "awsvpcConfiguration": {
      "subnets": ["<SUBNET_ID>"],
      "securityGroups": ["<SG_ID>"],
      "assignPublicIp": "ENABLED"
    }
  }' \
  --region us-east-1
```

### Conectar ao banco para diagnostico

```bash
mysql -h <DB_HOST> -P 3306 -u <DB_USER> -p<DB_PASSWORD> <DB_NAME> --ssl-mode=REQUIRED
```
