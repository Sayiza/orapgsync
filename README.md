# Oracle to PostgreSQL Synchronization and Migration Tool

An enterprise-grade Oracle-to-PostgreSQL migration tool built with Quarkus, featuring event-driven architecture, plugin-based job system, and real-time progress tracking.

## A) Development Plan and Current Status

### 🟢 Completed Features

**Core Infrastructure**
- ✅ **Event-Driven Architecture**: CDI-based events for loose coupling between components
- ✅ **Plugin-Based Job System**: Automatic job discovery and execution via CDI
- ✅ **Thread-Safe State Management**: ReadWriteLock-based state managers for concurrent access
- ✅ **Real-Time Progress Tracking**: WebSocket-style polling with detailed progress updates
- ✅ **Configuration Management**: Runtime configurable database connections with UI

**Database Connectivity**
- ✅ **Oracle Connection Service**: Connection testing, schema discovery, metadata extraction
- ✅ **PostgreSQL Connection Service**: Connection testing, schema creation, data import capabilities
- ✅ **Connection Testing UI**: Real-time connection validation with detailed feedback

**Metadata Extraction**
- ✅ **Schema Discovery**: Extract and display schema lists from both databases
- ✅ **Table Metadata Extraction**: Complete table structure with columns, constraints, data types
- ✅ **Object Data Type Extraction**: Oracle/PostgreSQL custom type discovery with variables
- ✅ **Row Count Analysis**: Precise table row counting for migration planning

**Frontend Interface**
- ✅ **Vanilla JavaScript UI**: No framework dependencies, responsive design
- ✅ **Database Comparison View**: Side-by-side Oracle and PostgreSQL status
- ✅ **Expandable Detail Views**: Schema-grouped tables, object types, and row counts
- ✅ **Progress Indicators**: Real-time job progress with detailed status messages

### 🟡 In Progress / Next Phase

**Data Migration (Priority 1)**
- 🔄 **Bulk Data Transfer**: High-performance data copying between databases
- 🔄 **Type Conversion**: Oracle-to-PostgreSQL data type mapping and conversion
- 🔄 **Constraint Migration**: Primary keys, foreign keys, indexes, and check constraints
- 🔄 **Incremental Sync**: Delta synchronization for ongoing data updates

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
docker rm -f pgtest
docker run --name pgtest -e POSTGRES_PASSWORD=secret -p 5432:5432 -d postgres
```

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

### Event-Driven State Management

**Why Events?**
Traditional direct method calls create tight coupling and make testing difficult:

```java
// Old approach (tightly coupled):
service.updateTableMetadata(tables);  // Direct dependency

// Event approach (loosely coupled):
@Inject Event<TableMetadataUpdatedEvent> event;
event.fire(TableMetadataUpdatedEvent.forOracle(tables));  // Decoupled
```

**Event Architecture Benefits**
- **Loose Coupling**: Components communicate without direct dependencies
- **Extensibility**: New listeners can be added without modifying existing code
- **Testing**: Events can be mocked and verified independently
- **Audit Trail**: All state changes are explicitly tracked through events

**State Management Pattern**
```java
@ApplicationScoped
public class TableMetadataStateManager {

    // Thread-safe storage
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<TableMetadata> oracleTableMetadata = new ArrayList<>();

    // Event-driven updates
    public void onTableMetadataUpdated(@Observes TableMetadataUpdatedEvent event) {
        lock.writeLock().lock();
        try {
            // Update state based on event
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Defensive copying for thread safety
    public List<TableMetadata> getOracleTableMetadata() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(oracleTableMetadata);  // Defensive copy
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

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
- **ReadWriteLocks**: Thread-safe state management
- **Defensive Copying**: Immutable state exposure
- **CDI Events**: Thread-safe event broadcasting

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

2. **Create Event**
   ```java
   public class ViewUpdatedEvent extends DatabaseMetadataUpdatedEvent<ViewMetadata> {
       public static ViewUpdatedEvent forOracle(List<ViewMetadata> views) {
           return new ViewUpdatedEvent("ORACLE", views);
       }
   }
   ```

3. **Create Jobs**
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

4. **Done!**
   - REST endpoint works automatically: `POST /api/jobs/oracle/view/extract`
   - JobRegistry discovers the job via CDI
   - Progress tracking and error handling included
   - Frontend polling works without changes

This architecture provides a solid foundation for Oracle-to-PostgreSQL migration with excellent extensibility for future enhancements.