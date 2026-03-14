# Relatorio de Incidente — 2026-03-13

> **Tipo**: Falha na primeira execucao do batch em producao (Locaweb)
> **Severidade**: Media (dados parciais gravados, job travou mas nao corrompeu dados)
> **Status**: RESOLVIDO
> **Duracao do incidente**: ~4h (task ECS rodando sem progresso)
> **Duracao da resolucao**: ~11h (inclui reprocessamento completo)

---

## Resumo Executivo

A primeira execucao do roubometro-batch conectando ao MySQL da Locaweb (producao) travou durante o `dataProcessingStep`. O MySQL da Locaweb encerra conexoes ociosas apos um `wait_timeout` curto (~60-120s), e a configuracao do HikariCP estava com tempos muito longos (keepalive de 5min, max-lifetime de 30min). As conexoes morriam entre os keepalives, travando o processamento.

---

## Linha do Tempo

| Horario (UTC) | Evento |
|---------------|--------|
| 17:21 | Task ECS `df339313...` iniciada (primeira execucao com banco Locaweb) |
| 17:22:49 | Job `roubometroDataSyncJob` lancado |
| 17:22:56 | `dataAcquisitionStep` iniciado — CSV baixado com sucesso (2.3MB, hash `27238104...`) |
| 17:23:06 | `dataAcquisitionStep` concluido em 10s |
| 17:23:15 | `dataProcessingStep` iniciado |
| 17:23:34 | Processor criado, CSV reader configurado (62 colunas, ISO-8859-1) |
| 17:31:23 | **Primeiro WARN**: `HikariPool-1 - Failed to validate connection ... No operations allowed after connection closed.` |
| 17:31 - 18:26 | WARNs de conexao fechada a cada ~5min (housekeeper do HikariCP detectando conexoes mortas) |
| ~18:30 | Constatado que o step travou — **nenhum log de progresso** apos 17:23:34 |
| 18:34 | Task ECS parada manualmente (`aws ecs stop-task`) |
| 18:39 | Commit `4978e0c` com fix do HikariCP — push dispara pipeline CI/CD |
| 18:42 | Nova task ECS `180c6170...` lancada pelo pipeline |
| 18:43:47 | **ERRO**: `JobExecutionAlreadyRunningException` — execucao anterior com status STARTED no banco |
| 18:48 | Correcao manual: UPDATE na `BATCH_JOB_EXECUTION` (STATUS=STARTED -> FAILED) |
| 18:48 | DELETE do `batch_file_metadata` (id=1, processed=0) para forcar reprocessamento |
| 19:06 | Nova task ECS `24b82064...` lancada manualmente |
| 19:07:10 | Job lancado com sucesso |
| 19:07:22 | CSV baixado, novo metadata registrado (id=2) |
| 19:07:27 | `dataProcessingStep` iniciado — **desta vez sem erros de conexao** |
| 03:00:22 (+1d) | `dataProcessingStep` concluido: 12.441 linhas lidas, 12.139 escritas, 0 skips |
| 03:00:26 (+1d) | Job COMPLETED em 10h53min |

---

## Causa Raiz

### Configuracao HikariCP incompativel com `wait_timeout` da Locaweb

O MySQL da hospedagem externa (Locaweb) possui um `wait_timeout` curto (estimado 60-120 segundos). A configuracao do HikariCP estava:

| Parametro | Valor anterior | Problema |
|-----------|---------------|----------|
| `max-lifetime` | 1.800.000ms (30min) | Conexoes recicladas a cada 30min — muito maior que o wait_timeout |
| `idle-timeout` | 600.000ms (10min) | Conexoes ociosas mantidas por 10min — ja estariam mortas |
| `keepalive-time` | 300.000ms (5min) | Keepalive a cada 5min — conexao ja morta entre os pings |

O HikariCP housekeeper detectava as conexoes mortas (WARN a cada ~5min), mas a conexao usada pela thread principal no `dataProcessingStep` morreu durante uma transacao de chunk, travando o step sem erro explicito.

### Cascata de problemas

1. **Step travou** → job nunca atualizou STATUS para COMPLETED/FAILED na `BATCH_JOB_EXECUTION`
2. **Task ECS parada manualmente** → Spring Batch nao teve chance de fazer cleanup
3. **Segunda execucao** → `JobExecutionAlreadyRunningException` porque BATCH_JOB_EXECUTION.STATUS = STARTED
4. **Skip indevido** → `batch_file_metadata` tinha registro com `processed=0` mas mesmo hash, causando skip do processamento

---

## Correcoes Aplicadas

### 1. Ajuste do HikariCP (`application-prod.yml`)

**Commit**: `4978e0c`

```yaml
# ANTES
hikari:
  max-lifetime: 1800000      # 30 min
  idle-timeout: 600000       # 10 min
  keepalive-time: 300000     # 5 min

# DEPOIS
hikari:
  max-lifetime: 120000       # 2 min — recicla antes do wait_timeout
  idle-timeout: 60000        # 1 min — descarta ociosas rapido
  keepalive-time: 30000      # 30s — mantem conexoes vivas entre chunks
  leak-detection-threshold: 60000  # 1 min — debug (gera falso positivo)
```

### 2. Correcao manual do estado do Spring Batch

```sql
-- Marcar job travado como FAILED
UPDATE BATCH_JOB_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW(), VERSION = VERSION + 1
WHERE JOB_EXECUTION_ID = 1 AND STATUS = 'STARTED';

-- Marcar step travado como FAILED
UPDATE BATCH_STEP_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = NOW()
WHERE JOB_EXECUTION_ID = 1 AND STATUS = 'STARTED';
```

### 3. Reset do file metadata para forcar reprocessamento

```sql
DELETE FROM batch_file_metadata WHERE id = 1;
```

---

## Resultado do Reprocessamento

| Metrica | Valor |
|---------|-------|
| **Status** | COMPLETED |
| **Duracao** | 10h 52min 54s |
| **Linhas CSV lidas** | 12.441 |
| **Linhas escritas** | 12.139 |
| **Linhas com skip** | 0 |
| **Registros em `monthly_stats`** | 323.156 |
| **Anos cobertos** | 2014-2026 |
| **Municipios** | 82 |
| **Categorias** | 49 |

### Distribuicao por ano

| Ano | Registros |
|-----|-----------|
| 2014 | 25.884 |
| 2015 | 25.566 |
| 2016 | 27.224 |
| 2017 | 27.527 |
| 2018 | 28.352 |
| 2019 | 27.989 |
| 2020 | 25.899 |
| 2021 | 26.539 |
| 2022 | 26.482 |
| 2023 | 26.454 |
| 2024 | 26.264 |
| 2025 | 26.753 |
| 2026 | 2.223 |

### Idempotencia confirmada

Os 21.131 registros parciais de 2014 gravados pela execucao travada foram corretamente atualizados via `ON DUPLICATE KEY UPDATE` sem duplicacao. O mecanismo de dedup funcionou como esperado.

---

## Pendencias / Proximos Passos

1. **Performance**: 10h53min para 12k linhas e inaceitavel (meta <5min). A latencia AWS↔Locaweb (~100-300ms por query) domina. Tema para o `performance-optimizer` na Fase 3.
2. **`leak-detection-threshold`**: O valor de 60s gera falso positivo a cada chunk (Spring Batch mantem conexao aberta na transacao). Considerar remover ou aumentar para 300s.
3. **Resiliencia do skip logic**: O `batch_file_metadata` deveria considerar o flag `processed` na decisao de reprocessar, nao apenas o hash. Arquivo com mesmo hash e `processed=0` deveria ser reprocessado automaticamente.
