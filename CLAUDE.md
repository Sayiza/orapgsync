# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Oracle-to-PostgreSQL synchronization and migration tool built with Quarkus (Java 18). The system operates in multiple phases: schema discovery, table metadata extraction, object type processing, and data synchronization. The architecture follows enterprise Java patterns with CDI, centralized state management, and plugin-based extensibility.

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

### State Management Architecture

The application uses a centralized state management approach for storing metadata:

#### 1. **State Management** (`core/state/`)
- **Centralized service**: `StateService` manages all application state
- **Simple setters/getters**: Direct state updates via service methods
- **Separation of concerns**: Different state types are clearly organized

**State Service Properties:**
- Oracle/PostgreSQL schema lists
- Oracle/PostgreSQL table metadata
- Oracle/PostgreSQL object type metadata
- Oracle/PostgreSQL row count data
- Oracle synonyms (dual-map structure for efficient resolution)
- Creation results (schemas, tables, object types)

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
- `ObjectDataTypeMetaData`, `ObjectDataTypeVariable`: Data models with proper constructors
- `OracleObjectDataTypeExtractionJob`, `PostgresObjectDataTypeExtractionJob`: CDI-managed extraction jobs
- `PostgresObjectTypeCreationJob`: Creates PostgreSQL composite types with dependency ordering
- `TypeDependencyAnalyzer`: Topological sort with circular dependency detection
- Service classes for REST endpoint compatibility

**Synonyms** (`core/job/model/synonym/`):
- `SynonymMetadata`: Oracle synonym data model
- `OracleSynonymExtractionJob`: Extracts Oracle synonyms (private and PUBLIC)
- Synonym resolution follows Oracle rules: current schema → PUBLIC fallback
- Used during object type creation to resolve type references

**Schemas** (`schema/`):
- Schema discovery and management services
- `PostgresSchemaCreationJob`: Creates schemas in PostgreSQL
- REST endpoints for schema information

**Data Transfer** (`transfer/`):
- `DataTransferJob`: Main data transfer orchestration job
- `CsvDataTransferService`: High-performance CSV-based data transfer using PostgreSQL COPY
- `OracleComplexTypeSerializer`: Serialization for complex Oracle types (ANYDATA, BLOB/CLOB, user-defined types)
- `RowCountService`: Row count comparison for transfer validation
- Supports all data types: simple types, LOBs, user-defined object types, complex system types
- Piped streaming architecture (producer-consumer) for memory-efficient transfer
- Automatic table truncation and row count validation

#### 4. **Cross-Cutting Concerns** (`core/`)
- `TypeConverter`: Oracle-to-PostgreSQL data type mapping
- `OracleTypeClassifier`: Identifies complex Oracle system types requiring jsonb
- `PostgreSqlIdentifierUtils`: PostgreSQL naming conventions
- `UserExcluder`: Schema filtering logic
- `NameNormalizer`, `CodeCleaner`: Data processing utilities
- `StateService.resolveSynonym()`: Oracle synonym resolution logic

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

### 2. **Direct State Access**
- Jobs update state directly via `StateService`
- Simple and straightforward data flow
- Clear ownership of state updates

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
- Dependency injection: `@Inject` for service dependencies
- Service-based state management

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

### 2. Create Extraction Jobs
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
        stateService.setOracleRowCountMetadata(results);
    }

    @Override
    protected List<RowCountMetadata> performExtraction(Consumer<JobProgress> progressCallback) {
        // Implementation
    }
}
```

### 3. Done!
- Jobs are automatically discovered by `JobRegistry`
- REST endpoints work immediately: `POST /api/jobs/oracle/row-count/extract`
- State is updated directly in `StateService`
- Progress tracking and error handling included

## Current Implementation Status

### ✅ Completed Features
- Centralized state management via StateService
- Plugin-based job discovery and execution
- Schema discovery and creation (Oracle → PostgreSQL)
- Table metadata extraction (Oracle ↔ PostgreSQL)
- Table creation in PostgreSQL (without constraints)
- Object type metadata extraction (Oracle ↔ PostgreSQL)
- Object type creation in PostgreSQL with dependency ordering
- Synonym extraction and resolution (Oracle)
- Row count extraction (Oracle ↔ PostgreSQL)
- **Data transfer (Oracle → PostgreSQL)** - Full implementation with:
  - CSV-based bulk transfer using PostgreSQL COPY
  - Support for all data types (simple, LOB, user-defined objects, complex system types)
  - BLOB/CLOB streaming with hex encoding
  - User-defined object type serialization to PostgreSQL composite format
  - Complex Oracle system type serialization to jsonb with metadata preservation
  - Memory-efficient piped streaming (producer-consumer architecture)
  - Automatic row count validation and table truncation
- Generic REST API for job management
- Database connection management and testing
- Configuration management with UI

### 🔄 Ready for Implementation
- **Sequence extraction and creation (Oracle → PostgreSQL)** ⬅️ Next priority
- Constraint migration (primary keys, foreign keys, unique, check)
- View metadata processing
- Index metadata extraction
- Trigger migration

### 🎯 Extension Points
- New job types: Implement `DatabaseExtractionJob<T>`
- State management: Add new properties to `StateService`
- REST endpoints: Leverage existing generic job endpoints
- Custom processing: Extend job classes for specialized logic

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
- **State management**: Jobs update StateService directly via injected dependency

### Testing Strategy
- **Framework**: JUnit 5 with Mockito
- **Focus**: Test job logic, state management, data extraction
- **Integration**: Test complete job execution flows
- **Mocking**: Mock database connections and state service for unit tests

### Performance Considerations
- **State management**: Simple in-memory storage via StateService
- **Job execution**: Asynchronous with CompletableFuture
- **Memory**: Efficient data structures for metadata storage
- **Connection pooling**: Managed by Quarkus datasource configuration

## Complex Oracle Type Handling Strategy

### Overview
The migration handles three categories of Oracle data types with different strategies:

1. **Built-in Oracle types** → Direct PostgreSQL type mapping via `TypeConverter`
2. **User-defined object types** → PostgreSQL composite types (created via `PostgresObjectTypeCreationJob`)
3. **Complex Oracle system types** → jsonb serialization with type metadata preservation

### Type Categories and Handling

#### 1. User-Defined Object Types (Composite Types)
**Oracle Examples:** Custom types created by users (e.g., `ADDRESS_TYPE`, `CUSTOMER_TYPE`)

**Strategy:**
- **Step A (Table Creation)**: Use PostgreSQL composite type `schema.typename`
- **Step B (Data Transfer)**: Direct structural mapping (Oracle object → PostgreSQL composite)
- **Foundation**: Object types already extracted and created in PostgreSQL with correct dependency order

**Implementation:** `PostgresTableCreationJob.generateColumnDefinition()` lines 237-250

#### 2. Complex Oracle System Types (jsonb Serialization)
**Oracle Examples:**
- `SYS.ANYDATA`, `SYS.ANYTYPE` - Dynamic types
- `SYS.AQ$_*` - Advanced Queuing types (e.g., `AQ$_JMS_TEXT_MESSAGE`)
- `SYS.XMLTYPE` - XML data type
- `SYS.SDO_GEOMETRY` - Spatial/geometry types
- **Note:** These types may appear with owner `"SYS"` or `"PUBLIC"` (due to Oracle PUBLIC synonyms/grants)

**Strategy:**
- **Step A (Table Creation)**: Create as `jsonb` column in PostgreSQL
- **Step B (Data Transfer)**: Serialize to JSON with type metadata wrapper:
  ```json
  {
    "oracleType": "SYS.ANYDATA",
    "value": { /* actual serialized data */ }
  }
  ```

**Benefits:**
- ✅ Preserves original Oracle type name for future PL/SQL code conversion
- ✅ Enables CSV batch transfer (jsonb serialization maintains performance)
- ✅ PostgreSQL jsonb operators allow type-aware queries
- ✅ Debuggable and inspectable data

**Implementation:**
- Table Creation: `PostgresTableCreationJob.isComplexOracleSystemType()` lines 284-309
  - Detects both `owner="sys"` and `owner="public"` (PUBLIC synonyms for SYS types)
- Extraction: `OracleTableExtractor.fetchTableMetadata()` lines 95-102
  - Preserves all type owner information (including SYS/PUBLIC)
- Data Transfer: **TODO - To be implemented in Step B**

#### 3. Built-in Oracle Types
**Oracle Examples:** `NUMBER`, `VARCHAR2`, `DATE`, `TIMESTAMP`, etc.

**Strategy:**
- **Step A (Table Creation)**: Use `TypeConverter.toPostgre()` for direct mapping
- **Step B (Data Transfer)**: Direct conversion using standard JDBC type mapping

**Implementation:** `TypeConverter.toPostgre()` in `core/tools/TypeConverter.java`

### Migration Process (Three-Step Strategy)

**Step A: Table Creation (✅ Implemented)**
- Create tables WITHOUT constraints
- Apply type mapping strategy:
  - User-defined types → `schema.typename` (composite types)
  - Complex Oracle system types → `jsonb`
  - Built-in types → Direct mapping via `TypeConverter`

**Step B: Data Transfer (✅ Implemented)**
- User-defined types: PostgreSQL composite literal format serialization (`CsvDataTransferService.serializeToPostgresRow()`)
- Complex Oracle system types: JSON serialization with metadata wrapper to jsonb (`OracleComplexTypeSerializer.serializeToJson()`)
- Built-in types: Direct JDBC conversion via `getString()`
- LOB types: BLOB→hex encoding, CLOB→text extraction (`OracleComplexTypeSerializer.serializeBlobToHex/serializeClobToText()`)
- PostgreSQL COPY for batch performance (CSV format with piped streaming)

**Step C: Constraint Creation (🔄 To Be Implemented)**
- Add primary keys, foreign keys, unique constraints
- Add check constraints
- Executed after data transfer to avoid constraint violation issues

### Code References

**Table Creation Type Handling:**
- `PostgresTableCreationJob.generateColumnDefinition()` - Main type mapping logic
- `PostgresTableCreationJob.isComplexOracleSystemType()` - Identifies system types requiring jsonb

**Type Conversion:**
- `TypeConverter.toPostgre()` - Built-in type mapping

**Data Transfer Implementation:**
- `CsvDataTransferService.performCsvTransfer()` - Main transfer orchestration
- `CsvDataTransferService.produceOracleCsv()` - Extracts Oracle data to CSV format
- `OracleComplexTypeSerializer.serializeToPostgresRow()` - User-defined object serialization
- `OracleComplexTypeSerializer.serializeToJson()` - Complex system type serialization to jsonb
- `OracleComplexTypeSerializer.serializeBlobToHex()` / `serializeClobToText()` - LOB handling
- Serialization format for complex types:
  ```json
  {
    "oracleType": "<owner>.<type_name>",
    "value": <serialized_data>
  }
  ```
- Preserves type metadata for future PL/SQL code transformation

This architecture provides a solid foundation for Oracle-to-PostgreSQL migration with excellent extensibility for future enhancements.

## Synonym Resolution and Object Type Dependencies

### Overview
Oracle synonyms provide alternative names for database objects. While PostgreSQL doesn't have synonyms, Oracle object types can reference other types via synonyms, requiring resolution during migration.

### Synonym Resolution Strategy

**Extraction:**
- `OracleSynonymExtractionJob` extracts all synonyms from `ALL_SYNONYMS`
- Captures both private (schema-specific) and PUBLIC synonyms
- Stores in dual-map structure: `Map<owner, Map<synonym_name, SynonymMetadata>>`

**Resolution Logic (`StateService.resolveSynonym()`):**
Follows Oracle's name resolution rules:
1. Check for synonym in the current schema
2. If not found, check PUBLIC schema
3. If not found, return null (not a synonym)

**Example:**
```sql
-- Oracle setup
CREATE TYPE schema_a.address_type AS OBJECT (street VARCHAR2(100));
CREATE SYNONYM schema_b.addr_syn FOR schema_a.address_type;

CREATE TYPE schema_b.person_type AS OBJECT (
    name VARCHAR2(100),
    address addr_syn  -- Synonym reference
);
```

When extracting `schema_b.person_type`, Oracle stores:
- `attr_type_owner` = "schema_b"
- `attr_type_name` = "addr_syn"

Our resolution resolves this to `schema_a.address_type`.

### Object Type Creation with Synonym Resolution

**Preprocessing/Normalization:**
Before creating PostgreSQL composite types, `PostgresObjectTypeCreationJob` normalizes object type metadata:

1. **Normalize Object Types:** For each type variable that references a custom type, resolve synonyms
2. **Dependency Analysis:** Use normalized metadata for topological sorting
3. **Type Creation:** Generate SQL with resolved type references

**Benefits:**
- Synonym resolution happens once per type reference (not multiple times)
- Dependency analyzer sees true dependencies (not synonym references)
- PostgreSQL types reference actual target types
- Clean separation of concerns

**Implementation:**
- `PostgresObjectTypeCreationJob.normalizeObjectTypes()` (lines 183-288): Preprocessing step
- `StateService.resolveSynonym()` (lines 101-126): Resolution logic
- `TypeDependencyAnalyzer`: Analyzes normalized metadata for correct ordering

**Important Note:**
Synonyms are only relevant for object type attributes. Table columns in Oracle cannot use synonyms - Oracle always stores the actual type name and owner in table metadata.