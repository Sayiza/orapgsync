# Mod-PL/SQL Web Gateway Implementation Plan

**Created:** 2026-03-29
**Status:** Phase 1 COMPLETE - PostgreSQL Compatibility Layer + Quarkus Gateway Generation
**Priority:** High
**Last Updated:** 2026-03-29

## Overview

This plan describes the implementation of Oracle mod_plsql compatibility, enabling migration of PL/SQL-based web applications to PostgreSQL + Quarkus.

**Two Main Components:**
1. **PostgreSQL Compatibility Layer** - HTP/HTF/OWA functions in `oracle_compat` schema
2. **Quarkus Web Gateway** - Java application that replaces Apache mod_plsql module

**Approach:** Iterative implementation, starting with minimal viable feature set and expanding based on real-world testing.

**Note:** PL/SQL transformation is now **unfrozen** and will continue receiving improvements as needed for this feature.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Request                                │
└─────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Quarkus Web Gateway                             │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────┐    │
│  │ URL Router  │→ │ Context Init │→ │ PostgreSQL Function Call│    │
│  │ /pls/dad/.. │  │ (CGI env,    │  │ (JDBC connection pool)  │    │
│  └─────────────┘  │  params)     │  └─────────────────────────┘    │
│                   └──────────────┘              │                   │
│                                                 ▼                   │
│                              ┌──────────────────────────────────┐   │
│                              │ Extract Buffer & Return Response │   │
│                              └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         PostgreSQL                                  │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    oracle_compat schema                      │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │   │
│  │  │ htp__p()    │  │ htf__*()    │  │ owa_util__*()       │  │   │
│  │  │ htp__print()│  │ htf__bold() │  │ get_cgi_env()       │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │   │
│  │                         │                                    │   │
│  │                         ▼                                    │   │
│  │  ┌───────────────────────────────────────────────────────┐  │   │
│  │  │              temp_htp_buffer (TEMP TABLE)             │  │   │
│  │  │  seq_num SERIAL | content TEXT | content_type TEXT    │  │   │
│  │  └───────────────────────────────────────────────────────┘  │   │
│  │                                                              │   │
│  │  ┌───────────────────────────────────────────────────────┐  │   │
│  │  │              temp_cgi_env (TEMP TABLE)                │  │   │
│  │  │  var_name TEXT PRIMARY KEY | var_value TEXT           │  │   │
│  │  └───────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Component 1: PostgreSQL Compatibility Layer

### Buffer Storage Design

**Decision: Temporary Tables** (over session variables)

**Rationale:**
- Handles large responses without memory concerns
- Survives across function calls within a transaction
- Easy to query and export
- Natural ordering via sequence number
- Can store metadata (headers) separately from body

**Tables:**

```sql
-- Main HTTP output buffer
CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
    seq_num SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ON COMMIT DROP;

-- CGI environment variables (request context)
CREATE TEMP TABLE IF NOT EXISTS temp_cgi_env (
    var_name TEXT PRIMARY KEY,
    var_value TEXT
) ON COMMIT DROP;

-- HTTP headers (for future use)
CREATE TEMP TABLE IF NOT EXISTS temp_http_headers (
    seq_num SERIAL PRIMARY KEY,
    header_name TEXT NOT NULL,
    header_value TEXT NOT NULL
) ON COMMIT DROP;

-- Cookies (for future use)
CREATE TEMP TABLE IF NOT EXISTS temp_owa_cookies (
    cookie_name TEXT PRIMARY KEY,
    cookie_value TEXT,
    expires TIMESTAMP,
    path TEXT,
    domain TEXT,
    secure BOOLEAN DEFAULT FALSE
) ON COMMIT DROP;
```

### Core Functions (Iteration 1)

**Priority:** These functions cover 95%+ of typical usage.

#### Buffer Management

```sql
-- Initialize buffer (called by Quarkus at request start)
CREATE OR REPLACE FUNCTION oracle_compat.htp__init()
RETURNS void AS $$
BEGIN
    -- Create temp tables if not exist (idempotent)
    CREATE TEMP TABLE IF NOT EXISTS temp_htp_buffer (
        seq_num SERIAL PRIMARY KEY,
        content TEXT NOT NULL
    ) ON COMMIT DROP;

    -- Clear any existing content
    TRUNCATE temp_htp_buffer;
END;
$$ LANGUAGE plpgsql;

-- Get buffer contents (called by Quarkus after procedure execution)
CREATE OR REPLACE FUNCTION oracle_compat.htp__get_buffer()
RETURNS TEXT AS $$
DECLARE
    result TEXT := '';
BEGIN
    SELECT string_agg(content, '' ORDER BY seq_num)
    INTO result
    FROM temp_htp_buffer;

    RETURN COALESCE(result, '');
END;
$$ LANGUAGE plpgsql;
```

#### HTP Output Functions

```sql
-- HTP.P - Primary output function (95% of usage)
CREATE OR REPLACE FUNCTION oracle_compat.htp__p(p_text TEXT)
RETURNS void AS $$
BEGIN
    INSERT INTO temp_htp_buffer (content) VALUES (p_text || chr(10));
END;
$$ LANGUAGE plpgsql;

-- HTP.PRN - Output without newline
CREATE OR REPLACE FUNCTION oracle_compat.htp__prn(p_text TEXT)
RETURNS void AS $$
BEGIN
    INSERT INTO temp_htp_buffer (content) VALUES (p_text);
END;
$$ LANGUAGE plpgsql;

-- HTP.PRINT - Alias for HTP.P
CREATE OR REPLACE FUNCTION oracle_compat.htp__print(p_text TEXT)
RETURNS void AS $$
BEGIN
    PERFORM oracle_compat.htp__p(p_text);
END;
$$ LANGUAGE plpgsql;

-- HTP.NL - Newline only
CREATE OR REPLACE FUNCTION oracle_compat.htp__nl()
RETURNS void AS $$
BEGIN
    INSERT INTO temp_htp_buffer (content) VALUES (chr(10));
END;
$$ LANGUAGE plpgsql;
```

#### HTF Functions (Return Strings)

```sql
-- HTF.ESCAPE_SC - Escape special characters
CREATE OR REPLACE FUNCTION oracle_compat.htf__escape_sc(p_text TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
        p_text,
        '&', '&amp;'),
        '<', '&lt;'),
        '>', '&gt;'),
        '"', '&quot;'),
        '''', '&#39;');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.BOLD
CREATE OR REPLACE FUNCTION oracle_compat.htf__bold(p_text TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN '<b>' || COALESCE(p_text, '') || '</b>';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.ITALIC
CREATE OR REPLACE FUNCTION oracle_compat.htf__italic(p_text TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN '<i>' || COALESCE(p_text, '') || '</i>';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.ANCHOR / HTF.ANCHOR2
CREATE OR REPLACE FUNCTION oracle_compat.htf__anchor(
    p_url TEXT,
    p_text TEXT,
    p_name TEXT DEFAULT NULL,
    p_target TEXT DEFAULT NULL
)
RETURNS TEXT AS $$
DECLARE
    result TEXT;
BEGIN
    result := '<a href="' || COALESCE(p_url, '') || '"';
    IF p_name IS NOT NULL THEN
        result := result || ' name="' || p_name || '"';
    END IF;
    IF p_target IS NOT NULL THEN
        result := result || ' target="' || p_target || '"';
    END IF;
    result := result || '>' || COALESCE(p_text, '') || '</a>';
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.IMG
CREATE OR REPLACE FUNCTION oracle_compat.htf__img(
    p_src TEXT,
    p_alt TEXT DEFAULT NULL,
    p_align TEXT DEFAULT NULL,
    p_width TEXT DEFAULT NULL,
    p_height TEXT DEFAULT NULL
)
RETURNS TEXT AS $$
DECLARE
    result TEXT;
BEGIN
    result := '<img src="' || COALESCE(p_src, '') || '"';
    IF p_alt IS NOT NULL THEN
        result := result || ' alt="' || p_alt || '"';
    END IF;
    IF p_align IS NOT NULL THEN
        result := result || ' align="' || p_align || '"';
    END IF;
    IF p_width IS NOT NULL THEN
        result := result || ' width="' || p_width || '"';
    END IF;
    IF p_height IS NOT NULL THEN
        result := result || ' height="' || p_height || '"';
    END IF;
    result := result || '>';
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.BR
CREATE OR REPLACE FUNCTION oracle_compat.htf__br()
RETURNS TEXT AS $$
BEGIN
    RETURN '<br>';
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- HTF.HR
CREATE OR REPLACE FUNCTION oracle_compat.htf__hr()
RETURNS TEXT AS $$
BEGIN
    RETURN '<hr>';
END;
$$ LANGUAGE plpgsql IMMUTABLE;
```

### CGI Environment (Iteration 1)

```sql
-- Initialize CGI environment (called by Quarkus)
CREATE OR REPLACE FUNCTION oracle_compat.owa__init_cgi_env(
    p_env JSONB  -- {"REQUEST_METHOD": "GET", "QUERY_STRING": "...", ...}
)
RETURNS void AS $$
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS temp_cgi_env (
        var_name TEXT PRIMARY KEY,
        var_value TEXT
    ) ON COMMIT DROP;

    TRUNCATE temp_cgi_env;

    INSERT INTO temp_cgi_env (var_name, var_value)
    SELECT key, value::TEXT
    FROM jsonb_each_text(p_env);
END;
$$ LANGUAGE plpgsql;

-- OWA_UTIL.GET_CGI_ENV
CREATE OR REPLACE FUNCTION oracle_compat.owa_util__get_cgi_env(p_name TEXT)
RETURNS TEXT AS $$
DECLARE
    result TEXT;
BEGIN
    SELECT var_value INTO result
    FROM temp_cgi_env
    WHERE var_name = UPPER(p_name);

    RETURN result;
END;
$$ LANGUAGE plpgsql;
```

### Function Catalog Addition

Add to `OracleBuiltinCatalog.java`:

```java
private void registerHtp() {
    // Buffer management
    allFunctions.add(OracleBuiltinFunction.builder()
        .packageName("HTP")
        .functionName("INIT")
        .signature("INIT()")
        .supportLevel(SupportLevel.FULL)
        .postgresFunction("oracle_compat.htp__init")
        .notes("Initialize HTP buffer - called by web gateway at request start")
        .sqlDefinition(HtpImpl.getInit())
        .build());

    // ... more functions
}
```

---

## Component 2: Quarkus Web Gateway

### Project Structure

```
orapgsync-web-gateway/
├── pom.xml
├── src/main/java/
│   └── me/christianrobert/orapgsync/gateway/
│       ├── GatewayApplication.java
│       ├── config/
│       │   └── GatewayConfig.java          # DAD name, URL mappings
│       ├── routing/
│       │   ├── PlsqlRouter.java            # URL → function mapping
│       │   └── ParameterParser.java        # Query/form params
│       ├── execution/
│       │   ├── PlsqlExecutor.java          # JDBC execution
│       │   └── BufferExtractor.java        # Get HTP buffer
│       └── response/
│           └── ResponseBuilder.java        # HTTP response construction
├── src/main/resources/
│   └── application.properties
└── src/test/java/
```

### Configuration

**application.properties:**
```properties
# Database connection
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/mydb
quarkus.datasource.username=${DB_USER}
quarkus.datasource.password=${DB_PASSWORD}

# Connection pool
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20

# Gateway configuration
gateway.dad-name=myapp
gateway.default-schema=hr
gateway.url-prefix=/pls
```

### Core Classes

#### PlsqlRouter.java

```java
@Path("/pls/{dadName}")
@ApplicationScoped
public class PlsqlRouter {

    @Inject
    PlsqlExecutor executor;

    @Inject
    GatewayConfig config;

    /**
     * Route: /pls/{dad}/{schema}.{package}.{procedure}
     * or:    /pls/{dad}/{procedure}
     */
    @GET
    @POST
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    public Response handleRequest(
            @PathParam("dadName") String dadName,
            @PathParam("path") String path,
            @Context HttpServletRequest request) {

        // Parse path to extract schema, package, procedure
        ProcedureCall call = parsePath(path);

        // Extract parameters
        Map<String, String[]> params = extractParameters(request);

        // Build CGI environment
        Map<String, String> cgiEnv = buildCgiEnv(request);

        // Execute and get response
        String html = executor.execute(call, params, cgiEnv);

        return Response.ok(html).build();
    }
}
```

#### PlsqlExecutor.java

```java
@ApplicationScoped
public class PlsqlExecutor {

    @Inject
    AgroalDataSource dataSource;

    public String execute(
            ProcedureCall call,
            Map<String, String[]> params,
            Map<String, String> cgiEnv) {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 1. Initialize HTP buffer
                initBuffer(conn);

                // 2. Set CGI environment
                setCgiEnv(conn, cgiEnv);

                // 3. Call the procedure
                callProcedure(conn, call, params);

                // 4. Extract buffer
                String result = extractBuffer(conn);

                // 5. Commit
                conn.commit();

                return result;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private void initBuffer(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT oracle_compat.htp__init()");
        }
    }

    private void setCgiEnv(Connection conn, Map<String, String> env)
            throws SQLException {
        String json = new ObjectMapper().writeValueAsString(env);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT oracle_compat.owa__init_cgi_env(?::jsonb)")) {
            ps.setString(1, json);
            ps.execute();
        }
    }

    private void callProcedure(
            Connection conn,
            ProcedureCall call,
            Map<String, String[]> params) throws SQLException {

        // Build: SELECT schema.package__procedure(param1, param2, ...)
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(call.getFullyQualifiedName());
        sql.append("(");

        // Add parameters...
        // Handle name_array/value_array for flexible parameters

        sql.append(")");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }
    }

    private String extractBuffer(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT oracle_compat.htp__get_buffer()")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return "";
        }
    }
}
```

---

## Implementation Phases

### Phase 1: Minimal Viable Product (MVP)

**Goal:** Get basic web pages rendering from PostgreSQL.

**PostgreSQL Compatibility Layer:** ✅ **COMPLETE** (2026-03-29)
- [x] `htp__init()` - Initialize buffer (temp table with PRESERVE ROWS)
- [x] `htp__p()` - Primary output (95% of usage)
- [x] `htp__prn()` - Output without newline
- [x] `htp__print()` - Alias for p
- [x] `htp__nl()` - Newline only
- [x] `htp__br()` - Line break tag
- [x] `htp__line()` - Horizontal rule tag
- [x] `htp__get_buffer()` - Extract buffer contents
- [x] `owa__init_cgi_env()` - Set CGI environment from JSON
- [x] `owa__init_request()` - Combined buffer + CGI init
- [x] `owa_util__get_cgi_env()` - Read CGI variable
- [x] `owa_util__http_header_close()` - Close headers
- [x] `owa_util__mime_header()` - Set content type (stub)
- [x] `owa_util__redirect_url()` - Redirect (stub)
- [x] `owa_util__print_cgi_env()` - Debug function

**Implementation Files:**
- `HtpImpl.java` - HTP function SQL definitions
- `OwaImpl.java` - OWA core function SQL definitions
- `OwaUtilImpl.java` - OWA_UTIL function SQL definitions
- `OracleBuiltinCatalog.java` - Function registration

**Test Coverage:** 17 integration tests passing (`PostgresHtpBufferValidationTest`)

**Quarkus Gateway Generation:** ✅ **COMPLETE** (2026-03-29)
- [x] Project generation from templates
- [x] pom.xml with Quarkus dependencies
- [x] application.yaml with database and gateway config
- [x] .env file with database credentials
- [x] GatewayApplication.java - Main entry point
- [x] GatewayConfig.java - Configuration interface
- [x] PlsqlRouter.java - URL routing to PostgreSQL functions
- [x] PlsqlExecutor.java - Buffer init, procedure call, buffer extraction
- [x] Simple URL routing (`/pls/{dad}/{schema}.{procedure}`)
- [x] CGI environment initialization
- [x] Buffer extraction and HTML response
- [x] Redirect support (via session variable)
- [x] Error handling (return error page on exception)

**Generator Implementation:**
- `WebGatewayGenerator.java` - Template processing and file generation
- `WebGatewayGenerationResult.java` - Result model
- `WebGatewayResource.java` - REST endpoints (`POST /api/web-gateway/generate`)
- Templates in `src/main/resources/web-gateway-template/`

**Test Coverage:** 8 unit tests passing (`WebGatewayGeneratorTest`)

**REST API:**
- `POST /api/web-gateway/generate` - Generate gateway project
- `GET /api/web-gateway/config` - Get current config
- `PUT /api/web-gateway/config` - Update config

**Configuration:**
- `web-gateway.output-path` - Output directory (must be empty)
- `web-gateway.dad-name` - DAD name for URL routing
- `web-gateway.url-prefix` - URL prefix (default: /pls)
- `web-gateway.server-port` - HTTP port (default: 8090)
- `java.generated-package-name` - Java package name

**Integration:**
- [x] Add HTP functions to OracleBuiltinCatalog
- [x] Functions auto-installed via PostgresOracleCompatInstallationJob
- [ ] Test with simple "Hello World" procedure (needs generated gateway to be built/run)

**Deliverable:** A simple procedure like this works:
```sql
CREATE FUNCTION hr.hello_world() RETURNS void AS $$
BEGIN
    PERFORM oracle_compat.htp__p('<html><body>');
    PERFORM oracle_compat.htp__p('<h1>Hello World!</h1>');
    PERFORM oracle_compat.htp__p('</body></html>');
END;
$$ LANGUAGE plpgsql;
```

Accessed via: `http://localhost:8080/pls/myapp/hr.hello_world`

---

### Phase 2: HTF Functions & HTML Helpers

**Goal:** Support HTF functions for HTML generation.

**PostgreSQL:**
- [ ] `htf__escape_sc()` - Escape special characters
- [ ] `htf__bold()`, `htf__italic()`
- [ ] `htf__anchor()` - Links
- [ ] `htf__img()` - Images
- [ ] `htf__br()`, `htf__hr()` - Line breaks
- [ ] `htf__para()` - Paragraphs
- [ ] `htp__htmlopen()`, `htp__htmlclose()`
- [ ] `htp__headopen()`, `htp__headclose()`
- [ ] `htp__bodyopen()`, `htp__bodyclose()`
- [ ] `htp__title()`

**Quarkus:**
- [ ] POST parameter handling (form submissions)
- [ ] Content-Type detection (HTML vs other)

---

### Phase 3: Table & Form Support

**Goal:** Support HTML tables and forms.

**PostgreSQL:**
- [ ] `htp__tableopen()`, `htp__tableclose()`
- [ ] `htp__tablerowopen()`, `htp__tablerowclose()`
- [ ] `htp__tabledata()`, `htp__tableheader()`
- [ ] `htp__formopen()`, `htp__formclose()`
- [ ] `htp__formtext()`, `htp__formpassword()`
- [ ] `htp__formsubmit()`, `htp__formreset()`
- [ ] `htp__formhidden()`
- [ ] `htp__formselectopen()`, `htp__formselectclose()`
- [ ] `htp__formselectoption()`
- [ ] `htp__formcheckbox()`, `htp__formradio()`

**Quarkus:**
- [ ] Multipart form handling
- [ ] Array parameters (same name, multiple values)

---

### Phase 4: Flexible Parameters (name_array/value_array)

**Goal:** Support Oracle's flexible parameter passing.

**Context:** Oracle mod_plsql supports flexible parameter procedures:
```sql
PROCEDURE my_proc(
    name_array  IN owa_util.ident_arr,
    value_array IN owa_util.vc_arr
)
```

**PostgreSQL:**
- [ ] `owa_util.ident_arr` type equivalent
- [ ] `owa_util.vc_arr` type equivalent

**Quarkus:**
- [ ] Detect flexible parameter signatures
- [ ] Convert parameters to array format
- [ ] Handle `!` prefix for array parameters

---

### Phase 5: HTTP Headers & Redirects

**Goal:** Support custom headers, cookies, redirects.

**PostgreSQL:**
- [ ] `temp_http_headers` table
- [ ] `owa_util__redirect_url()`
- [ ] `owa_util__mime_header()`
- [ ] `htp__header()` additions

**Quarkus:**
- [ ] Extract headers from temp table
- [ ] Apply headers to HTTP response
- [ ] Handle redirects (Location header)
- [ ] Handle Content-Type overrides

---

### Phase 6: Cookie Support

**Goal:** Support OWA_COOKIE for session management.

**PostgreSQL:**
- [ ] `temp_owa_cookies` table
- [ ] `owa_cookie__send()`
- [ ] `owa_cookie__get()`
- [ ] `owa_cookie__remove()`

**Quarkus:**
- [ ] Parse incoming cookies into temp table
- [ ] Extract outgoing cookies from temp table
- [ ] Set cookies on HTTP response

---

### Phase 7: Advanced Features (As Needed)

**Potential additions based on actual usage:**
- [ ] File downloads (BLOB streaming)
- [ ] OWA_SEC authentication hooks
- [ ] HTTPS/SSL considerations
- [ ] Connection affinity for package variables
- [ ] Performance optimization (caching, compression)
- [ ] Logging and monitoring
- [ ] Error page customization

---

## Configuration Requirements

### orapgsync Application Config

Add to existing configuration:

```json
{
  "modplsql": {
    "enabled": true,
    "dadName": "myapp",
    "defaultSchema": "hr",
    "urlPrefix": "/pls",
    "javaSourcePath": "/path/to/gateway/project"
  }
}
```

### New REST Endpoints

```
POST /api/modplsql/gateway/generate    - Generate Quarkus gateway project
POST /api/modplsql/gateway/configure   - Update gateway configuration
GET  /api/modplsql/procedures          - List web-callable procedures
```

---

## PL/SQL Transformation Updates

**Status:** PL/SQL transformation is now **unfrozen**.

**Required transformations for mod_plsql:**

1. **HTP.P calls** → `oracle_compat.htp__p()`
   ```sql
   -- Oracle
   HTP.P('<html>');

   -- PostgreSQL
   PERFORM oracle_compat.htp__p('<html>');
   ```

2. **HTF function calls** → `oracle_compat.htf__*()`
   ```sql
   -- Oracle
   HTP.P(HTF.BOLD('Hello'));

   -- PostgreSQL
   PERFORM oracle_compat.htp__p(oracle_compat.htf__bold('Hello'));
   ```

3. **OWA_UTIL calls** → `oracle_compat.owa_util__*()`

**Implementation:**
- Add HTP/HTF/OWA to package function recognition in `VisitGeneralElement`
- Transform to flattened `oracle_compat.*` naming (existing pattern)

---

## Testing Strategy

### Unit Tests

- HTP buffer functions (init, p, prn, get_buffer)
- HTF HTML generation functions
- CGI environment functions
- Each function in isolation

### Integration Tests

- End-to-end: HTTP request → Quarkus → PostgreSQL → HTML response
- Parameter passing (GET, POST, arrays)
- Error handling (procedure throws exception)
- Buffer handling with large responses

### Real-World Validation

- Select simple procedures from actual Oracle application
- Migrate and test against Quarkus gateway
- Compare output with Oracle mod_plsql output

---

## Success Criteria

### Phase 1 (MVP)
- [ ] Simple HTP.P-based procedure renders correctly
- [ ] Basic URL routing works
- [ ] Query parameters passed to procedures
- [ ] Error pages displayed on exceptions

### Phase 2-3
- [ ] HTF functions generate correct HTML
- [ ] Forms submit and process correctly
- [ ] Tables render with data

### Phase 4+
- [ ] Flexible parameters work
- [ ] Redirects function
- [ ] Cookies maintain session state

---

## Open Questions

1. **Gateway deployment:** Embedded in orapgsync or standalone JAR?
2. **Multiple DADs:** Support multiple database access descriptors?
3. **Static files:** Serve static assets through gateway or separate server?
4. **Authentication integration:** How to integrate with existing auth systems?
5. **Performance baseline:** What response time targets?

---

## References

- Oracle HTP Package Documentation
- Oracle OWA_UTIL Package Documentation
- Oracle mod_plsql User's Guide
- Existing oraclecompat module: `src/main/java/.../oraclecompat/`
