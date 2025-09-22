# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Oracle-to-PostgreSQL synchronization and migration tool built with Quarkus (Java 18). The system operates in three phases: tables, data, and PL/SQL-related state migration. The source database is Oracle, target is PostgreSQL, with real-time synchronization capabilities and in-memory state management.

## Build and Development Commands

### Core Development
- **Build project**: `mvn clean compile`
- **Run in development mode**: `mvn quarkus:dev`
- **Run tests**: `mvn test`
- **Generate ANTLR parsers**: `mvn antlr4:antlr4` (generates from `src/main/antlr4/`)
- **Package application**: `mvn clean package`

### Build System
- **Maven**: Uses Quarkus 3.15.1 platform
- **Java version**: 18
- **ANTLR version**: 4.13.2 for PL/SQL parsing

## Architecture Overview

### Core Components

1. **State Management** (`core/State.java`): Central ApplicationScoped bean holding extracted metadata
   - User names, object type metadata, table metadata
   - Planned: Views, synonyms, indexes, PL/SQL code, parsed ASTs

2. **Table Processing** (`table/`):
   - `TableExtractor`: Extracts Oracle table metadata
   - `ColumnMetadata`, `TableMetadata`, `ConstraintMetadata`: Data models
   - Tools: `NameNormalizer`, `CodeCleaner`, `UserExcluder`

3. **Schema Processing** (`schema/SchemaExtractor`): High-level schema extraction coordination

4. **Object Type Processing** (`objectmeta/`): Oracle custom object type handling
   - `ObjectTypeMetaData`, `ObjectTypeVariable`: Models for Oracle object types

5. **Core Utilities** (`core/`):
   - `TypeConverter`: Oracle-to-PostgreSQL data type mapping
   - `PostgreSqlIdentifierUtils`: PostgreSQL naming convention handling

6. **ANTLR Integration** (`antlr/`): PL/SQL parsing using custom grammar
   - Grammar files: `PlSqlParser.g4`, `PlSqlLexer.g4`
   - Generated parsers in `target/generated-sources/antlr4/`

### Frontend
- **Location**: `src/main/resources/META-INF/resources/`
- **Technology**: Vanilla JavaScript only (no frameworks)
- **Purpose**: Configuration UI for database connections and migration monitoring

### Configuration
- **Main config**: `src/main/resources/application.properties`
- **Database connections**: Oracle JDBC (ojdbc11) and PostgreSQL drivers
- **Logging**: File rotation to `logs/migration.log`
- **API docs**: Swagger UI at `/q/swagger-ui`

## Key Development Notes

### Database Connectivity
- Oracle: Uses `com.oracle.database.jdbc:ojdbc11:23.5.0.24.07`
- PostgreSQL: Uses `org.postgresql:postgresql:42.7.1`
- Configuration overridable from frontend UI

### Current Development Focus
1. Establish and verify Oracle/PostgreSQL connections
2. Frontend configuration mechanism for database credentials
3. Read and display source database tables
4. Create missing tables in PostgreSQL target
5. Handle custom Oracle object types (variables only for now)

### Testing
- **Framework**: JUnit 5 with Mockito
- **Test location**: `src/test/`
- **Run tests**: `mvn test`

## Project Structure Patterns

- `model/`: Data transfer objects and metadata models
- `service/`: Business logic and database interaction
- `tools/`: Utility classes for name normalization, code cleaning
- `antlr/`: PL/SQL grammar and parser integration
- `core/`: Cross-cutting utilities and central state management