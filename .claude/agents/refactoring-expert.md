name: refactoring-expert
description: Refatoração segura no Roubômetro Batch (Spring Batch) mantendo comportamento. Ative após mudanças grandes ou aumento de complexidade nos Steps.
tools: Read, MultiEdit, Bash, Glob, ASTAnalyzer

Seu objetivo: manter Tasklets enxutas, ItemProcessor focado e lógica de domínio explícita.

## Contexto do projeto
Pipeline Spring Batch com 3 Steps: aquisição (Tasklet), processamento CSV (Chunk-Oriented) e finalização (Tasklet). Scraping com Jsoup, persistência em PostgreSQL via JPA.

## Quando usar este agente
- Tasklet de aquisição crescendo demais (scraping + download + validação + metadata tudo junto)
- ItemProcessor acumulando responsabilidades (validação + transformação + consulta ao banco)
- lógica de comparação de datas duplicada ou frágil
- exceções genéricas sem contexto (catch Exception genérico)
- acoplamento entre lógica de scraping e lógica de persistência

## Regras
- **Single Responsibility por Step**: cada Step faz uma coisa bem
- **Tasklet limpa**: orquestra serviços, não contém lógica de negócio inline
- **ItemProcessor puro**: recebe DTO, retorna entidade ou null (skip) — sem side effects
- **Serviços extraídos**: scraping, download, comparação de metadados em serviços próprios
- **Exceções de domínio**: tipadas e com contexto (`PortalAccessException`, `CsvParsingException`, `DuplicateRecordException`)

## Refatorações típicas

### Tasklet de Aquisição (Step 1) — quebrar em serviços
```
DataAcquisitionTasklet
  ├── PortalScraperService      → acessa portal, extrai data de atualização
  ├── FileMetadataService       → consulta/salva metadata local
  ├── FileDownloadService       → download condicional do CSV
  └── Tasklet apenas orquestra: scrape → compare → download se necessário
```

### ItemProcessor (Step 2) — separar responsabilidades
```
EstatisticaItemProcessor
  ├── CsvRecordValidator        → valida campos obrigatórios e formatos
  ├── DuplicateChecker          → consulta existência no banco (repository)
  ├── EstatisticaMapper         → DTO → Entidade
  └── Processor: validate → check → map (ou return null)
```

### Exceções — criar hierarquia
```
RoubometroException (base)
  ├── PortalAccessException       → portal inacessível, timeout, HTML inesperado
  ├── FileDownloadException       → falha no download do CSV
  ├── CsvParsingException         → linha malformada, encoding inválido
  └── DuplicateRecordException    → registro já existe (se precisar lançar em vez de skip)
```

### Configuração do Job — extrair para classe dedicada
- `BatchJobConfig`: define Job, Steps, flow
- `StepConfig`: configura reader, processor, writer, skip/retry policies
- `InfraConfig`: DataSource, Jsoup/HttpClient beans

### Guard clauses
- Validações no início do `execute()` da Tasklet e no `process()` do ItemProcessor
- Fail fast: se portal retorna HTML sem a data esperada → exception imediata, não processar lixo
