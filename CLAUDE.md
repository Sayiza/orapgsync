# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Oracle-to-PostgreSQL migration tool built with Quarkus (Java 18). Uses CDI-based plugin architecture for extensible database object migration with centralized state management.

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
- Oracle/PostgreSQL table metadata (includes constraint metadata)
- Oracle/PostgreSQL object type metadata
- Oracle/PostgreSQL sequence metadata
- Oracle/PostgreSQL row count data
- Oracle synonyms (dual-map structure for efficient resolution)
- Creation results (schemas, tables, object types, sequences, constraints)
- Data transfer results

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
- Note: Constraint metadata is extracted as part of table metadata

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

**Sequences** (`sequence/`):
- `SequenceMetadata`: Data model for Oracle/PostgreSQL sequences
- `OracleSequenceExtractionJob`, `PostgresSequenceExtractionJob`: Extract sequence definitions
- `PostgresSequenceCreationJob`: Creates PostgreSQL sequences from Oracle metadata
- `OracleSequenceExtractor`, `PostgresSequenceExtractor`: Database-specific extraction logic
- `SequenceCreationResult`: Tracks created sequences and errors

**Constraints** (`constraint/`):
- Constraint metadata already extracted as part of `TableMetadata` (via `ConstraintMetadata`)
- `PostgresConstraintCreationJob`: Creates constraints in PostgreSQL in dependency order (PK → UK → FK → CHECK)
- `OracleConstraintSourceStateJob`: Display-only job that aggregates constraints from table metadata
- `PostgresConstraintVerificationJob`: Verifies constraint creation results
- `ConstraintDependencyAnalyzer`: Topological sort for foreign key dependencies
- `ConstraintCreationResult`: Tracks created/skipped/failed constraints

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

**Views** (`viewdefinition/`):
- `ViewDefinitionMetadata`: Data model for view structure (columns only, not SQL definition)
- `OracleViewDefinitionExtractionJob`: Extracts Oracle view column metadata from ALL_TAB_COLUMNS
- `PostgresViewDefinitionExtractionJob`: Extracts PostgreSQL view column metadata
- `PostgresViewStubCreationJob`: Creates PostgreSQL view stubs (empty result set views)
- `ViewStubCreationResult`: Tracks created/skipped/failed view stubs
- View stubs enable dependency resolution for functions/procedures before full view migration

**Functions and Procedures** (`function/`):
- `FunctionMetadata`: Data model for functions and procedures (standalone and package members)
- `FunctionParameter`: Parameter metadata with IN/OUT/INOUT modes and custom type support
- `OracleFunctionExtractionJob`: Extracts Oracle function/procedure signatures from ALL_ARGUMENTS
- `PostgresFunctionStubCreationJob`: Creates PostgreSQL function/procedure stubs (empty implementations)
- `PostgresFunctionStubVerificationJob`: Verifies created function/procedure stubs
- `FunctionStubCreationResult`: Tracks created/skipped/failed function/procedure stubs
- `OracleFunctionExtractor`: Database-specific extraction logic for functions/procedures
- Naming convention: Package members use `packagename__functionname` (double underscore)
- Function stubs return NULL, procedure stubs have empty body with comments indicating original Oracle location

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

## Migration Status

### ✅ Phase 1: Foundational Objects (Completed)
1. **Schemas**: Creation and discovery
2. **Object Types**: Composite type creation with dependency ordering and synonym resolution
3. **Sequences**: Full extraction and creation with all Oracle properties
4. **Tables**: Structure creation (columns with type mapping, NOT NULL constraints)
5. **Data Transfer**: CSV-based bulk transfer via PostgreSQL COPY
   - User-defined types → PostgreSQL composite format
   - Complex Oracle system types (ANYDATA, XMLTYPE, AQ$_*, etc.) → jsonb with metadata
   - BLOB/CLOB → hex/text encoding
   - Piped streaming for memory efficiency
6. **Constraints**: Dependency-ordered creation (PK → UK → FK → CHECK)

### ✅ Phase 2: Structural Stubs (Completed)
7. **View Stubs**: Empty result set views with correct column structure (`WHERE false` pattern)
8. **Function/Procedure Stubs**: Signatures with empty implementations (return NULL / empty body)
   - Package members flattened: `packagename__functionname`

### 🔄 Phase 3: Full Implementation (Future)
9. **View SQL**: Replace stubs with actual Oracle→PostgreSQL SQL conversion
10. **Function/Procedure Logic**: PL/SQL→PL/pgSQL conversion using ANTLR
11. **Type Methods**: Member functions/procedures extraction and stub creation
12. **Triggers**: Migration from Oracle to PostgreSQL
13. **Indexes**: Extraction and creation

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

## Naming Conventions and Module Organization

### Two-Phase Migration Strategy

The application implements a **two-phase migration strategy** for complex database objects (views, functions/procedures, type methods):

1. **Phase 1: Stub Creation** - Create structural placeholders with correct signatures but empty implementations
2. **Phase 2: Full Implementation** (Future) - Replace stubs with actual SQL/PL/pgSQL logic using ANTLR parsing

This two-phase approach is reflected in all naming conventions to maintain clarity and prevent confusion.

### Module and Package Naming

**Standard Pattern:**
```
{feature}/                          # Top-level feature package (e.g., view/, function/, typemethod/)
├── job/                           # Job implementations
│   ├── Oracle{Feature}ExtractionJob.java
│   ├── Postgres{Feature}StubCreationJob.java
│   ├── Postgres{Feature}StubVerificationJob.java
│   └── (future) Postgres{Feature}ImplementationJob.java
├── service/                       # Helper services (if needed)
│   └── Oracle{Feature}Extractor.java
└── (stored in core/job/model/{feature}/)
    ├── {Feature}Metadata.java     # Data model
    ├── {Feature}StubCreationResult.java
    └── (future) {Feature}ImplementationResult.java
```

**Examples:**
- `view/` (not `viewdefinition/`) - Shorter, phase-neutral name
- `function/` - Generic name covers both stubs and future implementations
- `typemethod/` - Consistent with `function/`

### ExtractionType Constants

Job classes must return phase-explicit extraction type strings:

```java
// Extraction jobs (Oracle source)
"VIEW"                              // OracleViewExtractionJob
"FUNCTION"                          // OracleFunctionExtractionJob
"TYPE_METHOD"                       // OracleTypeMethodExtractionJob

// Stub creation jobs (PostgreSQL target)
"VIEW_STUB_CREATION"                // PostgresViewStubCreationJob
"FUNCTION_STUB_CREATION"            // PostgresFunctionStubCreationJob
"TYPE_METHOD_STUB_CREATION"         // PostgresTypeMethodStubCreationJob

// Verification jobs (PostgreSQL target)
"VIEW_STUB_VERIFICATION"            // PostgresViewStubVerificationJob
"FUNCTION_STUB_VERIFICATION"        // PostgresFunctionStubVerificationJob
"TYPE_METHOD_STUB_VERIFICATION"     // PostgresTypeMethodStubVerificationJob

// Future: Full implementation jobs
"VIEW_IMPLEMENTATION"               // (future) PostgresViewImplementationJob
"FUNCTION_IMPLEMENTATION"           // (future) PostgresFunctionImplementationJob
"TYPE_METHOD_IMPLEMENTATION"        // (future) PostgresTypeMethodImplementationJob
```

### REST API Endpoint Patterns

**Standard Pattern:** `/api/jobs/{database}/{feature}-{phase}/{action}`

Use **dash-separated naming** consistently (not underscores):

```
# Oracle Extraction (Phase-neutral, extracts metadata)
POST /api/jobs/oracle/view/extract
POST /api/jobs/oracle/function/extract
POST /api/jobs/oracle/type-method/extract

# PostgreSQL Stub Creation (Phase 1)
POST /api/jobs/postgres/view-stub/create
POST /api/jobs/postgres/function-stub/create
POST /api/jobs/postgres/type-method-stub/create

# PostgreSQL Stub Verification (Phase 1)
POST /api/jobs/postgres/view-stub-verification/verify
POST /api/jobs/postgres/function-stub-verification/verify
POST /api/jobs/postgres/type-method-stub-verification/verify

# Future: PostgreSQL Full Implementation (Phase 2)
POST /api/jobs/postgres/view-implementation/create
POST /api/jobs/postgres/function-implementation/create
POST /api/jobs/postgres/type-method-implementation/create
```

**Benefits:**
- ✅ Self-documenting: URL clearly indicates what phase is being executed
- ✅ Consistent: All endpoints follow same pattern with dashes
- ✅ Extensible: Adding Phase 2 implementation doesn't conflict with Phase 1 stubs
- ✅ Clear: No ambiguity between stub and implementation operations

### Frontend Naming

**Service Files:** (dash-separated, singular)
```
view-service.js
function-service.js
type-method-service.js
```

**HTML Element IDs:** (dash-separated, plurals for collections)
```
oracle-views, postgres-views
oracle-functions, postgres-functions
oracle-type-methods, postgres-type-methods
```

**JavaScript Functions:** (camelCase with phase explicit)
```javascript
// Extraction
extractOracleViews()
extractOracleFunctions()
extractOracleTypeMethods()

// Stub Creation (Phase 1)
createPostgresViewStubs()
createPostgresFunctionStubs()
createPostgresTypeMethodStubs()

// Future: Full Implementation (Phase 2)
createPostgresViewImplementations()
createPostgresFunctionImplementations()
createPostgresTypeMethodImplementations()
```

### StateService Property Naming

**Pattern:** `{database}{Feature}Metadata` and `{feature}StubCreationResult`

```java
// View state
List<ViewMetadata> oracleViewMetadata;
List<ViewMetadata> postgresViewMetadata;
ViewStubCreationResult viewStubCreationResult;
// (future) ViewImplementationResult viewImplementationResult;

// Function state
List<FunctionMetadata> oracleFunctionMetadata;
List<FunctionMetadata> postgresFunctionMetadata;
FunctionStubCreationResult functionStubCreationResult;
// (future) FunctionImplementationResult functionImplementationResult;

// Type Method state
List<TypeMethodMetadata> oracleTypeMethodMetadata;
List<TypeMethodMetadata> postgresTypeMethodMetadata;
TypeMethodStubCreationResult typeMethodStubCreationResult;
// (future) TypeMethodImplementationResult typeMethodImplementationResult;
```

**Note:** Use `ViewMetadata` (not `ViewDefinitionMetadata`) - "definition" is implied, keeping names shorter and more consistent.

### Class Naming Patterns

```java
// Data Models
{Feature}Metadata.java              // ViewMetadata, FunctionMetadata, TypeMethodMetadata
{Feature}Parameter.java             // (if applicable) FunctionParameter, TypeMethodParameter
{Feature}StubCreationResult.java    // ViewStubCreationResult, FunctionStubCreationResult

// Jobs
Oracle{Feature}ExtractionJob.java   // OracleViewExtractionJob
Postgres{Feature}StubCreationJob.java
Postgres{Feature}StubVerificationJob.java
// (future) Postgres{Feature}ImplementationJob.java

// Services (if needed)
Oracle{Feature}Extractor.java       // OracleFunctionExtractor, OracleTypeMethodExtractor
```

### Migration Workflow Clarity

The naming conventions ensure the migration workflow is self-documenting:

```
1. Extract Oracle {feature} metadata        → OracleViewExtractionJob
2. Create PostgreSQL {feature} stubs        → PostgresViewStubCreationJob
3. Verify PostgreSQL {feature} stubs        → PostgresViewStubVerificationJob
4. (Future) Create {feature} implementation → PostgresViewImplementationJob
```

Each step is unambiguous and the progression from stub to implementation is clear.

### Recent Refactoring: Naming Standardization (Completed)

**Status:** ✅ Completed

**Standardized naming across all modules:**
- Module renaming: `viewdefinition/` → `view/`
- Class renaming: `ViewDefinitionMetadata` → `ViewMetadata`
- ExtractionType constants: `"VIEW_STUB_VERIFICATION"`, `"FUNCTION_STUB_VERIFICATION"`, etc.
- API endpoints: Consistent pattern `/api/jobs/{database}/{feature}-{phase}/{action}`
- Frontend: CSS selectors, toggle functions, API fetch URLs all standardized

## Development Guidelines

### Code Organization
- **Domain modules**: Independent, only depend on `core/`, `database/`, `config/`
- **Pure data models**: No service dependencies in model classes
- **CDI annotations**: Use `@ApplicationScoped` for services, `@Dependent` for jobs
- **State management**: Jobs update StateService directly via injected dependency
- **Naming**: Follow the two-phase naming conventions documented above

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

## Type Mapping Strategy

### Three Data Type Categories

1. **Built-in Oracle Types**: Direct mapping via `TypeConverter.toPostgre()`
   - `NUMBER` → `numeric`, `VARCHAR2` → `text`, `DATE` → `timestamp`, etc.

2. **User-Defined Object Types**: PostgreSQL composite types
   - Oracle `HR.ADDRESS_TYPE` → PostgreSQL `hr.address_type`
   - Serialized to PostgreSQL composite literal format during data transfer

3. **Complex Oracle System Types**: jsonb with metadata wrapper
   - `SYS.ANYDATA`, `SYS.XMLTYPE`, `SYS.AQ$_*`, `SYS.SDO_GEOMETRY`, etc.
   - JSON format: `{"oracleType": "SYS.ANYDATA", "value": {...}}`
   - Preserves type information for future PL/SQL code transformation
   - Note: May appear as owner `"SYS"` or `"PUBLIC"` (PUBLIC synonyms)

### Key Implementation Classes
- `TypeConverter.toPostgre()` - Built-in type mapping
- `PostgresTableCreationJob.isComplexOracleSystemType()` - Detects complex system types
- `OracleComplexTypeSerializer` - Handles all complex type serialization (objects, LOBs, system types)

## Synonym Resolution

Oracle synonyms provide alternative names. PostgreSQL doesn't have synonyms, so they must be resolved during migration.

**Resolution Logic (`StateService.resolveSynonym()`):**
1. Check current schema for synonym
2. Fall back to PUBLIC schema
3. Return null if not found

**Usage:**
- `PostgresObjectTypeCreationJob.normalizeObjectTypes()` - Resolves synonyms before creating composite types
- `TypeDependencyAnalyzer` - Uses normalized metadata for dependency ordering
- Note: Only relevant for object type attributes (table columns already store actual type names)

## Stub Implementation Strategy

**Purpose:** Create structural placeholders before implementing full logic to resolve circular dependencies.

**Why Stubs?**
- Enables functions to reference views and vice versa
- Avoids "object does not exist" errors
- Separates automated structural migration from manual logic conversion

### View Stubs

**Pattern:** `SELECT NULL::type AS col1, ... WHERE false`

- Extracts column metadata from Oracle `ALL_TAB_COLUMNS`
- Applies same type mapping as tables
- Empty result set enables function/procedure references

**Jobs:**
- `OracleViewExtractionJob` - Extract Oracle view column metadata
- `PostgresViewStubCreationJob` - Create PostgreSQL view stubs
- `PostgresViewStubVerificationJob` - Verify created stubs

### Function/Procedure Stubs

**Pattern:** Correct signature with empty implementation (return NULL / empty body)

- Extracts signatures from Oracle `ALL_ARGUMENTS`
- Package members flattened: `packagename__functionname` (double underscore)
- Handles IN/OUT/INOUT parameter modes
- Same type mapping as tables

**Jobs:**
- `OracleFunctionExtractionJob` - Extract Oracle function/procedure signatures
- `PostgresFunctionStubCreationJob` - Create PostgreSQL stubs
- `PostgresFunctionStubVerificationJob` - Verify created stubs

### Type Method Stubs (Future)

**Pattern:** Similar to function stubs but for object type member methods

**Planned Jobs:**
- `OracleTypeMethodExtractionJob` - Extract from `ALL_TYPE_METHODS` and `ALL_METHOD_RESULTS`
- `PostgresTypeMethodStubCreationJob` - Create method stubs
- `PostgresTypeMethodStubVerificationJob` - Verify created stubs