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
Each database element type is completely independent. Common pattern: `{Element}Metadata` (data model), `Oracle{Element}ExtractionJob` (CDI-managed), `Postgres{Element}CreationJob` (CDI-managed).

**Foundational Objects:**
- **Schemas** - Schema discovery and creation
- **Synonyms** - Oracle synonym resolution (current schema → PUBLIC fallback), used for type resolution
- **Object Types** - Composite types with dependency ordering via `TypeDependencyAnalyzer`
- **Sequences** - Full Oracle property extraction and PostgreSQL creation

**Data Structures:**
- **Tables** - Structure creation with columns and type mapping
- **Constraints** - Dependency-ordered creation (PK → UK → FK → CHECK) via `ConstraintDependencyAnalyzer`
- **Data Transfer** - CSV-based bulk transfer via PostgreSQL COPY, supports all types (LOBs, user-defined, complex system types → jsonb)

**Code Objects (Two-Phase Migration: Stubs → Implementation):**
- **Views** - Stub creation (`WHERE false` pattern), SQL transformation in progress (90% complete, 662+ tests)
- **Functions/Procedures** - Stub creation, PL/SQL transformation 85-95% complete (882 tests), package segmentation optimization active
- **Type Methods** - Stub creation, type method segmentation optimization ✅ complete (ready for Step 26 transformation)
- **Triggers** - Framework complete, ready for extraction and PL/SQL transformation

**Support Layers:**
- **Oracle Compatibility Layer** - PostgreSQL equivalents for Oracle built-in packages (DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB), three-tier support (FULL/PARTIAL/STUB), flattened naming (`oracle_compat.dbms_output__put_line`)

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
9. **Type Method Stubs**: Object type member functions/procedures with empty implementations
   - Pattern: `typename__methodname`

### 🔄 Phase 3: Full Implementation (In Progress)
10. **Oracle Compatibility Layer**: ✅ **COMPLETE** - PostgreSQL equivalents for Oracle built-in packages
    - ✅ Priority packages: DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB
    - ✅ Three-tier support system: FULL, PARTIAL, STUB
    - ✅ Installed into `oracle_compat` schema with flattened naming (e.g., `dbms_output__put_line`)
    - ✅ Extensible catalog system for future additions
    - ✅ Integrated as Step 23/28 in orchestration workflow
    - See `oraclecompat/` module and [TRANSFORMATION.md](documentation/TRANSFORMATION.md) Phase 4.5

11. **View SQL Transformation**: ✅ **90% COMPLETE** - ANTLR-based Oracle→PostgreSQL SQL conversion
    - ✅ **662+ tests passing** across 42+ test classes
    - ✅ **90% real-world view coverage achieved**
    - ✅ Direct AST approach with 37 static visitor helpers
    - ✅ Complete SELECT support, CTEs, CONNECT BY, Oracle-specific functions
    - ✅ PostgresViewImplementationJob with CREATE OR REPLACE VIEW (preserves dependencies)
    - **See [TRANSFORMATION.md](documentation/TRANSFORMATION.md) for detailed feature list and implementation history**

12. **Type Inference System**: 🔄 **42% COMPLETE** - Two-pass type analysis for accurate PL/SQL transformation
    - ✅ Phase 1: Foundation - Literals and simple expressions (18/18 tests)
    - ✅ Phase 2: Column References - Metadata integration with TransformationIndices (13/14 tests)
    - ✅ Phase 3: Built-in Functions - 50+ Oracle functions with polymorphic support (36/36 tests)
    - ✅ **Architecture Refactored** - TypeAnalysisVisitor (498 lines) + 5 static helper classes (following PostgresCodeBuilder pattern)
    - ✅ Token position-based caching for AST node type information
    - ✅ Supports: Literals, operators, date arithmetic, column resolution, function return types, pseudo-columns
    - 📋 Planned: Complex expressions (CASE), PL/SQL variables, collections, integration
    - **Purpose:** Enable accurate ROUND/TRUNC disambiguation, optimal type casting, function overload resolution
    - **Architecture:** TypeAnalysisVisitor (Pass 1) populates type cache → FullTypeEvaluator (Pass 2) queries cache
    - See [TYPE_INFERENCE_IMPLEMENTATION_PLAN.md](documentation/TYPE_INFERENCE_IMPLEMENTATION_PLAN.md) for detailed documentation

13. **Function/Procedure Implementation (Unified)**: 🔄 **85-95% COMPLETE** - PL/SQL→PL/pgSQL transformation
    - ✅ **882 tests passing** (PL/SQL transformation validation tests)
    - ✅ Handles **both standalone and package functions** in single unified job
    - ✅ **20 PL/SQL visitors**: Signatures, variables, control flow (IF/LOOP/FOR/WHILE/CASE), cursors, exceptions, DML
    - ✅ **Critical Fix:** All Oracle PROCEDUREs → PostgreSQL FUNCTIONs with correct RETURNS clause
    - ✅ **Package variables**: On-demand parsing, getter/setter generation (26 tests)
    - ✅ **Package private functions**: Extracted from package bodies via ANTLR parsing
    - ⏳ **Missing (~5-15%)**: BULK COLLECT, OUT/INOUT in call statements, named parameters, collections
    - **REST Endpoints:** `/api/functions/postgres/standalone-implementation/create` + `/verify`
    - **See [STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md](documentation/STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md) for detailed status and feature list**

14. **Type Method Implementation**: ✅ **COMPLETE** - PL/SQL→PL/pgSQL transformation for type methods
    - ✅ **Full implementation**: `PostgresTypeMethodImplementationJob` transforms type methods using ANTLR
    - ✅ **Type method segmentation**: Uses pre-extracted sources from StateService (800x memory reduction)
    - ✅ **Transformation**: Reuses existing `transformFunction`/`transformProcedure` from TransformationService
    - ✅ **Member vs Static**: Handles both member methods (with SELF) and static methods
    - ✅ Frontend integration: UI row, buttons, polling, result display
    - ✅ REST endpoints: `/api/type-methods/postgres/implementation/create`, `/verify`
    - ✅ State management: `TypeMethodImplementationResult`
    - ✅ Integrated as Step 26/28 in orchestration workflow
    - 📋 **Remaining**: Handle SELF parameter transformation (if needed), test with real Oracle types
    - See [TYPE_METHOD_SEGMENTATION_IMPLEMENTATION_PLAN.md](documentation/TYPE_METHOD_SEGMENTATION_IMPLEMENTATION_PLAN.md) for segmentation details

15. **Triggers**: ✅ **FRAMEWORK COMPLETE** - Ready for extraction and transformation
    - ✅ Shell jobs created: `OracleTriggerExtractionJob`, `PostgresTriggerImplementationJob`, `PostgresTriggerVerificationJob`
    - ✅ Frontend integration: UI row, Oracle extraction button, PostgreSQL implementation buttons
    - ✅ REST endpoints: `/api/triggers/oracle/extract`, `/postgres/implementation/create`, `/verify`
    - ✅ State management: `TriggerMetadata`, `TriggerImplementationResult`
    - ✅ Integrated as Steps 27-28/28 in orchestration workflow
    - 📋 **Next:** Extract Oracle trigger metadata, transform PL/SQL trigger bodies, handle :NEW/:OLD → NEW/OLD

16. **Other aspects**: Extraction and creation (Future)

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

### REST API Design Pattern

The application uses a **two-tier REST API architecture**:

1. **Generic Job API** (`/api/jobs/*`) - For standard extraction/creation/verification jobs
   - Pattern: `/api/jobs/{database}/{feature}-{phase}/{action}`
   - Examples:
     - `POST /api/jobs/oracle/view/extract`
     - `POST /api/jobs/postgres/view-stub/create`
     - `POST /api/jobs/postgres/constraint/create`
   - All jobs follow the plugin pattern and are auto-discovered by JobRegistry

2. **Specialized Resource APIs** - For complex operations requiring custom logic
   - Pattern: `/api/{resource}/{database}/{operation}`
   - Examples:
     - `POST /api/oracle-compat/postgres/install` - Install Oracle compatibility layer
     - `POST /api/oracle-compat/postgres/verify` - Verify compatibility layer
     - `POST /api/transformation/sql` - Transform Oracle SQL to PostgreSQL
   - Used when operations don't fit the standard job pattern (e.g., ad-hoc transformations)

**When to Use Each:**
- **Generic Job API**: Standard extraction, creation, verification workflows
- **Specialized API**: Complex orchestration, development tools, or operations requiring special parameters

### API Endpoints

- **Swagger UI**: Available at `/q/swagger-ui` when running
- **Job Management**: `/api/jobs/*` - Generic endpoints for any job type
- **Configuration**: `/api/config/*` - Database connection configuration
- **Status**: Various status endpoints for monitoring extraction progress
- **SQL Transformation**: `/api/transformation/sql` - Transform Oracle SQL to PostgreSQL (development/testing)
- **Oracle Compatibility**: `/api/oracle-compat/*` - Install and verify Oracle compatibility layer

### SQL Transformation REST Endpoint

Direct access to the SQL transformation service for development testing and future dynamic SQL conversion.

**Endpoint:** `POST /api/transformation/sql`

**Purpose:**
- Development testing - quickly test SQL transformations without modifying test files
- Interactive debugging - see transformed SQL immediately
- Future use case - dynamic SQL conversion in PL/pgSQL migration

**Usage Examples:**

```bash
# Transform SQL with default schema (first in state)
curl -X POST "http://localhost:8080/api/transformation/sql" \
  -H "Content-Type: text/plain" \
  --data "SELECT empno, ename FROM emp WHERE dept_id = 10"

# Transform SQL with specific schema
curl -X POST "http://localhost:8080/api/transformation/sql?schema=HR" \
  -H "Content-Type: text/plain" \
  --data "SELECT * FROM employees"

# Transform SQL from file
curl -X POST "http://localhost:8080/api/transformation/sql" \
  -H "Content-Type: text/plain" \
  --data @query.sql
```

**Response Format (JSON):**
```json
{
  "success": true,
  "oracleSql": "SELECT empno FROM emp",
  "postgresSql": "SELECT empno FROM hr.emp",
  "errorMessage": null
}
```

**Notes:**
- Always returns HTTP 200. Check `success` field in response.
- Transformation failure is a valid business outcome, not an HTTP error.
- Requires Oracle metadata to be extracted first (builds indices from StateService).
- Schema defaults to first schema in state if not specified.

## Naming Conventions and Module Organization

### Two-Phase Migration Strategy
Complex database objects (views, functions, type methods) follow a two-phase pattern:
1. **Stub Creation** - Structural placeholders with correct signatures, empty implementations
2. **Implementation** - Replace stubs with actual SQL/PL/pgSQL logic via ANTLR transformation

### Core Naming Patterns

**Package Structure:** `{feature}/` (e.g., `view/`, `function/`, `typemethod/`)
- Jobs: `Oracle{Feature}ExtractionJob`, `Postgres{Feature}StubCreationJob`, `Postgres{Feature}ImplementationJob`
- Models: `{Feature}Metadata`, `{Feature}StubCreationResult`, `{Feature}ImplementationResult`
- Services: `Oracle{Feature}Extractor` (if needed)

**ExtractionType Constants:** Phase-explicit strings
- Extraction: `"VIEW"`, `"FUNCTION"`, `"TYPE_METHOD"`
- Stub Creation: `"VIEW_STUB_CREATION"`, `"FUNCTION_STUB_CREATION"`, etc.
- Verification: `"VIEW_STUB_VERIFICATION"`, `"FUNCTION_STUB_VERIFICATION"`, etc.
- Implementation: `"VIEW_IMPLEMENTATION"`, `"FUNCTION_IMPLEMENTATION"`, etc.

**REST API:** `/api/jobs/{database}/{feature}-{phase}/{action}` (dash-separated)
- Examples: `/api/jobs/oracle/view/extract`, `/api/jobs/postgres/view-stub/create`, `/api/jobs/postgres/view-implementation/create`

**Frontend:** Dash-separated files (`view-service.js`), camelCase functions (`extractOracleViews()`), dash-separated IDs (`oracle-views`)

**StateService:** `{database}{Feature}Metadata` (e.g., `oracleViewMetadata`, `postgresFunctionMetadata`)

### Recent Refactoring

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

### Design Philosophy

**Prefer Rigorous Solutions Over Heuristics:**
- When planning new features, always think about rigorous, deterministic solutions first
- Invest in proper infrastructure (scope tracking, type analysis, metadata indices) rather than quick heuristic shortcuts
- **Example:** Variable scope tracking replaced heuristic variable detection, fixing critical function call misidentification bugs

**Manual Tracking Tools:**
- `TODO.md` is for manual tracking by the user only - **DO NOT update it automatically**

### Documentation Policy

**CRITICAL: Always update documentation when completing implementation steps**

To prevent divergence between code and documentation:

1. **After completing ANY implementation step:**
   - Update CLAUDE.md with a summary of what was implemented
   - Update TRANSFORMATION.md if it's a SQL/PL-SQL transformation feature, or other plan file that is being work with

2. **Document these aspects:**
   - What was implemented (module, classes, key features)
   - REST API endpoints (if any)
   - Integration points (orchestration workflow step number, dependencies)
   - Support levels or limitations (if applicable)

3. **Keep documentation synchronized:**
   - Mark completed phases with ✅ in both CLAUDE.md and TRANSFORMATION.md
   - Update migration status sections
   - Update "Next Steps" or roadmap sections
   - Cross-reference between documentation files

4. **Documentation is part of "done":**
   - An implementation is not complete until documentation is updated
   - Documentation updates should happen in the same session as code completion
   - Review both code and docs together before considering task complete

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

### Code Segmentation Optimizations

**Package Segmentation (2025-11-09):**
- **Problem:** Large Oracle packages (5000+ lines, 100+ functions) caused OutOfMemoryErrors
- **Solution:** Lightweight function boundary scanner (FunctionBoundaryScanner) replaces full ANTLR parse during extraction
- **Benefits:** 800x memory reduction, 42x speedup, 90% real-world coverage
- **Documentation:** [PACKAGE_SEGMENTATION_IMPLEMENTATION_PLAN.md](documentation/PACKAGE_SEGMENTATION_IMPLEMENTATION_PLAN.md)

**Type Method Segmentation (2025-11-11):**
- **Problem:** Private type methods not visible in Oracle data dictionary (ALL_TYPE_METHODS only shows public methods from type spec)
- **Solution:** Lightweight type method boundary scanner (TypeMethodBoundaryScanner) extracts all methods from type bodies
- **Benefits:** Same performance as package segmentation (800x memory, 42x speed), simpler (no variables in type bodies)
- **Status:** ✅ Complete (Phases 1-3) - Ready for use in Step 26
- **Documentation:** [TYPE_METHOD_SEGMENTATION_IMPLEMENTATION_PLAN.md](documentation/TYPE_METHOD_SEGMENTATION_IMPLEMENTATION_PLAN.md)

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
- **Important:** All stubs created as PostgreSQL FUNCTIONs (not PROCEDUREs), even for Oracle procedures
- RETURNS clause automatically calculated:
  - No OUT/INOUT → `RETURNS void`
  - Single OUT/INOUT → `RETURNS type`
  - Multiple OUT/INOUT → `RETURNS RECORD`
- Stub body uses `RETURN;` for procedures, `RETURN NULL;` for functions
- Ensures stubs can be replaced with transformations via `CREATE OR REPLACE` (Phase 1 fix 2025-10-30)

**✅ Package Private Functions (Completed - 2025-11-01):**
- **Problem resolved**: `ALL_PROCEDURES` only shows public functions (in package spec)
- **Solution implemented**: Parse package bodies with ANTLR to extract private functions
- Private functions now marked with `isPackagePrivate = true` in FunctionMetadata
- All package functions (public + private) extracted → stubs created → implementation works
- See STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md for implementation details

**Jobs:**
- `OracleFunctionExtractionJob` - Extract Oracle function/procedure signatures
- `PostgresFunctionStubCreationJob` - Create PostgreSQL stubs (always FUNCTION)
- `PostgresFunctionStubVerificationJob` - Verify created stubs

### Type Method Stubs

**Pattern:** Object type member methods with empty implementations (return NULL / empty body)

- Extracts signatures from Oracle `ALL_TYPE_METHODS` and `ALL_METHOD_RESULTS`
- Flattened naming: `typename__methodname` (double underscore)
- Handles MEMBER (instance) vs STATIC methods
- Same type mapping as tables and functions

**Jobs:**
- `OracleTypeMethodExtractionJob` - Extract Oracle type method signatures
- `PostgresTypeMethodStubCreationJob` - Create PostgreSQL stubs
- `PostgresTypeMethodStubVerificationJob` - Verify created stubs

**Metadata:**
- `TypeMethodMetadata` - Schema, type name, method name, parameters, return type
- Includes `isMemberMethod()`, `isStaticMethod()`, `isFunction()`, `isProcedure()`
- State stored in `StateService.oracleTypeMethodMetadata` and `postgresTypeMethodMetadata`

## SQL/PL-SQL Transformation Module

**Status:** View SQL 90% complete (662+ tests), PL/SQL 85-95% complete (882 tests) ✅

The transformation module converts Oracle SQL and PL/SQL to PostgreSQL using ANTLR-based direct AST transformation.

### Architecture Overview

**Core Pipeline:**
```
Oracle SQL/PL-SQL → ANTLR Parser → PostgresCodeBuilder → PostgreSQL SQL/PL/pgSQL
                        ↓                  ↓                        ↓
                   PlSqlParser    Static Visitor Helpers         String
```

**Key Design Principles:**
- **Direct transformation**: Visitor returns PostgreSQL strings directly (no intermediate semantic tree)
- **Static helper pattern**: Each ANTLR rule has a static helper class
- **Metadata-driven**: Uses pre-built TransformationIndices for O(1) lookups
- **Dependency boundaries**: TransformationContext passed as parameter (not CDI-injected)
- **Quarkus-native**: Service layer uses CDI, visitor layer stays pure

### Metadata Strategy

**Two types of metadata required:**
1. **Synonym resolution** (StateService) - Resolves Oracle synonyms to actual object names
2. **Structural indices** (built from StateService) - Table/Column/Type mappings for disambiguation

**Why metadata is needed:**
- Disambiguate `emp.address.get_street()` (type method) vs `emp_pkg.get_salary()` (package function)
- Both use dot notation in Oracle, different syntax in PostgreSQL

**Benefits:**
- ✅ Clean separation: Parse → Index → Transform
- ✅ Testable with mocked metadata

### Module Location

**Package:** `me.christianrobert.orapgsync.transformation`

**Key Classes:**
- `AntlrParser` - ANTLR wrapper for parsing Oracle SQL/PL-SQL
- `PostgresCodeBuilder` - Main visitor with 57+ static helper classes
- `TransformationContext` - Metadata indices and resolution logic
- `SqlTransformationService` - High-level transformation API

**For detailed implementation status:**
- **SQL Views**: See [TRANSFORMATION.md](documentation/TRANSFORMATION.md)
- **PL/SQL Functions**: See [STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md](documentation/STEP_25_STANDALONE_FUNCTION_IMPLEMENTATION.md)