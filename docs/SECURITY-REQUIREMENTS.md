# Roubometro Batch -- Requisitos de Seguranca

> **Versao**: 1.1.0
> **Data**: 2026-02-23
> **Autor**: security-auditor agent
> **Status**: Revisao pre-implementacao
> **Documento base**: `docs/ARCHITECTURE.md` v1.1.0
> **Alteracoes v1.1.0**: Removida tabela batch_municipality_ibge_mapping (FK direta); CSV mensal com valores INT; ajustados GRANTs no Apendice A

---

## Sumario

1. [Findings (Problemas Encontrados no Design)](#1-findings)
2. [Checklist de Requisitos por Componente](#2-checklist-de-requisitos-por-componente)
3. [Dependencias e Versoes](#3-dependencias-e-versoes)
4. [Modelo de Ameacas (Threat Model)](#4-modelo-de-ameacas)
5. [Verificacao e Testes de Seguranca](#5-verificacao-e-testes-de-seguranca)

---

## 1. Findings

Problemas identificados nas decisoes arquiteturais do `ARCHITECTURE.md`. Ordenados por severidade.

### FINDING-01: verifyServerCertificate=false em producao

| Campo | Valor |
|-------|-------|
| **Severidade** | CRITICO |
| **Componente** | `application-prod.yml` (ADR-004) |
| **Descricao** | A connection string de producao usa `verifyServerCertificate=false`. Isso significa que o cliente MySQL aceita QUALQUER certificado apresentado pelo servidor, incluindo certificados forjados. Um atacante com acesso a rede entre AWS e a hospedagem (man-in-the-middle) pode interceptar credenciais e dados em transito. |
| **Decisao do architect** | "verifyServerCertificate=false porque hospedagens baratas usam certificados self-signed" |
| **Analise** | A justificativa e compreensivel, mas o risco e real. SSL sem verificacao de certificado protege apenas contra sniffing passivo, nao contra MITM ativo. A conexao trafega pela internet publica (AWS -> hospedagem), aumentando a superficie. |
| **Recomendacao** | **Opcao A (preferivel)**: Obter o certificado CA da hospedagem e configura-lo no truststore da JVM (`-Djavax.net.ssl.trustStore`). Usar `verifyServerCertificate=true`. **Opcao B (aceitavel como risco documentado)**: Manter `verifyServerCertificate=false` SE e SOMENTE SE: (1) a hospedagem confirmar que usa self-signed, (2) o risco for formalmente aceito pelo dono do produto, (3) o certificado self-signed for importado no truststore como mitigacao futura no roadmap. **Opcao C (minimo)**: Usar `verifyServerCertificate=false` + certificate pinning manual via propriedade `trustCertificateKeyStoreUrl` apontando para o cert exportado da hospedagem. |
| **Status** | **PENDENTE -- acao necessaria antes de ir para producao** |

### FINDING-02: Actuator endpoints expostos em producao

| Campo | Valor |
|-------|-------|
| **Severidade** | ALTO |
| **Componente** | `application-prod.yml` |
| **Descricao** | O profile de producao expoe `management.endpoints.web.exposure.include: health,info,metrics`. O endpoint `/actuator/metrics` pode vazar informacoes sobre pool de conexoes, contadores internos, e nomes de beans. O endpoint `/actuator/info` pode expor versao do app, git info, etc. Em um batch ECS sem ingress publico isso e menor risco, mas se por algum motivo o container expuser portas (debug, sidecar), os dados ficam acessiveis. |
| **Recomendacao** | (1) Em producao, expor APENAS `health`. Metricas devem ser coletadas via Micrometer/CloudWatch, nao via HTTP. (2) Se metrics for necessario via HTTP, proteger com Spring Security ou rede (security group sem ingress na porta de management). (3) Configurar `management.server.port` diferente da porta principal e garantir que nao tenha ingress no security group. |
| **Status** | **PENDENTE** |

### FINDING-03: Credenciais em variaveis de ambiente no Docker Compose

| Campo | Valor |
|-------|-------|
| **Severidade** | MEDIO |
| **Componente** | `docker-compose.yml` |
| **Descricao** | O docker-compose usa `MYSQL_ROOT_PASSWORD: root`, `MYSQL_PASSWORD: roubometro` em texto plano. Isso e aceitavel para dev local, mas o arquivo esta no repositorio. Se o mesmo padrao for replicado em producao (env vars hardcoded no task definition ECS), ha risco de vazamento. |
| **Recomendacao** | (1) Dev local: aceitavel como esta. Documentar que essas credenciais sao APENAS para dev. (2) Producao: usar AWS Secrets Manager ou SSM Parameter Store (SecureString). O task definition ECS deve referenciar secrets, nao valores inline. (3) Nunca commitar `.env` files com credenciais reais. Adicionar ao `.gitignore`. |
| **Status** | **MITIGADO para dev** / **PENDENTE para producao** |

### FINDING-04: Ausencia de whitelist de dominios no download

| Campo | Valor |
|-------|-------|
| **Severidade** | ALTO |
| **Componente** | `PortalHttpClient`, `FileDownloadService`, `DataAcquisitionTasklet` |
| **Descricao** | A URL de download e configuravel via `roubometro.portal.csv-url`. Se um atacante conseguir alterar essa configuracao (env var, config server, etc.), pode apontar para um servidor malicioso. Alem disso, se o HTTP client seguir redirects automaticamente, uma URL legitima pode redirecionar para um dominio malicioso (SSRF). |
| **Recomendacao** | (1) Implementar whitelist de dominios permitidos: `ispdados.rj.gov.br`, `www.ispdados.rj.gov.br`. Validar antes de executar qualquer request. (2) Desabilitar redirect automatico no HTTP client, ou validar o dominio de destino apos cada redirect. (3) Nao permitir URLs com esquema diferente de `https://` (exceto em profile local). (4) Rejeitar URLs com IP literal (ex: `http://169.254.169.254` -- SSRF para AWS metadata). |
| **Status** | **PENDENTE** |

### FINDING-05: Sem limite de tamanho no download do CSV

| Campo | Valor |
|-------|-------|
| **Severidade** | MEDIO |
| **Componente** | `FileDownloadService` |
| **Descricao** | O documento nao menciona limite maximo de tamanho para o arquivo baixado. Se o portal for comprometido ou a URL alterada, o batch pode baixar um arquivo arbitrariamente grande, causando exaustao de disco ou memoria. |
| **Recomendacao** | (1) Definir limite maximo de download: 50MB (CSV mensal atual tem ~12.144 linhas x 62 colunas, estimado em ~5-10MB). 50MB da margem mas protege contra abuso. (2) Implementar verificacao durante o streaming do download (abortar se exceder limite). (3) Verificar espaco em disco disponivel antes de iniciar download. |
| **Status** | **PENDENTE** |

### FINDING-06: Encoding CSV ISO-8859-1 sem validacao

| Campo | Valor |
|-------|-------|
| **Severidade** | MEDIO |
| **Componente** | `CsvItemReaderConfig` |
| **Descricao** | O agent template menciona "Definir encoding explicitamente no reader (UTF-8)", mas o CSV real usa ISO-8859-1. O architect definiu `csv-encoding: ISO-8859-1` como configuravel. O risco e: se o encoding mudar e nao for detectado, caracteres acentuados sao corrompidos silenciosamente, levando a falhas de lookup por nome de municipio ou categorias com caracteres quebrados. |
| **Recomendacao** | (1) Usar o encoding configurado (ISO-8859-1 atualmente). (2) Apos ler o header, validar que contem caracteres esperados (ex: "fmun_cod", "fmun", "ano"). Se o header for ilegivel, lancar `CsvParsingException`. (3) Como melhoria futura: detectar encoding automaticamente com biblioteca (ex: juniversalchardet). |
| **Status** | **PARCIALMENTE MITIGADO** (encoding configuravel, mas sem validacao de integridade) |

### FINDING-07: Batch escreve em tabelas da API sem coordenacao de schema

| Campo | Valor |
|-------|-------|
| **Severidade** | ALTO |
| **Componente** | `MonthlyStatItemWriterConfig`, ADR-007 |
| **Descricao** | O batch faz INSERT em `monthly_stats` e `categories`, tabelas cujo DDL e gerenciado pelo Knex (roubometro-back). Se a API alterar o schema dessas tabelas (ex: rename de coluna, mudanca de tipo), o batch quebra sem aviso previo. Nao ha mecanismo de verificacao de compatibilidade de schema entre os dois servicos. |
| **Recomendacao** | (1) No inicio de cada execucao do job, validar que as colunas esperadas existem em `monthly_stats` e `categories` (schema validation step). Usar `DESCRIBE monthly_stats` ou `INFORMATION_SCHEMA.COLUMNS`. (2) Se o schema nao bater, falhar o job com mensagem clara. (3) Incluir teste de integracao que valida compatibilidade de schema (ja previsto no ARCHITECTURE.md risco R6). (4) Documentar contrato de schema entre batch e API. |
| **Status** | **PENDENTE** |

### FINDING-08: Docker MySQL exposto em todas interfaces

| Campo | Valor |
|-------|-------|
| **Severidade** | BAIXO |
| **Componente** | `docker-compose.yml` |
| **Descricao** | O port mapping `"3306:3306"` expoe o MySQL em `0.0.0.0:3306`, acessivel por qualquer interface de rede da maquina. Em ambientes de dev compartilhados ou redes Wi-Fi publicas, isso pode permitir acesso ao banco local. |
| **Recomendacao** | Usar `"127.0.0.1:3306:3306"` para bind apenas em localhost. |
| **Status** | **PENDENTE** (correcao trivial) |

### FINDING-09: spring.batch.jdbc.initialize-schema=always em producao

| Campo | Valor |
|-------|-------|
| **Severidade** | MEDIO |
| **Componente** | `application.yml` |
| **Descricao** | A configuracao `initialize-schema: always` tenta criar as tabelas BATCH_* em toda inicializacao. Em producao, isso pode causar erros se o usuario do banco nao tiver permissao CREATE TABLE, ou pode mascarar problemas de schema. Alem disso, concede ao usuario batch permissoes DDL que podem nao ser desejadas. |
| **Recomendacao** | (1) Em producao, usar `initialize-schema: never` e criar as tabelas via Flyway (migration dedicada). (2) Ou manter `always` mas garantir que o usuario MySQL de producao tenha permissoes limitadas (apenas as tabelas do batch + BATCH_*). (3) Alternativa: usar `embedded` para testes e `never` para prod, com Flyway cuidando de tudo. |
| **Status** | **PENDENTE** |

### FINDING-10: Ausencia de path traversal protection no nome do arquivo

| Campo | Valor |
|-------|-------|
| **Severidade** | MEDIO |
| **Componente** | `FileDownloadService` |
| **Descricao** | O arquivo CSV e salvo em `/tmp/roubometro/`. Se o nome do arquivo for derivado da URL ou header `Content-Disposition` sem sanitizacao, um atacante poderia forcar escrita em caminhos arbitrarios (ex: `../../etc/cron.d/malicious`). |
| **Recomendacao** | (1) NUNCA usar o nome fornecido pelo servidor diretamente. (2) Gerar nome fixo ou baseado em hash: `{temp-dir}/batch_{timestamp}_{hash}.csv`. (3) Validar que o path final esta dentro do diretorio esperado (`tempDir.resolve(name).normalize().startsWith(tempDir)`). |
| **Status** | **PENDENTE** |

---

## 2. Checklist de Requisitos por Componente

Legenda:
- **Severidade**: CRITICO / ALTO / MEDIO / BAIXO
- **Status**: PENDENTE (a implementar) / MITIGADO (ja endereçado no design) / N/A

### 2.1 Scraping e Download (Step 1)

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-DL-01 | Whitelist de dominios permitidos para download (`ispdados.rj.gov.br`) | ALTO | PENDENTE | Teste unitario: URL fora da whitelist -> rejeicao |
| SEC-DL-02 | Nao seguir redirects automaticamente, ou validar dominio apos redirect | ALTO | PENDENTE | Teste com WireMock: redirect para dominio externo -> rejeicao |
| SEC-DL-03 | Rejeitar URLs com IP literal (prevencao SSRF para metadata AWS 169.254.x.x) | ALTO | PENDENTE | Teste unitario: URL com IP -> rejeicao |
| SEC-DL-04 | Timeout de conexao configurado (10s connect, 30s read -- ja no design) | MEDIO | MITIGADO | Verificar em `application.yml`: `connect-timeout-ms`, `read-timeout-ms` |
| SEC-DL-05 | Limite maximo de tamanho de download (50MB) | MEDIO | PENDENTE | Teste: streaming de arquivo > 50MB -> abort com excecao |
| SEC-DL-06 | Usar HTTPS obrigatoriamente (exceto profile local) | MEDIO | MITIGADO | URL padrao ja usa `https://`. Validar no codigo: rejeitar `http://` em producao |
| SEC-DL-07 | Sanitizar nome do arquivo (nao usar nome do servidor; gerar nome fixo/hash) | MEDIO | PENDENTE | Teste: nome com `../` -> path normalizado dentro de temp-dir |
| SEC-DL-08 | Retry com backoff exponencial (3 tentativas -- ja no design) | BAIXO | MITIGADO | Verificar config `retry-attempts`, `retry-backoff-ms` |
| SEC-DL-09 | Nao logar conteudo do arquivo em nivel INFO/WARN | BAIXO | PENDENTE | Code review: nenhum log contendo linhas do CSV |

### 2.2 Processamento CSV (Step 2)

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-CSV-01 | NUNCA concatenar valores do CSV em SQL; usar parametros (`?`) | CRITICO | MITIGADO | JPA + `JdbcBatchItemWriter` usam parametros. Code review obrigatorio. |
| SEC-CSV-02 | Limitar tamanho de campos string conforme DDL (VARCHAR constraints) | ALTO | PENDENTE | Teste: campo `fmun` com 1000 caracteres -> truncar ou rejeitar |
| SEC-CSV-03 | Validar campos obrigatorios (`fmun_cod`, `ano` nao nulos/vazios) | ALTO | MITIGADO | Ja previsto no `EstatisticaItemProcessor` |
| SEC-CSV-04 | Validar ranges numericos (valor absoluto nao pode ser negativo; ano entre 2000-2100; mes entre 1-12) | MEDIO | PENDENTE | Teste unitario: valor negativo -> skip; ano=1800 -> skip; mes=13 -> skip |
| SEC-CSV-05 | Definir encoding explicitamente no reader (ISO-8859-1 configuravel) | MEDIO | MITIGADO | Ja previsto em `csv-encoding: ISO-8859-1` |
| SEC-CSV-06 | Validar header do CSV antes de processar (colunas esperadas) | MEDIO | PENDENTE | Teste: CSV com header diferente -> `CsvParsingException` |
| SEC-CSV-07 | Strip/trim campos string do CSV antes de usar | BAIXO | PENDENTE | Teste: `" Angra dos Reis "` -> `"Angra dos Reis"` |
| SEC-CSV-08 | Sanitizar strings: remover caracteres de controle (\\0, \\r ilegitimo) | BAIXO | PENDENTE | Teste: campo com `\\0` -> removido |
| SEC-CSV-09 | Limitar numero total de linhas processadas (ex: 100.000) como safety net | BAIXO | PENDENTE | Se CSV exceder 100k linhas -> alerta + continua (nao bloqueia) |

### 2.3 Persistencia (MySQL)

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-DB-01 | Credenciais NUNCA hardcoded; usar env vars (dev) e Secrets Manager (prod) | CRITICO | PARCIAL | Dev: env vars no docker-compose. Prod: PENDENTE -- definir integracao com Secrets Manager |
| SEC-DB-02 | Conexao SSL obrigatoria em producao (`useSSL=true&requireSSL=true`) | CRITICO | MITIGADO | Ja configurado em `application-prod.yml` |
| SEC-DB-03 | Verificacao de certificado SSL (`verifyServerCertificate`) | CRITICO | **PENDENTE** | Ver FINDING-01. Configurar truststore ou aceitar risco formalmente. |
| SEC-DB-04 | SQL injection: usar APENAS parametros nomeados ou `?`; NUNCA concatenacao | CRITICO | MITIGADO | JPA + JdbcTemplate com `?`. Code review obrigatorio. |
| SEC-DB-05 | Pool de conexoes conservador (max 3 em prod) | ALTO | MITIGADO | Ja configurado em `application-prod.yml` |
| SEC-DB-06 | Usuario MySQL de producao com permissoes minimas | ALTO | PENDENTE | Definir GRANT: SELECT/INSERT/UPDATE em tabelas especificas. Nao conceder DROP/ALTER em tabelas da API. |
| SEC-DB-07 | Batch NUNCA executa DELETE/DROP/ALTER em tabelas da API | CRITICO | MITIGADO | Por design (ADR-007). Verificar via code review e teste de integracao. |
| SEC-DB-08 | `initialize-schema` diferenciado por profile (never em prod) | MEDIO | PENDENTE | Ver FINDING-09 |
| SEC-DB-09 | Validar schema de `monthly_stats` e `categories` no inicio do job | ALTO | PENDENTE | Ver FINDING-07 |
| SEC-DB-10 | Connection test query configurada (`SELECT 1`) | BAIXO | MITIGADO | Ja em `application-prod.yml` |

### 2.4 Infraestrutura Docker

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-DK-01 | Dockerfile com usuario nao-root (`USER` directive) | ALTO | PENDENTE | Verificar Dockerfile: presenca de `USER appuser` ou similar |
| SEC-DK-02 | Imagem base oficial e minima (`eclipse-temurin:21-jre-alpine`) | ALTO | PENDENTE | Verificar `FROM` no Dockerfile |
| SEC-DK-03 | Multi-stage build (nao incluir JDK, Maven, sources na imagem final) | MEDIO | PENDENTE | Verificar Dockerfile: stage de build separado de runtime |
| SEC-DK-04 | Nenhum secret copiado na imagem (COPY de .env, application-prod.yml com senhas) | CRITICO | PENDENTE | Verificar Dockerfile + `.dockerignore` |
| SEC-DK-05 | MySQL local bind em 127.0.0.1 (nao 0.0.0.0) | BAIXO | PENDENTE | Ver FINDING-08 |
| SEC-DK-06 | `.dockerignore` inclui `.env`, `.git`, `target/`, `*.yml` com secrets | MEDIO | PENDENTE | Verificar existencia e conteudo de `.dockerignore` |

### 2.5 AWS (Producao)

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-AWS-01 | IAM role com permissoes minimas (CloudWatch Logs, Secrets Manager read, S3 read/write no bucket especifico) | ALTO | PENDENTE | Revisar policy IAM antes de deploy |
| SEC-AWS-02 | Credenciais do banco via AWS Secrets Manager (nao env vars hardcoded no task def) | CRITICO | PENDENTE | Task definition ECS referencia secret ARN, nao valor |
| SEC-AWS-03 | Logs nao contem credenciais, tokens, ou dados pessoais | ALTO | PENDENTE | Code review: nenhum log com password, connection string, CPF |
| SEC-AWS-04 | Security Group do ECS: sem ingress publico (batch nao recebe conexoes) | ALTO | PENDENTE | Revisar SG: apenas egress para porta 3306 da hospedagem e 443 para ISP-RJ |
| SEC-AWS-05 | Elastic IP fixo para whitelisting na hospedagem | MEDIO | PENDENTE | Usar NAT Gateway com Elastic IP; liberar no firewall da hospedagem |
| SEC-AWS-06 | CloudWatch Logs com retention policy (ex: 90 dias) | BAIXO | PENDENTE | Configurar log group retention |
| SEC-AWS-07 | Nao usar `latest` como tag de imagem Docker no ECR | BAIXO | PENDENTE | Usar tag com versao semantica ou SHA do commit |

### 2.6 Spring Batch / Framework

| ID | Requisito | Severidade | Status | Verificacao |
|----|-----------|------------|--------|-------------|
| SEC-SB-01 | `spring.batch.job.enabled=false` (execucao apenas via scheduler) | ALTO | MITIGADO | Ja configurado em `application.yml` |
| SEC-SB-02 | Actuator: expor apenas `health` em producao | ALTO | PENDENTE | Ver FINDING-02 |
| SEC-SB-03 | JobRepository em tabelas BATCH_* (nao contem dados sensiveis de negocio) | BAIXO | MITIGADO | Tabelas BATCH_* guardam apenas metadata de execucao |
| SEC-SB-04 | Nao expor porta de management externamente | MEDIO | PENDENTE | `management.server.port` separado + SG sem ingress |
| SEC-SB-05 | Desabilitar Spring DevTools em producao | MEDIO | PENDENTE | Garantir que `spring-boot-devtools` nao esta no classpath de producao |

---

## 3. Dependencias e Versoes

### 3.1 Dependencias esperadas (pre-aprovadas com ressalvas)

| Dependencia | Justificativa | Notas de seguranca |
|-------------|---------------|-------------------|
| `spring-boot-starter-batch` | Core do batch | Manter atualizado com Spring Boot BOM |
| `spring-boot-starter-data-jpa` | Persistencia JPA | Usar parametros, nunca concatenacao |
| `mysql-connector-j` | Driver JDBC MySQL (NAO postgresql) | Versao >= 8.0.33 recomendada (fixes de seguranca SSL) |
| `jsoup` | Parsing HTML para fallback scraping | Versao >= 1.17.2. Jsoup faz sanitizacao de HTML por padrao. |
| `flyway-core` + `flyway-mysql` | Migrations do batch | Flyway tabela separada (`flyway_schema_history_batch`) |
| `spring-boot-starter-test` | Testes | Somente scope test |
| `testcontainers` (mysql) | Testes de integracao | Somente scope test |
| `wiremock` | Mock HTTP para testes | Somente scope test |
| `logstash-logback-encoder` | Logs JSON estruturados | Verificar que nao loga objetos inteiros (pode vazar dados) |
| `spring-boot-starter-actuator` | Metricas | Restringir endpoints expostos (ver SEC-SB-02) |

### 3.2 Dependencias NAO permitidas

| Dependencia | Motivo |
|-------------|--------|
| `postgresql` (driver) | Banco e MySQL, nao PostgreSQL |
| `spring-boot-devtools` | Risco em producao (remote debug, live reload). Usar apenas em dev local com profile |
| `spring-boot-starter-web` | Batch nao precisa de servidor HTTP. Se necessario para Actuator, usar com `management.server.port` restrito |
| Qualquer driver JDBC alem de `mysql-connector-j` | Unico banco e MySQL |

### 3.3 Verificacao de vulnerabilidades

Apos criar o `pom.xml`, executar:

```bash
# Arvore de dependencias
mvn -q dependency:tree

# Verificacao de CVEs (OWASP Dependency Check)
mvn org.owasp:dependency-check-maven:check

# Alternativa mais rapida para CI
mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
```

**Requisito**: nenhuma dependencia com CVE de severidade CRITICA (CVSS >= 9.0) deve ir para producao. CVEs ALTAS (CVSS >= 7.0) devem ser avaliadas caso a caso.

---

## 4. Modelo de Ameacas

### 4.1 Diagrama de fluxo de dados (simplificado)

```
                    INTERNET PUBLICA
                         |
    +--------------------+--------------------+
    |                    |                    |
    v                    v                    v
[ISP-RJ Portal]    [AWS ECS Batch]    [Hospedagem MySQL]
 (fonte CSV)        (processamento)     (banco prod)
    |                    |                    ^
    | HTTPS GET          | MySQL/SSL          |
    +--------->----------+---------->---------+
                         |
                    [AWS CloudWatch]
                      (logs)
```

### 4.2 Atores de ameaca

| Ator | Motivacao | Capacidade |
|------|-----------|------------|
| Atacante na rede (MITM) | Interceptar credenciais MySQL | Posicionado entre AWS e hospedagem |
| Portal ISP-RJ comprometido | Servir CSV malicioso ou redirecionar para URL maliciosa | Controla conteudo da URL |
| Insider com acesso ao repositorio | Extrair credenciais commitadas | Acesso ao git |
| Atacante com acesso a hospedagem | Manipular dados do banco | Acesso ao MySQL da hospedagem |
| Atacante com acesso ao container ECS | Extrair env vars, secrets | Acesso ao runtime do batch |

### 4.3 Cenarios de ataque e mitigacoes

| # | Cenario | Impacto | Controle |
|---|---------|---------|----------|
| T1 | MITM intercepta credenciais MySQL entre AWS e hospedagem | Acesso total ao banco | SEC-DB-02, SEC-DB-03 (SSL + cert verification) |
| T2 | CSV malicioso com SQL injection em campo de municipio | Execucao de SQL arbitrario | SEC-CSV-01 (parametros), SEC-CSV-02 (limites) |
| T3 | URL de download redirecionada para servidor do atacante | SSRF, download de payload malicioso | SEC-DL-01, SEC-DL-02, SEC-DL-03 (whitelist) |
| T4 | CSV gigante causa exaustao de disco/memoria | DoS no batch | SEC-DL-05 (limite de tamanho) |
| T5 | Credenciais MySQL no docker-compose copiadas para prod | Acesso ao banco de producao | SEC-DB-01, SEC-DK-04 (secrets management) |
| T6 | Batch faz DROP TABLE em tabela da API por bug | Perda de dados da API | SEC-DB-06, SEC-DB-07 (permissoes minimas) |
| T7 | Logs no CloudWatch vazam connection string | Credenciais expostas | SEC-AWS-03 (audit de logs) |
| T8 | Container batch acessivel pela internet | Superficie de ataque ampliada | SEC-AWS-04 (SG sem ingress) |
| T9 | Knex migration altera schema de monthly_stats | Batch quebra silenciosamente e corrompe dados | SEC-DB-09 (schema validation) |
| T10 | Arquivo CSV salvo com path traversal | Escrita arbitraria no filesystem | SEC-DL-07 (sanitizacao de path) |

---

## 5. Verificacao e Testes de Seguranca

### 5.1 Testes automatizados (responsabilidade do test-automator)

| ID teste | Requisito coberto | Tipo | Descricao |
|----------|------------------|------|-----------|
| TST-SEC-01 | SEC-DL-01 | Unitario | `PortalHttpClient` rejeita URL com dominio fora da whitelist |
| TST-SEC-02 | SEC-DL-02 | Unitario (WireMock) | HTTP client nao segue redirect para dominio externo |
| TST-SEC-03 | SEC-DL-03 | Unitario | Rejeita URL com IP literal (`http://169.254.169.254/latest/meta-data/`) |
| TST-SEC-04 | SEC-DL-05 | Unitario (WireMock) | Download abortado quando excede 50MB |
| TST-SEC-05 | SEC-DL-07 | Unitario | Nome de arquivo com `../` e normalizado para dentro de temp-dir |
| TST-SEC-06 | SEC-CSV-01 | Code review | Grep no codigo: nenhuma concatenacao SQL (regex: `".*" \+ .*` em queries) |
| TST-SEC-07 | SEC-CSV-02 | Unitario | Campo string com 1000+ chars -> truncado ou rejeitado |
| TST-SEC-08 | SEC-CSV-04 | Unitario | Valor negativo -> skip; ano fora de range -> skip; mes fora de 1-12 -> skip |
| TST-SEC-09 | SEC-CSV-06 | Unitario | CSV com header inesperado -> `CsvParsingException` |
| TST-SEC-10 | SEC-DB-07 | Integracao (Testcontainers) | Usuario MySQL do batch NAO consegue executar DROP/ALTER em tabelas da API |
| TST-SEC-11 | SEC-DB-09 | Integracao (Testcontainers) | Job falha graciosamente se `monthly_stats` nao tem colunas esperadas |
| TST-SEC-12 | SEC-DK-01 | CI/CD | `docker inspect` verifica que container nao roda como root |
| TST-SEC-13 | SEC-AWS-03 | Code review | Grep em logs: nenhuma ocorrencia de patterns de credenciais |

### 5.2 Auditorias manuais (pre-deploy)

| # | Acao | Responsavel | Quando |
|---|------|-------------|--------|
| AUD-01 | Executar `mvn org.owasp:dependency-check-maven:check` e revisar relatorio | security-auditor | Antes de cada release |
| AUD-02 | Revisar task definition ECS: secrets via Secrets Manager, nao env vars inline | security-auditor | Antes do primeiro deploy |
| AUD-03 | Revisar IAM policy: principio de menor privilegio | security-auditor | Antes do primeiro deploy |
| AUD-04 | Validar que Security Group nao permite ingress publico ao container batch | security-auditor | Antes do primeiro deploy |
| AUD-05 | Validar configuracao SSL do MySQL na hospedagem (tipo de certificado) | infra | Antes do primeiro deploy |
| AUD-06 | Revisar `.gitignore` e `.dockerignore` para ausencia de arquivos sensiveis | security-auditor | No setup do repositorio |
| AUD-07 | Validar permissoes do usuario MySQL de producao (GRANT statement) | DBA / infra | Antes do primeiro deploy |

### 5.3 Checklist de CI/CD

```
[ ] OWASP Dependency Check sem CVEs CRITICAS
[ ] Docker image scan (Trivy ou ECR scan) sem vulnerabilidades CRITICAS
[ ] Nenhum secret hardcoded no codigo (git-secrets ou trufflehog)
[ ] Testes de seguranca automatizados passando (TST-SEC-*)
[ ] Imagem Docker nao roda como root
[ ] Tag de imagem != latest
```

---

## Apendice A: Configuracao recomendada do usuario MySQL de producao

```sql
-- Criar usuario dedicado para o batch
CREATE USER 'roubometro_batch'@'%' IDENTIFIED BY '<senha-do-secrets-manager>';

-- Permissoes em tabelas da API (somente leitura + insert em monthly_stats e categories)
GRANT SELECT ON roubometro.municipalities TO 'roubometro_batch'@'%';
GRANT SELECT ON roubometro.regions TO 'roubometro_batch'@'%';
GRANT SELECT ON roubometro.states TO 'roubometro_batch'@'%';
GRANT SELECT, INSERT ON roubometro.categories TO 'roubometro_batch'@'%';
GRANT SELECT, INSERT, UPDATE ON roubometro.monthly_stats TO 'roubometro_batch'@'%';

-- Permissoes em tabelas do batch (full CRUD + DDL para Flyway)
GRANT ALL PRIVILEGES ON roubometro.batch_file_metadata TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.batch_job_execution_report TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.flyway_schema_history_batch TO 'roubometro_batch'@'%';

-- Permissoes para tabelas Spring Batch (BATCH_*)
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_INSTANCE TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_EXECUTION TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_EXECUTION_PARAMS TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_STEP_EXECUTION TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_STEP_EXECUTION_CONTEXT TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_EXECUTION_CONTEXT TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_EXECUTION_SEQ TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_JOB_SEQ TO 'roubometro_batch'@'%';
GRANT ALL PRIVILEGES ON roubometro.BATCH_STEP_EXECUTION_SEQ TO 'roubometro_batch'@'%';

-- Permissao CREATE TABLE para Flyway e Spring Batch auto-schema
GRANT CREATE ON roubometro.* TO 'roubometro_batch'@'%';

-- IMPORTANTE: NAO conceder DROP, ALTER, DELETE em tabelas da API
-- O batch NUNCA deve poder: DROP TABLE monthly_stats, ALTER TABLE municipalities, DELETE FROM categories

FLUSH PRIVILEGES;
```

> **Nota**: `GRANT CREATE ON roubometro.*` e necessario para Flyway criar novas tabelas e para Spring Batch criar seu schema. Como mitigacao adicional, o `initialize-schema` deve ser `never` em producao, e as tabelas BATCH_* devem ser criadas previamente via Flyway.

---

## Apendice B: Configuracao SSL recomendada (producao)

### Cenario ideal (hospedagem com CA reconhecida)

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=true&requireSSL=true&verifyServerCertificate=true&serverTimezone=America/Sao_Paulo
```

### Cenario com certificado self-signed (hospedagem barata)

```bash
# 1. Exportar certificado da hospedagem
openssl s_client -connect <host>:3306 -starttls mysql </dev/null 2>/dev/null | openssl x509 > hospedagem-cert.pem

# 2. Importar no truststore
keytool -importcert -alias hospedagem-mysql -file hospedagem-cert.pem -keystore /app/truststore.jks -storepass changeit -noprompt

# 3. Configurar JVM
JAVA_OPTS="-Djavax.net.ssl.trustStore=/app/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
```

```yaml
# application-prod.yml (com truststore)
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=true&requireSSL=true&verifyServerCertificate=true&serverTimezone=America/Sao_Paulo
```

### Cenario de risco aceito (verifyServerCertificate=false)

Se nenhuma das opcoes acima for viavel, documentar formalmente:

```
RISCO ACEITO: verifyServerCertificate=false
Data: ____
Aprovado por: ____
Justificativa: Hospedagem nao fornece certificado CA; custo de migracao inviavel na V1.
Mitigacao parcial: SSL ainda protege contra sniffing passivo.
Plano de resolucao: Migrar banco para AWS RDS (tem CA publica) no roadmap V2.
```

---

## Apendice C: Resumo executivo para o dono do produto

### Acoes bloqueantes antes de ir para producao

| # | Acao | Severidade | Esforco |
|---|------|------------|---------|
| 1 | Decidir sobre `verifyServerCertificate` (FINDING-01) | CRITICO | Baixo (config) ou medio (truststore) |
| 2 | Configurar AWS Secrets Manager para credenciais do banco (SEC-DB-01, SEC-AWS-02) | CRITICO | Medio |
| 3 | Implementar whitelist de dominios no download (SEC-DL-01 a 03) | ALTO | Baixo |
| 4 | Criar usuario MySQL dedicado com permissoes minimas (Apendice A) | ALTO | Baixo |
| 5 | Restringir Actuator em producao (SEC-SB-02) | ALTO | Baixo |
| 6 | Implementar validacao de schema no inicio do job (SEC-DB-09) | ALTO | Medio |

### Acoes recomendadas (podem ser pos-V1)

| # | Acao | Severidade | Esforco |
|---|------|------------|---------|
| 7 | Dockerfile com multi-stage build e usuario nao-root | ALTO | Baixo |
| 8 | OWASP Dependency Check no CI/CD | MEDIO | Baixo |
| 9 | Limite de tamanho no download (SEC-DL-05) | MEDIO | Baixo |
| 10 | Sanitizacao de path no nome do arquivo (SEC-DL-07) | MEDIO | Baixo |
| 11 | `initialize-schema: never` em prod com Flyway para tabelas BATCH_* | MEDIO | Medio |
| 12 | Bind MySQL Docker em 127.0.0.1 | BAIXO | Trivial |
