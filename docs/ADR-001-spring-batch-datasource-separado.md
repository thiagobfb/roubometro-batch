# ADR-001: Separar tabelas do Spring Batch em banco de dados dedicado

> **Status**: Proposta (em analise)
> **Data**: 2026-02-24
> **Decisores**: Time roubometro
> **Escopo**: Infraestrutura de banco de dados do roubometro-batch

---

## Contexto

O projeto **roubometro-batch** compartilha o banco de dados MySQL `roubometro` com o **roubometro-back** (Node.js/Fastify). Atualmente, todas as tabelas coexistem no mesmo schema:

| Grupo | Tabelas | Owner | Qtde |
|-------|---------|-------|------|
| API (Knex) | `regions`, `states`, `municipalities`, `users`, `categories`, `monthly_stats`, `user_reports`, `refresh_tokens` | roubometro-back | 8 |
| Batch (Flyway) | `batch_file_metadata`, `batch_job_execution_report` | roubometro-batch | 2 |
| Spring Batch (auto) | `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_*_SEQ` (3 sequence tables) | Spring Batch framework | 9 |
| Flyway (controle) | `flyway_schema_history_batch` | Flyway | 1 |

**Total: 20 tabelas no mesmo schema**, sendo que 10 delas (50%) sao exclusivamente de infraestrutura do batch e nunca sao acessadas pela API.

### Motivacao

1. **Poluicao do schema compartilhado**: o dev do roubometro-back ve 12 tabelas de batch misturadas com as 8 tabelas da API ao inspecionar o banco.
2. **Risco operacional**: um `DROP DATABASE` ou restore de backup do banco da API afeta metadados do batch (e vice-versa).
3. **Pool de conexoes limitado**: o hosting externo (Locaweb) impoe limite baixo de conexoes. Com banco unico, batch e API competem pelo mesmo pool.
4. **Separacao de responsabilidades**: as tabelas `BATCH_*` sao internas do framework e nao tem relacao com o dominio de negocio.

---

## Opcoes Avaliadas

### Opcao A: Manter tudo no mesmo banco (status quo)

O batch continua usando o unico DataSource (`roubometro`) para tudo: metadados do Spring Batch, tabelas de controle do batch e escrita em `monthly_stats`.

**Configuracao atual**:
```yaml
spring:
  datasource:
    url: jdbc:mysql://host:3306/roubometro
  batch:
    jdbc:
      initialize-schema: always
```

| Vantagem | Desvantagem |
|----------|-------------|
| Simplicidade — zero configuracao extra | 20 tabelas misturadas no mesmo schema |
| Uma unica conexao para gerenciar | Batch e API competem pelo pool de conexoes |
| Transacoes ACID entre batch metadata e business data | Acoplamento: backup/restore afeta ambos |
| Sem custo adicional de infra | Dev do back-end precisa ignorar 12 tabelas que nao sao dele |

### Opcao B: Banco separado para tabelas do Spring Batch + batch_*

Criar um segundo database MySQL (ex: `roubometro_batch`) para armazenar:
- 9 tabelas `BATCH_*` (Spring Batch metadata)
- 2 tabelas `batch_file_metadata` e `batch_job_execution_report`
- `flyway_schema_history_batch`

O batch teria dois DataSources:
- `@BatchDataSource` → `roubometro_batch` (metadata do job)
- `@Primary` → `roubometro` (leitura de municipalities/categories, escrita em monthly_stats)

**Configuracao proposta**:
```yaml
spring:
  datasource:
    url: jdbc:mysql://host:3306/roubometro       # business data
  batch:
    datasource:
      url: jdbc:mysql://host:3306/roubometro_batch  # batch metadata
      username: ${DB_BATCH_USER:roubometro_batch}
      password: ${DB_BATCH_PASSWORD:roubometro_batch}
    jdbc:
      initialize-schema: always
```

**Codigo necessario**:
```java
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSource businessDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @BatchDataSource
    @ConfigurationProperties("spring.batch.datasource")
    public DataSource batchDataSource() {
        return DataSourceBuilder.create().build();
    }
}
```

| Vantagem | Desvantagem |
|----------|-------------|
| Schema limpo: API ve apenas suas 8 tabelas | **Duas conexoes abertas** — impacta hosting com limite baixo |
| Backup/restore independentes | Complexidade de configuracao (dois DataSources, dois pools) |
| Batch metadata pode ser descartado sem afetar dados de negocio | Flyway precisa ser configurado para o banco correto |
| Pools de conexao isolados | **Sem transacao ACID** entre metadata do batch e escrita em monthly_stats |
| Dev do back-end nao ve tabelas do batch | Docker Compose precisa de segundo database ou segundo container |
| | Testes de integracao mais complexos (Testcontainers com 2 databases) |

### Opcao C: Schema separado no mesmo servidor MySQL

Usar o mesmo servidor MySQL mas com databases diferentes: `roubometro` (negocio) e `roubometro_batch` (metadata). No MySQL, databases e schemas sao sinonimos — o efeito e o mesmo da Opcao B, mas sem necessidade de um segundo servidor.

A diferenca para a Opcao B e que ambos vivem no mesmo host, economizando custo de infra, mas os trade-offs de dual DataSource permanecem.

| Vantagem | Desvantagem |
|----------|-------------|
| Mesmas vantagens da Opcao B | Mesmas desvantagens da Opcao B |
| Sem custo de segundo servidor | Compartilha o mesmo limite de conexoes do servidor |
| Facil de criar: `CREATE DATABASE roubometro_batch` | |

### Opcao D: Batch metadata em RDS MySQL dedicado na AWS

Provisionar uma instancia RDS MySQL (ex: `db.t3.micro`) exclusivamente para as tabelas de infraestrutura do batch:
- 9 tabelas `BATCH_*` (Spring Batch metadata)
- 2 tabelas `batch_file_metadata` e `batch_job_execution_report`
- `flyway_schema_history_batch`

O banco de negocio (`roubometro`) permanece no hosting externo (Locaweb). O batch conecta em dois bancos:
- `@Primary` → Locaweb MySQL (`roubometro`) — leitura de municipalities/categories, escrita em monthly_stats
- `@BatchDataSource` → RDS MySQL (`roubometro_batch`) — metadata do job

**Configuracao proposta**:
```yaml
spring:
  datasource:
    url: jdbc:mysql://locaweb-host:3306/roubometro          # business data (Locaweb)
    hikari:
      maximum-pool-size: 3
  batch:
    datasource:
      url: jdbc:mysql://roubometro-batch.xxxxx.us-east-1.rds.amazonaws.com:3306/roubometro_batch
      username: ${DB_BATCH_USER}
      password: ${DB_BATCH_PASSWORD}
      hikari:
        maximum-pool-size: 3
    jdbc:
      initialize-schema: always
```

**Topologia**:
```
┌─────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│  ECS / EC2  │────→│  Locaweb MySQL       │     │  AWS RDS (t3.micro)  │
│  (batch)    │     │  roubometro          │     │  roubometro_batch    │
│             │────→│  monthly_stats       │     │  BATCH_*             │
│             │     │  categories          │     │  batch_file_metadata │
│             │     │  municipalities      │     │  batch_job_exec_rpt  │
│             │────→│                      │     │                      │
└─────────────┘     └──────────────────────┘     └──────────────────────┘
  @Primary DataSource                              @BatchDataSource
  (pool max=3)                                     (pool max=3)
```

**Custos estimados (RDS db.t3.micro, us-east-1)**:

| Item | Free Tier (12 meses) | Apos Free Tier |
|------|---------------------|----------------|
| Instancia (750h/mes) | $0.00 | ~$15-22/mes |
| Storage (20 GB gp2) | $0.00 | ~$2.30/mes |
| Backup (20 GB) | $0.00 | ~$1.90/mes |
| **Total** | **$0.00** | **~$19-26/mes** |

> Nota: Free Tier RDS elegivel para contas criadas antes de 15/jul/2025 (750h/mes por 12 meses). Contas mais novas recebem $100 em creditos.

**Capacidade do db.t3.micro**: 1 GB RAM, ~60 conexoes max. Para batch metadata (poucas escritas por chunk), e mais que suficiente.

| Vantagem | Desvantagem |
|----------|-------------|
| **Zero impacto no pool da Locaweb** — batch metadata nao consome conexoes do hosting externo | Custo mensal ~$19-26 apos Free Tier |
| Schema do Locaweb limpo: API ve apenas suas 8 tabelas | Latencia de rede entre ECS e RDS (~1-2ms, desprezivel) |
| Backup/restore independentes | Dois secrets de DB no deploy |
| RDS gerenciado: backup automatico, patching, Multi-AZ opcional | Complexidade de configuracao (dois DataSources) |
| ~60 conexoes disponiveis (sobra para o batch) | Flyway precisa apontar para o RDS |
| Batch na mesma VPC que o RDS = baixa latencia | **Sem transacao ACID** entre metadata e monthly_stats |
| Pode escalar (db.t3.small, db.t3.medium) se necessario | Novo recurso de infra para gerenciar (Terraform/CDK) |
| Se o batch roda em ECS na AWS, a latencia para o RDS e minima | Batch precisa acessar **dois** bancos em redes diferentes |

### Opcao E: Usar H2 em memoria para batch metadata (descartada)

As tabelas do Spring Batch (`BATCH_*`) sao operacionais e transientes. Usar um H2 in-memory para o `JobRepository` elimina completamente a poluicao no MySQL.

```yaml
spring:
  batch:
    datasource:
      url: jdbc:h2:mem:batchdb
      driver-class-name: org.h2.Driver
    jdbc:
      initialize-schema: always
```

| Vantagem | Desvantagem |
|----------|-------------|
| Zero tabelas de batch no MySQL | **Perde historico de execucoes** ao reiniciar a JVM |
| Sem competicao por conexoes MySQL | **Sem restart de jobs falhados** — metadata perdido |
| Performance de leitura/escrita de metadata excelente | Nao pode listar execucoes anteriores para auditoria |
| Simples de configurar | Incompativel com deploy ECS/Lambda (containers efemeros) |
| | Precisa adicionar dependencia do H2 |
| | `batch_file_metadata` e `batch_job_execution_report` continuam no MySQL (solucao parcial) |

---

## Analise de Impacto

### Conexoes (restricao critica)

O hosting externo (Locaweb) impoe limite baixo de conexoes simultaneas. Cenario atual:
- roubometro-back: pool Knex (~5-10 conexoes)
- roubometro-batch: HikariCP max=3 (prod)
- Total: ~8-13 conexoes no pico

Com Opcao B/C (dois pools no batch, mesmo servidor):
- Pool negocio: max=3
- Pool metadata: max=2
- Total batch: 5 conexoes (aumento de 2 na Locaweb)
- Total geral: ~10-15 conexoes na Locaweb

Com Opcao D (RDS separado):
- Pool negocio na Locaweb: max=3 (sem mudanca)
- Pool metadata no RDS: max=3 (servidor separado, ~60 conexoes disponiveis)
- Total na Locaweb: ~8-13 (sem impacto)

**Veredicto**: Opcoes B/C sao viaveis se o limite da Locaweb for >= 20, arriscadas se <= 15. **Opcao D elimina completamente o problema** — nao adiciona conexoes na Locaweb.

### Consistencia transacional

Cenario atual (banco unico): quando o `dataProcessingStep` escreve em `monthly_stats` e o Spring Batch atualiza `BATCH_STEP_EXECUTION` no commit do chunk, ambos participam da mesma transacao. Se o commit falha, ambos fazem rollback.

Com dois bancos: o commit em `monthly_stats` e o commit em `BATCH_STEP_EXECUTION` sao transacoes independentes. Cenario de falha possivel:
1. Chunk escreve 50 rows em `monthly_stats` → COMMIT OK
2. Spring Batch atualiza `BATCH_STEP_EXECUTION` → falha de rede → metadata nao atualizado
3. Proximo restart: batch re-processa o chunk (dados duplicados, mas UPSERT mitiga)

**Veredicto**: risco baixo — o UPSERT (`ON DUPLICATE KEY UPDATE`) garante idempotencia. O pior caso e reprocessar alguns chunks, nao corrompicao de dados.

### Complexidade de configuracao

| Aspecto | Banco unico | Banco separado |
|---------|-------------|----------------|
| application.yml | 1 datasource | 2 datasources |
| DataSource beans | auto-config | manual (`@Primary` + `@BatchDataSource`) |
| Flyway | 1 configuracao | 2 configuracoes (ou Flyway so no batch DB) |
| Docker Compose | 1 database | init script com `CREATE DATABASE roubometro_batch` |
| Testcontainers | 1 MySQL container | 1 container, 2 databases (ou 2 containers) |
| application-local.yml | 1 bloco | 2 blocos de conexao |
| Deploy (ECS/Lambda) | 1 secret de DB | 2 secrets de DB |

### Impacto no codigo existente

Arquivos que precisariam mudar:

| Arquivo | Mudanca |
|---------|---------|
| `DataSourceConfig.java` (novo) | Dois beans DataSource |
| `application.yml` | Segundo bloco datasource |
| `application-local.yml` | Segundo bloco datasource local |
| `MonthlyStatItemWriterConfig.java` | Garantir que usa `@Primary` DataSource |
| `docker-compose.yml` | Init script para segundo database |
| `AbstractBatchIntegrationTest.java` | Configurar segundo datasource nos testes |
| `V1__create_batch_tables.sql` | Mover para Flyway do segundo banco |

Estimativa: **~7 arquivos alterados, ~1-2 novos**.

---

## Recomendacao

### Curto prazo: Opcao A (status quo)

Manter tudo no mesmo banco enquanto o projeto estiver em fase inicial de desenvolvimento.

**Justificativa**:

1. **Simplicidade**: zero configuracao extra, um unico DataSource, testes simples.
2. **Convencao de prefixo**: `BATCH_` e `batch_` ja diferenciam visualmente as tabelas do framework.
3. **Transacao ACID**: atomicidade entre business data e batch metadata no commit do chunk.

### Medio prazo: Opcao D (RDS dedicado) quando o batch for para AWS

Quando o batch for deployado em ECS/Lambda na AWS, migrar o metadata para um RDS dedicado.

**Justificativa**:

1. **Zero impacto na Locaweb**: nao adiciona conexoes no hosting externo — resolve a restricao critica sem risco.
2. **Latencia minima**: batch (ECS) e RDS na mesma VPC = ~1-2ms, desprezivel para metadata de job.
3. **Custo aceitavel**: $0 nos primeiros 12 meses (Free Tier), ~$19-26/mes depois. Para um servico de batch que roda 1x/dia, e um custo operacional baixo.
4. **Gerenciado**: backup automatico, patching, monitoring via CloudWatch sem esforco adicional.
5. **Escopo limitado**: o RDS armazena apenas tabelas de infraestrutura (~12 tabelas, poucas centenas de rows). Nao exige instancia robusta.
6. **Idempotencia mitiga risco ACID**: o UPSERT (`ON DUPLICATE KEY UPDATE`) garante que chunks reprocessados nao corrompem dados. A perda de atomicidade entre os dois bancos e aceitavel.

**Pre-requisitos para migrar**:
- Batch deployado na AWS (ECS Fargate ou Lambda)
- VPC configurada com Security Groups permitindo acesso do batch ao RDS
- Terraform/CDK para provisionar o RDS (db.t3.micro, single-AZ, gp2 20GB)
- Secrets Manager para credenciais do RDS

### Analise de frequencia de execucao vs custo

O cron atual esta configurado para execucao **diaria as 3h** (`0 0 3 * * *`). Porem, o CSV do ISP-RJ e atualizado com frequencia **mensal** (dados mensais por municipio). Isso significa que em ~29 de 30 dias o batch detecta hash identico e pula o processamento. Se a frequencia for reduzida, o custo de um RDS 24/7 se torna desproporcional.

**Custo do RDS db.t3.micro vs frequencia de uso real**:

| Frequencia do batch | Tempo util/mes | RDS ligado 24/7 | Custo/execucao efetiva |
|---------------------|----------------|-----------------|----------------------|
| Diaria (atual) | ~30 execucoes | ~$22/mes | ~$0.73/execucao |
| Semanal | ~4 execucoes | ~$22/mes | ~$5.50/execucao |
| Quinzenal | ~2 execucoes | ~$22/mes | ~$11.00/execucao |
| Mensal | ~1 execucao | ~$22/mes | ~$22.00/execucao |

Para frequencias menores que diaria, existem estrategias de otimizacao de custo:

#### D.1: RDS com start/stop agendado

O RDS pode ser parado quando nao esta em uso. Enquanto parado, cobra apenas storage (~$2.30/mes), nao a instancia.

```
EventBridge (cron) → Lambda "start-rds" → aguarda available → ECS batch roda → Lambda "stop-rds"
```

**Restricao**: o RDS reinicia automaticamente apos **7 dias parado** (para aplicar patches). Para frequencias > semanal, e necessario um Step Function ou Lambda que re-pare a instancia apos o restart automatico.

| Frequencia | Custo estimado (instancia + storage) |
|------------|--------------------------------------|
| Semanal | ~$3.50/mes (6h ligado/mes + storage) |
| Quinzenal | ~$2.80/mes (3h ligado/mes + storage) |
| Mensal | ~$2.60/mes (1.5h ligado/mes + storage) |

> Nota: o batch leva ~1-2 min para processar ~12K rows. O RDS precisa de ~3-5 min para ficar `available` apos start. Tempo total por execucao: ~5-7 min.

**Complexidade adicional**: Lambda de orquestracao + EventBridge + IAM roles + Step Functions (se > semanal). Essa complexidade pode nao justificar a economia de ~$19/mes.

#### D.2: Aurora Serverless v2 (scale-to-zero)

Aurora Serverless v2 (MySQL 3.08.0+) pode escalar para **0 ACU** quando idle — custo de compute zero quando nao ha conexoes.

| Item | Custo idle (0 ACU) | Custo ativo (0.5 ACU) |
|------|-------------------|----------------------|
| Compute | $0.00/h | $0.06/h |
| Storage (I/O Optimized) | ~$0.20/GB/mes | ~$0.20/GB/mes |
| **Total mensal (1 exec/mes, 10 min ativo)** | **~$1.50/mes** | |
| **Total mensal (4 exec/mes, 40 min ativo)** | **~$1.70/mes** | |

| Vantagem | Desvantagem |
|----------|-------------|
| Custo near-zero quando idle | Cold start: ~15-30s para escalar de 0 para 0.5 ACU |
| Sem orquestracao start/stop | Custo minimo do cluster ($0.20/GB storage) mesmo parado |
| Escala automaticamente se necessario | Mais caro que RDS se rodar 24/7 (~$43/mes vs ~$22/mes) |
| Gerenciado, sem patches manuais | Nao elegivel ao Free Tier do RDS |
| Ideal para workloads esporadicos | Requer Aurora (nao e RDS vanilla) |

#### D.3: SQLite/H2 com EFS (persistente)

Para execucoes muito esporadicas, uma alternativa sem custo de DB gerenciado: usar um banco embedded (H2 ou SQLite) com arquivo persistido em EFS (Elastic File System).

```
ECS Task → monta EFS → H2 file mode (jdbc:h2:file:/efs/batchdb) → desmonta ao terminar
```

| Vantagem | Desvantagem |
|----------|-------------|
| Custo minimo (~$0.30/GB/mes no EFS) | Sem suporte nativo do Spring Batch a SQLite |
| Sem servidor de BD para gerenciar | H2 file mode nao e tao robusto quanto MySQL |
| Zero latencia (local ao container) | Nao escala para multiplas instancias do batch |
| | EFS tem latencia maior que EBS em escrita |

#### Recomendacao por frequencia

| Frequencia | Recomendacao | Custo estimado/mes |
|------------|-------------|-------------------|
| **Diaria** | **Opcao D (RDS db.t3.micro 24/7)** — custo aceitavel, simplicidade maxima | ~$22 (pos Free Tier) |
| **Semanal** | **Opcao D.1 (RDS com start/stop)** ou **D.2 (Aurora Serverless v2)** | ~$1.70-3.50 |
| **Quinzenal** | **Opcao D.2 (Aurora Serverless v2)** — sem orquestracao de start/stop | ~$1.50 |
| **Mensal** | **Opcao D.2 (Aurora Serverless v2)** ou **Opcao A (status quo)** — custo de separar pode nao justificar | ~$1.50 ou $0 |

> **Nota sobre frequencia ideal para o roubometro**:
>
> Segundo a [nota metodologica oficial do ISP-RJ](https://www.ispdados.rj.gov.br/metodDivulDados.html), os dados sao **divulgados mensalmente**, tipicamente entre o **13o e 28o dia do mes seguinte** ao mes de referencia (ex: dados de fevereiro/2025 publicados em 28/mar/2025). Nao ha calendario fixo de publicacao.
>
> Alem disso, o ISP publica **retificacoes trimestrais (erratas)** que alteram dados historicos retroativamente — o que reforça a importancia do `ON DUPLICATE KEY UPDATE` no batch.
>
> Uma frequencia **semanal** e suficiente para capturar atualizacoes sem atraso significativo (max ~7 dias). A frequencia diaria atual serve como safety net, mas a execucao real (com processamento de dados novos) ocorre ~1-2x/mes (publicacao mensal + eventuais erratas trimestrais).
>
> Fontes: [Decreto no. 36.872/2005](https://leisestaduais.com.br/rj/decreto-n-36872-2005-rio-de-janeiro), [Resolucao Seseg no. 1.278/2018](https://www.ispdados.rj.gov.br/metodDivulDados.html)

### Opcoes descartadas

| Opcao | Motivo |
|-------|--------|
| B (banco separado, mesmo host Locaweb) | Adiciona conexoes no hosting limitado sem resolver o problema central |
| C (schema separado, mesmo servidor) | Idem Opcao B — mesmo servidor, mesmo limite de conexoes |
| E (H2 in-memory) | Perde historico e restart de jobs; incompativel com containers efemeros |

### Comparativo final

| Criterio | A (status quo) | D (RDS dedicado) |
|----------|---------------|-------------------|
| Conexoes na Locaweb | 3 | 3 (sem mudanca) |
| Schema limpo na Locaweb | Nao | Sim |
| Transacao ACID | Sim | Nao (mitigado por UPSERT) |
| Custo adicional | $0 | $0 (Free Tier) / ~$22/mes |
| Complexidade de config | Baixa | Media |
| Backup independente | Nao | Sim |
| Escalabilidade | Limitada | Alta (pode subir instancia) |

---

## Referencias

- [Spring Boot — Using a Separate DataSource for Batch](https://docs.spring.io/spring-boot/how-to/batch.html)
- [Spring Batch — Configuring a JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html)
- [Configuring Dual Data Sources in Spring Batch 5 (Medium)](https://medium.com/@aryan.shinde.29/configuring-dual-data-sources-in-spring-batch-5-and-spring-boot-3-8a72bc00555c)
- [EnableBatchProcessing — Spring Batch 5.2 API](https://docs.spring.io/spring-batch/docs/current/api/org/springframework/batch/core/configuration/annotation/EnableBatchProcessing.html)
- [Amazon RDS for MySQL — Pricing](https://aws.amazon.com/rds/mysql/pricing/)
- [Amazon RDS Free Tier](https://aws.amazon.com/rds/free/)
- [AWS RDS Max Connections by Instance Type](https://sysadminxpert.com/aws-rds-max-connections-limit/)
- [Stopping an Amazon RDS DB Instance Temporarily](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html)
- [Schedule Amazon RDS Stop and Start Using AWS Systems Manager](https://aws.amazon.com/blogs/database/schedule-amazon-rds-stop-and-start-using-aws-systems-manager/)
- [Aurora Serverless v2 — Minimum Cost Setup](https://repost.aws/questions/QUbtHMLZXiS4Kppi7KMIB5YQ/aurora-serverless-v2-minimum-cost-setup-for-development-environment)
- [Amazon Aurora Pricing](https://aws.amazon.com/rds/aurora/pricing/)
