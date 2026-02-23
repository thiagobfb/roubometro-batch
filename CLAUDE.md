# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

Read the full implementation prompt before starting any work:
- **Main prompt**: `prompts/roubometro-batch.md` (versioned, contains all decisions and constraints)
- **Agents**: `.claude/agents/*.md` (7 specialized agents, activated in order via slash commands)

## Quick Reference

- **Stack**: Java 21, Spring Boot 3.x, Spring Batch 5.x, JPA/Hibernate, MySQL
- **DB**: Shared MySQL with roubometro-back (Node/Fastify). Batch uses Flyway. API uses Knex. Never mix.
- **Deploy**: Batch on AWS (ECS/Lambda). DB on external hosting (Locaweb-type). Connection over internet with SSL.
- **Business logic**: ALL in ItemProcessor. Reader only reads. Writer only writes. No exceptions.

## Development Commands

```bash
# Build
mvn clean package -DskipTests

# Run locally (requires Docker MySQL running)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Start local MySQL
docker compose up -d

# Run tests
mvn test                                        # unit tests
mvn verify -Pintegration-tests                  # integration (Testcontainers)

# Flyway
mvn flyway:info                                 # check migration status
mvn flyway:migrate                              # apply migrations

# Code formatting
mvn spotless:apply                              # if using Spotless
```

## Architecture

```
src/main/java/br/com/roubometro/
├── config/              → BatchJobConfig, StepConfig, InfraConfig
├── domain/
│   ├── model/           → EstatisticaSeguranca, FileMetadata
│   └── exception/       → PortalAccessException, CsvParsingException...
├── application/
│   ├── step/            → DataAcquisitionTasklet, FinalizationTasklet
│   ├── processor/       → EstatisticaItemProcessor (ALL business logic here)
│   └── service/         → PortalScraperService, FileDownloadService, FileMetadataService
├── infrastructure/
│   ├── reader/          → CsvItemReaderConfig (reads CSV, nothing else)
│   ├── writer/          → EstatisticaItemWriterConfig (writes to DB, nothing else)
│   ├── repository/      → EstatisticaRepository, FileMetadataRepository
│   └── client/          → PortalHttpClient
└── RoubometroBatchApplication.java
```

## Key Constraints

- **Shared DB**: Never ALTER/DROP tables managed by Knex (roubometro-back). Batch owns only its Flyway-managed tables.
- **Connection pool**: HikariCP max 3-5 connections (external hosting has low limits).
- **Chunk processing**: Reader=dumb, Processor=smart, Writer=dumb.
- **Idempotency**: Running the job N times with the same file must produce the same result.
- **Deduplication**: Use `INSERT IGNORE` or `ON DUPLICATE KEY UPDATE` (MySQL syntax). Avoid SELECT per row.

## Related Project

- **roubometro-back** (`C:\dev\roubometro-back`): Node.js/Fastify API that reads from the same MySQL database.