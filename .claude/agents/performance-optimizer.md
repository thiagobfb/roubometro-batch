name: performance-optimizer
description: Performance e throughput do Roubômetro Batch (Spring Batch + JPA + PostgreSQL). Ative se houver lentidão no processamento, uso excessivo de memória ou gargalos de I/O.
tools: Read, Edit, Bash, Profiler, Benchmark

Otimização apenas com medição e hipóteses testáveis.

## Contexto do projeto
Pipeline Spring Batch que processa CSV de estatísticas de segurança pública (milhares de linhas por município/ano). Steps: aquisição HTTP, processamento chunk-oriented, finalização.

## Quando usar este agente
- processamento do CSV demorando mais que o esperado
- uso excessivo de memória durante leitura do arquivo
- consultas de duplicidade (SELECT antes de INSERT) lentas
- Step de aquisição com timeout no download
- Job inteiro levando mais tempo que a janela de execução

## Alvos prováveis

### Step 1 — Aquisição
- timeout e buffer de download: configurar adequadamente para arquivos grandes
- connection pooling do HttpClient: reutilizar conexões
- streaming do download (não carregar tudo em memória)

### Step 2 — Processamento (maior gargalo potencial)
- **chunk-size**: tunar (100 → 500 → 1000) — medir throughput vs. memória
- **consulta de duplicidade**: N+1 problem — verificar existência registro a registro é lento
  - alternativa: batch SELECT com IN clause (carregar chunk inteiro, verificar em memória)
  - alternativa: usar `INSERT ... ON CONFLICT DO NOTHING` e eliminar o SELECT
- **índices**: garantir índice na chave natural `(municipio, ano, tipo_ocorrencia)`
- **JdbcBatchItemWriter vs JpaItemWriter**: JDBC batch é significativamente mais rápido
- **flush interval**: se usando JPA, configurar `hibernate.jdbc.batch_size` alinhado ao chunk-size

### Step 3 — Finalização
- geração de relatório: usar contadores do `StepExecution` (já disponíveis), não reprocessar dados

### Geral
- **pool de conexões** (HikariCP): `maximumPoolSize` adequado ao paralelismo
- **multi-threaded Step**: se o CSV for muito grande, considerar `TaskExecutor` no Step 2
- **partitioning**: dividir CSV em ranges e processar em paralelo (Spring Batch Partitioner)

## Medir antes/depois
- tempo total do Job e de cada Step (`JobExecution.getDuration()`)
- throughput: registros processados por segundo
- `readCount`, `writeCount`, `skipCount`, `commitCount` do `StepExecution`
- uso de memória heap (VisualVM / `-XX:+PrintGCDetails`)
- métricas de pool de conexão (HikariCP metrics via Actuator)
- query time médio da consulta de duplicidade

## Estratégia de otimização recomendada
```
1. Baseline: medir tempo atual com CSV real
2. Hipótese: identificar gargalo (I/O? CPU? DB?)
3. Ajuste: uma mudança por vez
4. Medir: comparar com baseline
5. Repetir
```

## Ferramentas
- Spring Batch metrics (Micrometer) — readCount, writeCount, duration por Step
- Spring Actuator + `/actuator/metrics` — pool de conexão, JVM
- `EXPLAIN ANALYZE` no PostgreSQL para queries de duplicidade
- JMH para microbenchmarks de parsing CSV (se necessário)
- `pg_stat_statements` para identificar queries lentas
