# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Oracle-to-PostgreSQL synchronization and migration tool built with Quarkus (Java 18). The system operates in multiple phases: schema discovery, table metadata extraction, object type processing, and data synchronization. The architecture follows enterprise Java patterns with CDI, event-driven state management, and plugin-based extensibility.

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

### Event-Driven State Management Architecture

The application uses a modern event-driven architecture with CDI events for loose coupling and extensibility:

#### 1. **State Management** (`core/state/`)
- **Event-driven**: Uses CDI events for state updates
- **Thread-safe**: State managers use ReadWriteLocks for concurrent access
- **Separation of concerns**: Each data type has its own state manager

**State Managers:**
- `TableMetadataStateManager`: Manages table metadata for Oracle/PostgreSQL
- `ObjectDataTypeStateManager`: Manages object type metadata
- `SchemaStateManager`: Manages schema lists
- `StateService`: Event dispatcher and backward-compatibility layer

**Events:**
- `TableMetadataUpdatedEvent`: Fired when table metadata is updated
- `ObjectDataTypeUpdatedEvent`: Fired when object types are updated
- `SchemaListUpdatedEvent`: Fired when schema lists are updated
- `DatabaseMetadataUpdatedEvent<T>`: Base event class

#### 2. **Plugin-Based Job System** (`core/job/`)
- **Auto-discovery**: Jobs are automatically discovered via CDI
- **Registry pattern**: `JobRegistry` manages all available extraction jobs
- **Generic interfaces**: `DatabaseExtractionJob<T>` for type-safe job contracts
- **Base functionality**: `AbstractDatabaseExtractionJob<T>` provides common behavior

**Job Architecture:**
- `JobRegistry`: CDI-based discovery and creation of jobs
- `JobService`: Job execution and lifecycle management
- `JobResource`: Generic REST endpoints for any job type
- Job classes: Extend `AbstractDatabaseExtractionJob<T>` with `@Dependent` scope

#### 3. **Database Element Modules** (Independent & Extensible)
Each database element type is completely independent:

**Tables** (`table/`):
- `TableMetadata`, `ColumnMetadata`, `ConstraintMetadata`: Pure data models
- `OracleTableMetadataExtractionJob`, `PostgresTableMetadataExtractionJob`: CDI-managed jobs
- `TableExtractor`, `PostgresTableExtractor`: Database-specific extraction logic

**Object Types** (`objectdatatype/`):
- `ObjectDataTypeMetaData`, `ObjectDataTypeVariable`: Data models
- `OracleObjectDataTypeExtractionJob`, `PostgresObjectDataTypeExtractionJob`: CDI-managed jobs
- Service classes for REST endpoint compatibility

**Schemas** (`schema/`):
- Schema discovery and management services
- REST endpoints for schema information

#### 4. **Cross-Cutting Concerns** (`core/`)
- `TypeConverter`: Oracle-to-PostgreSQL data type mapping
- `PostgreSqlIdentifierUtils`: PostgreSQL naming conventions
- `UserExcluder`: Schema filtering logic
- `NameNormalizer`, `CodeCleaner`: Data processing utilities

#### 5. **Database Connectivity** (`database/`)
- `OracleConnectionService`: Oracle database connections
- `PostgresConnectionService`: PostgreSQL database connections
- Connection testing and validation

#### 6. **Configuration Management** (`config/`)
- `ConfigService`: Application configuration management
- REST endpoints for configuration updates

### ANTLR Integration
- **Grammar files**: `PlSqlParser.g4`, `PlSqlLexer.g4` in `src/main/antlr4/`
- **Generated parsers**: Output to `target/generated-sources/antlr4/`
- **Base classes**: `PlSqlParserBase`, `PlSqlLexerBase` for grammar extensions

### Frontend
- **Location**: `src/main/resources/META-INF/resources/`
- **Technology**: Vanilla JavaScript (no frameworks)
- **Purpose**: Database connection configuration and migration monitoring
- **API Integration**: Consumes REST endpoints for job management and status

## Key Architectural Principles

### 1. **Dependency Independence**
- Database elements (tables, objects) are completely independent
- Core modules only depend on cross-cutting concerns
- No circular dependencies between domain modules

### 2. **Event-Driven Communication**
- State updates use CDI events (`@Observes`)
- Loose coupling between components
- Easy to add new listeners for state changes

### 3. **Plugin Architecture**
- New extraction types are automatically discovered
- Jobs implement `DatabaseExtractionJob<T>` interface
- No code changes needed in core infrastructure for new job types

### 4. **Type Safety**
- Generic interfaces ensure compile-time type safety
- `DatabaseExtractionJob<TableMetadata>` vs `DatabaseExtractionJob<ObjectDataTypeMetaData>`
- State managers typed to specific data models

### 5. **CDI Best Practices**
- Proper scoping: `@ApplicationScoped` for singletons, `@Dependent` for jobs
- Event-driven: `@Inject Event<T>` for firing events
- Observer pattern: `@Observes` for event handling

## Adding New Database Elements

To add a new database element type (e.g., row counts, views, indexes):

### 1. Create Data Model
```java
// In new package: src/main/java/.../rowcount/model/
public class RowCountMetadata {
    private String schema;
    private String tableName;
    private long rowCount;
    // constructors, getters, toString
}
```

### 2. Create Event (Optional)
```java
// In core/event/
public class RowCountUpdatedEvent extends DatabaseMetadataUpdatedEvent<RowCountMetadata> {
    public static RowCountUpdatedEvent forOracle(List<RowCountMetadata> rowCounts) {
        return new RowCountUpdatedEvent("ORACLE", rowCounts);
    }
}
```

### 3. Create Extraction Jobs
```java
// Oracle job
@Dependent
public class OracleRowCountExtractionJob extends AbstractDatabaseExtractionJob<RowCountMetadata> {
    @Override
    public String getSourceDatabase() { return "ORACLE"; }

    @Override
    public String getExtractionType() { return "ROW_COUNT"; }

    @Override
    public Class<RowCountMetadata> getResultType() { return RowCountMetadata.class; }

    @Override
    protected void saveResultsToState(List<RowCountMetadata> results) {
        // Fire event or update state
    }

    @Override
    protected List<RowCountMetadata> performExtraction(Consumer<JobProgress> progressCallback) {
        // Implementation
    }
}
```

### 4. Done!
- Jobs are automatically discovered by `JobRegistry`
- REST endpoints work immediately: `POST /api/jobs/oracle/row-count/extract`
- State management works through events
- Progress tracking and error handling included

## Current Implementation Status

### ✅ Completed Features
- Event-driven state management with CDI
- Plugin-based job discovery and execution
- Table metadata extraction (Oracle ↔ PostgreSQL)
- Object type metadata extraction (Oracle ↔ PostgreSQL)
- Schema discovery and management
- Generic REST API for job management
- Database connection management and testing
- Configuration management with UI

### 🔄 Ready for Implementation
- Row count extraction (architecture supports it)
- View metadata processing
- Index metadata extraction
- Constraint synchronization
- Data migration jobs

### 🎯 Extension Points
- New job types: Implement `DatabaseExtractionJob<T>`
- State listeners: Use `@Observes` on event types
- REST endpoints: Leverage existing generic job endpoints
- Custom processing: Add event listeners for specialized logic

## Database Configuration

### Oracle Connection
- **Driver**: `com.oracle.database.jdbc:ojdbc11:23.5.0.24.07`
- **Configuration**: Runtime configurable via REST API or UI
- **Features**: Connection testing, schema discovery, metadata extraction

### PostgreSQL Connection
- **Driver**: `org.postgresql:postgresql:42.7.1`
- **Configuration**: Runtime configurable via REST API or UI
- **Features**: Connection testing, schema creation, data import

## API Documentation

- **Swagger UI**: Available at `/q/swagger-ui` when running
- **Job Management**: `/api/jobs/*` - Generic endpoints for any job type
- **Configuration**: `/api/config/*` - Database connection configuration
- **Status**: Various status endpoints for monitoring extraction progress

## Development Guidelines

### Code Organization
- **Domain modules**: Independent, only depend on `core/`, `database/`, `config/`
- **Pure data models**: No service dependencies in model classes
- **CDI annotations**: Use `@ApplicationScoped` for services, `@Dependent` for jobs
- **Event handling**: Prefer events over direct method calls for loose coupling

### Testing Strategy
- **Framework**: JUnit 5 with Mockito
- **Focus**: Test job logic, state management, event handling
- **Integration**: Test complete job execution flows
- **Mocking**: Mock database connections for unit tests

### Performance Considerations
- **State management**: Thread-safe with ReadWriteLocks
- **Job execution**: Asynchronous with CompletableFuture
- **Memory**: Defensive copying in state managers prevents memory leaks
- **Connection pooling**: Managed by Quarkus datasource configuration

This architecture provides a solid foundation for Oracle-to-PostgreSQL migration with excellent extensibility for future enhancements.