# Oracle to PostgreSQL Migration Tool

An enterprise-grade Oracle-to-PostgreSQL migration tool built with Quarkus. Migrates schemas, tables, data, constraints, views, functions, and more with automated SQL transformation.

**Current Status:** Production-ready for foundational objects (schemas, tables, data, constraints, sequences, view stubs). View SQL transformation at 90% real-world coverage.

For comprehensive documentation, see [CLAUDE.md](CLAUDE.md) and [TRANSFORMATION.md](TRANSFORMATION.md).

---

## Quick Start

### Prerequisites

- **Java 18** or higher
- **Maven 3.8+**
- **Oracle Database** (accessible via JDBC)
- **PostgreSQL Database** (accessible via JDBC)

### Running the Application

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

   Application available at: **http://localhost:8080**

3. **Configure Database Connections**
   - Open the web interface at http://localhost:8080
   - Configure Oracle connection (URL, username, password)
   - Configure PostgreSQL connection
   - Click "Test" buttons to verify connectivity
   - Save configuration

4. **Run Migration**
   - Use individual action buttons to extract/create specific objects
   - Or use **"Start All"** button for full automated workflow
   - Monitor progress in real-time via the UI

---

## Docker Test Databases

### PostgreSQL Test Container

**Start a PostgreSQL test database:**
```bash
docker rm -f -v pgtest
docker run --name pgtest -e POSTGRES_PASSWORD=secret -p 5432:5432 -d postgres
```

**Connection Settings:**
```
JDBC URL: jdbc:postgresql://localhost:5432/postgres
Username: postgres
Password: secret
```

### Docker Image Management (Optional)

**Save container state to image:**
```bash
# 1. Start fresh empty DB — data will be inside the container
# 1. Start empty DB with data stored inside the container (no volume!)
docker rm -f pgtest
docker run --name pgtest -e POSTGRES_PASSWORD=secret -e PGDATA=/var/lib/postgresql/pgdata -p 5432:5432 -d postgres

# ... Run your DDL, insert data, etc. (or your 8-hour job) ...

# 2. Commit the full state (data IS included now)
docker commit pgtest trunkdevpg:2025-11-24

# 3. Later, start from the saved image (data restores instantly)
docker rm -f pgtest
docker run --name pgtest -e POSTGRES_PASSWORD=secret -e PGDATA=/var/lib/postgresql/pgdata -p 5433:5432 -d trunkdevpg:2025-11-30

use the postgres with new profile: mvn quarkus:dev -Dquarkus.profile=postgres
```
# Remove the old container (if running)
docker rm -f pgtest

# Run the container from your saved snapshot
docker run --name pgtest -p 5432:5432 -d trunkdevpg:2025-11-22


**Clean up disk space:**
```bash
docker volume prune
```

---

## Key Features

**Completed:**
- ✅ Schema, table, sequence, object type extraction and creation
- ✅ High-performance CSV-based data transfer (PostgreSQL COPY)
- ✅ Complex type handling (BLOB/CLOB, user-defined types, Oracle system types)
- ✅ Dependency-ordered constraint creation (PK → UK → FK → CHECK)
- ✅ View stub creation (structure placeholders for dependency resolution)
- ✅ Function/procedure stub creation (package flattening)
- ✅ Oracle compatibility layer (DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB)
- ✅ **View SQL transformation** - 90% real-world coverage (ANTLR-based)

**In Progress:**
- 🔄 Complete view SQL migration (replacing stubs with transformed Oracle SQL)
- 🔄 PL/SQL to PL/pgSQL transformation (functions, procedures, type methods)

---

## API Examples

### SQL Transformation (Development/Testing)

Test Oracle SQL transformation without modifying code:

```bash
# Transform with default schema
curl -X POST "http://localhost:8080/api/transformation/sql" \
  -H "Content-Type: text/plain" \
  --data "SELECT empno, ename FROM emp WHERE dept_id = 10"

# Transform with specific schema
curl -X POST "http://localhost:8080/api/transformation/sql?schema=HR" \
  -H "Content-Type: text/plain" \
  --data "SELECT * FROM employees"
```

### Job Management

```bash
# Extract Oracle tables
curl -X POST "http://localhost:8080/api/jobs/oracle/table/extract"

# Get job status
curl "http://localhost:8080/api/jobs/{jobId}/status"
```

**Swagger UI:** http://localhost:8080/q/swagger-ui

---

## Architecture Overview

**Two-Phase Migration Strategy:**
1. **Stub Creation** - Create structural placeholders (views, functions) for dependency resolution
2. **Implementation** - Replace stubs with actual SQL/PL-pgSQL logic using ANTLR transformation

**Key Components:**
- **Plugin-based job system** - Auto-discovered via CDI
- **Centralized state management** - Simple StateService
- **ANTLR-based SQL transformation** - Direct AST approach, 662+ tests
- **REST API** - Generic job endpoints + specialized resources
- **Vanilla JavaScript UI** - No framework dependencies

**See [CLAUDE.md](CLAUDE.md) for comprehensive architecture documentation.**

---

## Documentation

- **[CLAUDE.md](CLAUDE.md)** - Complete architecture, development guide, module documentation
- **[TRANSFORMATION.md](TRANSFORMATION.md)** - SQL/PL-SQL transformation module (ANTLR implementation)
- **[CTE_IMPLEMENTATION_PLAN.md](documentation/completed/CTE_IMPLEMENTATION_PLAN.md)** - CTE (WITH clause) implementation details
- **[CONNECT_BY_IMPLEMENTATION_PLAN.md](CONNECT_BY_IMPLEMENTATION_PLAN.md)** - Hierarchical query transformation

---

## License

[Add your license here]
