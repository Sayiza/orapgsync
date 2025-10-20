# Oracle to PostgreSQL Synchronization and Migration Tool

An enterprise-grade Oracle-to-PostgreSQL migration tool built with Quarkus, featuring centralized state management, plugin-based job system, and real-time progress tracking.

## A) Development Plan and Current Status

### 🟢 Completed Features

**Core Infrastructure**
- ✅ **Centralized State Management**: Simple StateService for storing all application metadata
- ✅ **Plugin-Based Job System**: Automatic job discovery and execution via CDI
- ✅ **Real-Time Progress Tracking**: WebSocket-style polling with detailed progress updates
- ✅ **Configuration Management**: Runtime configurable database connections with UI

**Database Connectivity**
- ✅ **Oracle Connection Service**: Connection testing, schema discovery, metadata extraction
- ✅ **PostgreSQL Connection Service**: Connection testing, schema creation, data import capabilities
- ✅ **Connection Testing UI**: Real-time connection validation with detailed feedback

**Metadata Extraction**
- ✅ **Schema Discovery**: Extract and display schema lists from both databases
- ✅ **Schema Creation**: Create PostgreSQL schemas from Oracle schema lists
- ✅ **Table Metadata Extraction**: Complete table structure with columns, constraints, data types
- ✅ **Table Creation**: Create PostgreSQL tables from Oracle metadata (without constraints)
- ✅ **Object Data Type Extraction**: Oracle/PostgreSQL custom type discovery with variables
- ✅ **Object Type Creation**: Create PostgreSQL composite types with dependency ordering
- ✅ **Synonym Extraction**: Extract Oracle synonyms (private and PUBLIC) for type resolution
- ✅ **Sequence Extraction**: Extract Oracle sequences with all properties
- ✅ **Sequence Creation**: Create PostgreSQL sequences from Oracle metadata
- ✅ **Row Count Analysis**: Precise table row counting for migration planning

**Data and Constraint Migration**
- ✅ **Bulk Data Transfer**: High-performance CSV-based data transfer using PostgreSQL COPY
- ✅ **Complex Type Serialization**: BLOB/CLOB, user-defined types, Oracle system types
- ✅ **Constraint Extraction**: Extract constraints as part of table metadata
- ✅ **Constraint Creation**: Create PostgreSQL constraints in dependency order

**Frontend Interface**
- ✅ **Vanilla JavaScript UI**: No framework dependencies, responsive design
- ✅ **Database Comparison View**: Side-by-side Oracle and PostgreSQL status
- ✅ **Expandable Detail Views**: Schema-grouped tables, object types, and row counts
- ✅ **Progress Indicators**: Real-time job progress with detailed status messages

### 🟢 Recently Completed

**Sequence Migration**
- ✅ **Sequence Extraction**: Oracle sequences with all properties (start, increment, min/max, cache, cycle)
- ✅ **Sequence Creation**: PostgreSQL sequences with mapped properties
- ✅ **Error Tracking**: Comprehensive error handling and result tracking

**Constraint Migration**
- ✅ **Constraint Extraction**: Extracted as part of table metadata (PK, FK, UK, CHECK, NOT NULL)
- ✅ **Dependency Ordering**: Topological sort for foreign key dependencies
- ✅ **Constraint Creation**: PostgreSQL constraint creation in dependency order (PK → UK → FK → CHECK)
- ✅ **Duplicate Detection**: Skips already-existing constraints
- ✅ **Error Reporting**: Detailed error tracking for failed constraints

**Complete Data Transfer**
- ✅ **Bulk Data Transfer**: High-performance CSV-based copying using PostgreSQL COPY
- ✅ **Complex Type Handling**: Full serialization of Oracle system types (ANYDATA, XMLTYPE, BLOB, CLOB, user-defined types)
- ✅ **Row Count Validation**: Automatic verification and table truncation

**View Stub Migration**
- ✅ **View Definition Extraction**: Extract Oracle view column metadata from ALL_TAB_COLUMNS
- ✅ **View Stub Creation**: Create PostgreSQL views with correct column structure but empty result sets
- ✅ **Type Mapping**: Support for custom types, XMLTYPE, and complex Oracle system types in views
- ✅ **Empty Result Pattern**: `SELECT NULL::type AS col1, ... WHERE false` ensures structural validity

**Function/Procedure Stub Migration**
- ✅ **Function/Procedure Extraction**: Extract Oracle function/procedure signatures (standalone and package members)
- ✅ **Parameter Metadata**: Extract parameter modes (IN/OUT/INOUT) and custom types
- ✅ **Stub Creation**: Create PostgreSQL functions/procedures with correct signatures but empty implementations
- ✅ **Package Flattening**: Package members use `packagename__functionname` naming convention
- ✅ **Stub Verification**: Verify created function/procedure stubs

### 🟡 In Progress / Next Phase

**Full View and Function Migration (Priority 1)**
- 📋 **View SQL Conversion**: Replace view stubs with actual Oracle SQL → PostgreSQL SQL logic
- 📋 **PL/SQL to PL/pgSQL**: Convert function/procedure implementations using ANTLR parsing
- 📋 **Type Member Functions**: Extract and create object type member functions/procedures
- 📋 **Index Migration**: Extract and create PostgreSQL indexes
- 📋 **Materialized View Support**: Handle materialized views

**Incremental Sync (Priority 2)**
- 📋 **Delta Synchronization**: Ongoing data updates
- 📋 **Change Data Capture**: Track changes for incremental sync

**PL/SQL Migration (Priority 2)**
- 📋 **Stored Procedure Analysis**: ANTLR-based PL/SQL parsing and dependency analysis
- 📋 **PostgreSQL Function Generation**: Automatic conversion to PL/pgSQL
- 📋 **Trigger Migration**: Oracle trigger conversion to PostgreSQL equivalents
- 📋 **Package Decomposition**: Oracle package breakdown into PostgreSQL schemas

**Advanced Features (Priority 3)**
- 📋 **View Migration**: Complex view structures and materialized views
- 📋 **Sequence Migration**: Oracle sequence conversion to PostgreSQL sequences
- 📋 **Permission Migration**: User, role, and privilege synchronization
- 📋 **Performance Optimization**: Query plan analysis and index recommendations

## B) Running the Application

### Prerequisites

- **Java 18** or higher
- **Maven 3.8+**
- **Oracle Database** (accessible via JDBC)
- **PostgreSQL Database** (accessible via JDBC)

### Quick Start

1. **Clone and Build**
   ```bash
   git clone <repository-url>
   cd orapgsync
   mvn clean compile
   ```

2. **Start Development Server**
   ```bash
   mvn quarkus:dev
   ```

   Application will be available at: http://localhost:8080

3. **Configure Database Connections**
   - Open the web interface at http://localhost:8080
   - Configure Oracle connection parameters (URL, username, password)
   - Configure PostgreSQL connection parameters
   - Click "Test" buttons to verify connectivity
   - Save configuration to persist settings

4. **Extract Metadata**
   - Use "↻" buttons to refresh schema lists
   - Use "⚙" buttons to extract table metadata, object types, and row counts
   - Expand detail views to examine extracted data

### Test Database Setup

**PostgreSQL Test Container**
```bash
docker rm -f -v pgtest
docker run --name pgtest -e POSTGRES_PASSWORD=secret -p 5432:5432 -d postgres
```
Optional docker export can be done in this way:
Commit the container to an image
docker commit temp-postgres myapp-db:sprint-23-2025-10-04
Share with team
docker push myapp-db:sprint-23-2025-10-04
From time to time clean up space:
docker volume prune

**Connection Settings**
- Oracle: `jdbc:oracle:thin:@localhost:1521:sid`
- PostgreSQL: `jdbc:postgresql://localhost:5432/postgres`

### Available API Endpoints

**Job Management**
- `POST /api/jobs/tables/oracle/extract` - Extract Oracle table metadata
- `POST /api/jobs/tables/postgres/extract` - Extract PostgreSQL table metadata
- `POST /api/jobs/objects/oracle/extract` - Extract Oracle object types
- `POST /api/jobs/objects/postgres/extract` - Extract PostgreSQL object types
- `POST /api/jobs/oracle/row_count/extract` - Count Oracle table rows
- `POST /api/jobs/postgres/row_count/extract` - Count PostgreSQL table rows
- `GET /api/jobs/{jobId}/status` - Get job progress
- `GET /api/jobs/{jobId}/result` - Get job results

**Configuration**
- `GET /api/config` - Get current configuration
- `POST /api/config` - Update configuration
- `POST /api/config/reset` - Reset to defaults

**Database Testing**
- `GET /api/database/test/oracle` - Test Oracle connection
- `GET /api/database/test/postgres` - Test PostgreSQL connection

Check SQL with:
curl -X POST "http://localhost:8080/api/transformation/sql?schema=HR" \
-H "Content-Type: text/plain" \
--data "SELECT * FROM employees"

## C) Architecture and Technical Design

### Frontend-Driven Architecture

**Why Frontend-Driven?**
The application follows a frontend-driven approach where the web interface orchestrates database operations:

- **User Control**: Migration operations are complex and require human oversight
- **Real-Time Feedback**: Users need immediate visibility into long-running operations
- **Error Handling**: Database issues require user intervention and decision-making
- **Configuration Flexibility**: Connection parameters change frequently during development

**Technology Choices**
- **Vanilla JavaScript**: No framework dependencies, easier deployment and maintenance
- **Server-Sent Events Pattern**: Real-time progress updates via polling (WebSocket alternative)
- **RESTful APIs**: Simple, stateless communication between frontend and backend

### Plugin-Based Job System

**Why Jobs?**
Database extraction operations are inherently long-running and resource-intensive:

```java
// Jobs provide:
// 1. Asynchronous execution
// 2. Progress tracking
// 3. Error isolation
// 4. Resource management
@Dependent
public class OracleTableMetadataExtractionJob extends AbstractDatabaseExtractionJob<TableMetadata> {
    // Automatic discovery via CDI
    // Type-safe result handling
    // Common progress tracking
}
```

**Plugin Architecture Benefits**
- **Automatic Discovery**: New job types are automatically registered via CDI
- **Type Safety**: Generic interfaces ensure compile-time correctness
- **Zero Configuration**: No manual registration or XML configuration needed
- **Extensibility**: Adding new extraction types requires only implementing the interface

**Job Lifecycle**
1. **Discovery**: `JobRegistry` finds all `@Dependent` job implementations
2. **Creation**: REST endpoints trigger job creation via `JobRegistry.createJob()`
3. **Execution**: `JobService` manages async execution with `CompletableFuture`
4. **Progress**: Real-time updates via `JobProgress` callbacks
5. **Completion**: Results saved to state and returned to frontend

### Centralized State Management

**Why Centralized State?**
A single StateService provides simple, straightforward state management:

```java
@ApplicationScoped
public class StateService {
    // Oracle metadata
    List<String> oracleSchemaNames = new ArrayList<>();
    List<TableMetadata> oracleTableMetadata = new ArrayList<>();
    List<ObjectDataTypeMetaData> oracleObjectDataTypeMetaData = new ArrayList<>();
    List<RowCountMetadata> oracleRowCountMetadata = new ArrayList<>();
    Map<String, Map<String, SynonymMetadata>> oracleSynonymsByOwnerAndName = new HashMap<>();

    // PostgreSQL metadata
    List<String> postgresSchemaNames = new ArrayList<>();
    List<TableMetadata> postgresTableMetadata = new ArrayList<>();
    List<ObjectDataTypeMetaData> postgresObjectDataTypeMetaData = new ArrayList<>();
    List<RowCountMetadata> postgresRowCountMetadata = new ArrayList<>();

    // Creation results
    SchemaCreationResult schemaCreationResult;
    TableCreationResult tableCreationResult;
    ObjectTypeCreationResult objectTypeCreationResult;

    // Simple getters and setters
    public void setOracleTableMetadata(List<TableMetadata> metadata) {
        this.oracleTableMetadata = metadata;
    }

    public List<TableMetadata> getOracleTableMetadata() {
        return oracleTableMetadata;
    }
}
```

**State Management Benefits**
- **Simplicity**: Direct access via getters and setters
- **Clarity**: All state in one service, easy to understand
- **Testing**: Easy to mock and verify state changes
- **Performance**: No event overhead, direct updates

### Technology Stack

**Backend Framework**
- **Quarkus 3.15.1**: Native compilation, fast startup, low memory usage
- **CDI**: Dependency injection and event system
- **JAX-RS**: RESTful web services with automatic JSON serialization
- **ANTLR 4.13.2**: PL/SQL parsing for code migration (future use)

**Database Connectivity**
- **Oracle JDBC 23.5.0**: Native Oracle database connectivity
- **PostgreSQL JDBC 42.7.1**: PostgreSQL database operations
- **HikariCP**: Connection pooling (via Quarkus datasources)

**Concurrency and Safety**
- **CompletableFuture**: Asynchronous job execution
- **CDI Scoping**: `@ApplicationScoped` services ensure single instance
- **Simple State**: In-memory lists and objects for metadata storage

### Adding New Database Elements

The architecture makes adding new extraction types trivial:

1. **Create Data Model**
   ```java
   public class ViewMetadata {
       private String schema;
       private String viewName;
       private String definition;
       // getters, constructors
   }
   ```

2. **Create Jobs**
   ```java
   @Dependent
   public class OracleViewExtractionJob extends AbstractDatabaseExtractionJob<ViewMetadata> {
       @Override
       public String getSourceDatabase() { return "ORACLE"; }

       @Override
       public String getExtractionType() { return "VIEW"; }

       @Override
       protected List<ViewMetadata> performExtraction(Consumer<JobProgress> progressCallback) {
           // Implementation
       }

       @Override
       protected void saveResultsToState(List<ViewMetadata> results) {
           stateService.updateOracleViews(results);
       }
   }
   ```

3. **Done!**
   - REST endpoint works automatically: `POST /api/jobs/oracle/view/extract`
   - JobRegistry discovers the job via CDI
   - Progress tracking and error handling included
   - Frontend polling works without changes
   - State updates happen directly in StateService

This architecture provides a solid foundation for Oracle-to-PostgreSQL migration with excellent extensibility for future enhancements.

## D) Stub Implementation Strategy for Dependency Resolution

### Overview

The application implements a **stub-first migration strategy** to handle complex interdependencies between Oracle database objects. This approach creates structural placeholders (stubs) before implementing full logic, enabling incremental and error-free migration.

### Why Stubs?

Oracle database objects often have circular dependencies:
- Functions may reference views
- Views may reference functions
- Both may reference object types
- Package procedures may call other package functions

Creating stubs solves these problems by:
- ✅ **Preventing Errors**: Avoids "object does not exist" errors during creation
- ✅ **Enabling Incremental Migration**: Structure first, logic later
- ✅ **Resolving Circular Dependencies**: All objects exist structurally before implementation
- ✅ **Supporting Testing**: Validate structure before migrating complex PL/SQL logic

### Migration Phases

#### Phase 1: Foundational Objects (✅ Completed)
1. Schemas → Object Types → Sequences → Tables (no constraints)
2. Data Transfer (bulk CSV copy)
3. Constraints (dependency-ordered: PK → UK → FK → CHECK)

#### Phase 2: Structural Stubs (✅ Completed)
4. **View Stubs**: Views with correct columns but empty result sets
5. **Function/Procedure Stubs**: Functions/procedures with correct signatures but empty implementations

#### Phase 3: Full Implementation (🔄 Future)
6. Replace view stubs with actual SQL logic
7. Replace function/procedure stubs with PL/pgSQL logic (ANTLR-based conversion)
8. Type member functions, triggers, packages

### View Stubs

**What they are:**
Views with correct column structure that return empty result sets.

**Example:**
```sql
-- Oracle view (complex logic)
CREATE VIEW hr.employees_v AS
SELECT emp_id, name, address_obj, salary * 1.1 AS bonus
FROM employees
WHERE department_id = 10;

-- PostgreSQL stub (structure only)
CREATE VIEW hr.employees_v AS
SELECT
    NULL::numeric AS emp_id,
    NULL::text AS name,
    NULL::hr.address_type AS address_obj,  -- Composite type
    NULL::numeric AS bonus
WHERE false;  -- Empty result set
```

**Benefits:**
- Functions can reference views without errors
- Column structure is preserved for type checking
- No data returned (safe for testing)

### Function/Procedure Stubs

**What they are:**
Functions and procedures with correct signatures but empty implementations.

**Example:**
```sql
-- Oracle function
CREATE FUNCTION hr.calculate_bonus(emp_id NUMBER) RETURN NUMBER IS
BEGIN
    -- Complex calculation logic
    RETURN emp_id * 0.1;
END;

-- PostgreSQL stub
CREATE FUNCTION hr.calculate_bonus(IN emp_id numeric) RETURNS numeric AS $$
BEGIN
    RETURN NULL;  -- Stub: Original Oracle function HR.CALCULATE_BONUS
END;
$$ LANGUAGE plpgsql;
```

**Package Member Handling:**
```sql
-- Oracle package
CREATE PACKAGE hr.emp_pkg IS
    FUNCTION get_salary(emp_id NUMBER) RETURN NUMBER;
END;

-- PostgreSQL stub (flattened)
CREATE FUNCTION hr.emp_pkg__get_salary(IN emp_id numeric) RETURNS numeric AS $$
BEGIN
    RETURN NULL;  -- Stub: Original Oracle function HR.EMP_PKG.GET_SALARY
END;
$$ LANGUAGE plpgsql;
```

**Benefits:**
- Views can reference functions without errors
- Functions can reference other functions without errors
- Package members are flattened with `packagename__functionname` naming
- Parameter modes (IN/OUT/INOUT) are preserved

### Recommended Migration Workflow

```
1. Extract Oracle metadata (all types)
2. Create PostgreSQL schemas
3. Create object types (with dependency ordering)
4. Create sequences
5. Create tables (without constraints)
6. Create view stubs          ← Enables function references to views
7. Create function stubs      ← Enables view references to functions
8. Transfer data
9. Create constraints
10. (Future) Replace view stubs with actual SQL
11. (Future) Replace function stubs with PL/pgSQL logic
```

### Type Mapping in Stubs

Stubs use the same type mapping as tables:
- **Built-in types**: `NUMBER` → `numeric`, `VARCHAR2` → `text`, etc.
- **User-defined types**: `HR.ADDRESS_TYPE` → `hr.address_type` (composite)
- **Complex system types**: `SYS.ANYDATA`, `SYS.XMLTYPE` → `jsonb`
- **Special case**: `XMLTYPE` → `xml` type in views/functions

### Current Status

✅ **Completed:**
- View stub extraction and creation
- Function/procedure stub extraction and creation
- Package member flattening
- Stub verification jobs

🔄 **Future:**
- Full view SQL conversion (Oracle → PostgreSQL)
- Full PL/SQL to PL/pgSQL conversion (ANTLR-based)
- Type member functions (object type methods)

For detailed implementation information, see `CLAUDE.md` section "Stub Implementation Strategy for Dependency Resolution".