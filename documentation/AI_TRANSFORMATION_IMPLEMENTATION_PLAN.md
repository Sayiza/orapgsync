# AI-Assisted Transformation Implementation Plan

## Overview

This document describes the implementation of an AI-assisted transformation system for handling PL/SQL constructs that cannot be deterministically transformed to PL/pgSQL. The system uses a database-centric queue approach with a separate frontend interface.

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EXISTING TRANSFORMATION                             │
│                                                                             │
│  PostgresCodeBuilder → Deterministic Transform → Success: Apply to DB      │
│                                              → Failure: Queue for AI       │
└─────────────────────────────────────────────────────────────────────────────┘
                                                        │
                                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         POSTGRESQL DATABASE                                 │
│                                                                             │
│  ai_transform.queue        - Work items with intermediate code             │
│  ai_transform.config       - AI provider settings                          │
│  ai_transform.audit_log    - Processing history                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │
┌───────────────────────────────────┴─────────────────────────────────────────┐
│                         AI TRANSFORMATION MODULE                            │
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │ AiProviderFactory│  │ QueueService    │  │ ResultApplier   │             │
│  │ (Claude/Ollama)  │  │ (CRUD + batch)  │  │ (validate+apply)│             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│                                                                             │
│  REST API: /api/ai-transform/*                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │
┌───────────────────────────────────┴─────────────────────────────────────────┐
│                         FRONTEND (ai-transform.html)                        │
│                                                                             │
│  - AI Provider Configuration (Claude API / Ollama)                         │
│  - Queue Dashboard (status counts, item list)                              │
│  - Batch Processing Controls (batch size, start/stop)                      │
│  - Item Detail View (original, intermediate, completed, diff)              │
│  - Apply Controls (individual / bulk)                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Queue storage | PostgreSQL table | Already connected, transactional, observable |
| UI location | Separate HTML page | Distinct workflow, avoids cluttering main UI |
| Processing trigger | Manual | User controls cost and review timing |
| Apply trigger | Manual with bulk option | Safety - review before applying |
| Batch processing | Configurable batch size | Balance between throughput and control |
| Ordering | Sequential by schema/name | Predictable, easy to track progress |
| Retry | Manual only | Avoid infinite loops, user decides when to retry |
| Interruption | Graceful (finish current item) | Simplicity, no complex checkpointing |
| Provider abstraction | Interface + implementations | Easy to add new AI providers |

## Phase 1: Database Schema and Core Services

### 1.1 Database Schema

**File:** `src/main/resources/db/ai_transform_schema.sql`

```sql
-- Schema for AI transformation workflow
CREATE SCHEMA IF NOT EXISTS ai_transform;

-- AI provider configuration
CREATE TABLE ai_transform.config (
    id SERIAL PRIMARY KEY,
    key TEXT UNIQUE NOT NULL,
    value TEXT,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Initial config values
INSERT INTO ai_transform.config (key, value) VALUES
    ('provider', 'claude'),              -- 'claude' or 'ollama'
    ('claude_api_key', NULL),
    ('claude_model', 'claude-sonnet-4-20250514'),
    ('ollama_url', 'http://localhost:11434'),
    ('ollama_model', 'codellama:34b'),
    ('batch_size', '5'),
    ('processing_active', 'false');

-- Main transformation queue
CREATE TABLE ai_transform.queue (
    id SERIAL PRIMARY KEY,

    -- Object identity
    schema_name TEXT NOT NULL,
    object_name TEXT NOT NULL,
    object_type TEXT NOT NULL,  -- FUNCTION, PROCEDURE, TRIGGER, VIEW

    -- Source material
    oracle_source TEXT NOT NULL,

    -- Intermediate representation (partially transformed with annotations)
    intermediate_code TEXT NOT NULL,

    -- Structured annotations describing what needs AI attention
    annotations JSONB NOT NULL DEFAULT '[]',

    -- Metadata context for AI (tables, types, packages involved)
    metadata_context JSONB NOT NULL DEFAULT '{}',

    -- AI output
    completed_code TEXT,
    ai_explanation TEXT,
    ai_model TEXT,
    ai_provider TEXT,

    -- Workflow status
    status TEXT NOT NULL DEFAULT 'pending',
    -- Values: pending, in_progress, completed, failed, applied, rejected

    -- Processing control
    priority INT DEFAULT 0,
    attempts INT DEFAULT 0,
    last_error TEXT,

    -- Timestamps
    created_at TIMESTAMPTZ DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,

    -- Validation
    syntax_valid BOOLEAN,
    validation_errors TEXT[],

    UNIQUE(schema_name, object_name, object_type)
);

-- Indexes for efficient queries
CREATE INDEX idx_queue_status ON ai_transform.queue(status);
CREATE INDEX idx_queue_pending_order ON ai_transform.queue(schema_name, object_name)
    WHERE status = 'pending';

-- Audit log for tracking all processing attempts
CREATE TABLE ai_transform.audit_log (
    id SERIAL PRIMARY KEY,
    queue_id INT REFERENCES ai_transform.queue(id),
    action TEXT NOT NULL,  -- 'queued', 'started', 'completed', 'failed', 'applied', 'rejected'
    ai_provider TEXT,
    ai_model TEXT,
    details JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Progress view
CREATE VIEW ai_transform.progress AS
SELECT
    status,
    COUNT(*) as count
FROM ai_transform.queue
GROUP BY status;
```

### 1.2 Configuration Service

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/config/AiTransformConfigService.java`

```java
@ApplicationScoped
public class AiTransformConfigService {

    @Inject
    PostgresConnectionService postgres;

    public String getConfig(String key);
    public void setConfig(String key, String value);
    public Map<String, String> getAllConfig();

    // Typed accessors
    public AiProvider getActiveProvider();
    public String getClaudeApiKey();
    public String getClaudeModel();
    public String getOllamaUrl();
    public String getOllamaModel();
    public int getBatchSize();
    public boolean isProcessingActive();
    public void setProcessingActive(boolean active);
}
```

### 1.3 Queue Repository

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/queue/AiTransformQueueRepository.java`

```java
@ApplicationScoped
public class AiTransformQueueRepository {

    @Inject
    PostgresConnectionService postgres;

    // CRUD operations
    public int insert(QueueItem item);
    public Optional<QueueItem> findById(int id);
    public List<QueueItem> findByStatus(String status);
    public List<QueueItem> findAll();
    public void update(QueueItem item);
    public void delete(int id);

    // Queue operations
    public List<QueueItem> claimNextBatch(int batchSize);  // Atomic claim with FOR UPDATE SKIP LOCKED
    public void markStatus(int id, String status, String error);
    public void markCompleted(int id, String completedCode, String explanation, String model, String provider);
    public void markApplied(int id);

    // Statistics
    public Map<String, Integer> getStatusCounts();

    // Audit
    public void logAction(int queueId, String action, String provider, String model, Map<String, Object> details);
}
```

### 1.4 Queue Item Model

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/queue/QueueItem.java`

```java
public class QueueItem {
    private int id;
    private String schemaName;
    private String objectName;
    private String objectType;
    private String oracleSource;
    private String intermediateCode;
    private List<Annotation> annotations;
    private MetadataContext metadataContext;
    private String completedCode;
    private String aiExplanation;
    private String aiModel;
    private String aiProvider;
    private String status;
    private int priority;
    private int attempts;
    private String lastError;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant appliedAt;
    private Boolean syntaxValid;
    private List<String> validationErrors;

    // Nested classes
    public static class Annotation {
        private String type;      // e.g., "dynamic_sql", "package_variable"
        private int line;
        private String original;  // Original Oracle code
        private String hint;      // Transformation hint
        private Map<String, Object> context;
    }

    public static class MetadataContext {
        private List<TableInfo> tables;
        private List<TypeInfo> types;
        private List<PackageInfo> packages;
        private List<FunctionInfo> functions;
    }
}
```

## Phase 2: AI Provider Abstraction

### 2.1 Provider Interface

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/provider/AiProvider.java`

```java
public interface AiProvider {

    String getName();  // "claude" or "ollama"

    boolean testConnection();

    AiCompletionResult complete(AiCompletionRequest request);

    record AiCompletionRequest(
        String systemPrompt,
        String userPrompt,
        String model,
        int maxTokens
    ) {}

    record AiCompletionResult(
        boolean success,
        String completedCode,
        String explanation,
        String model,
        String errorMessage
    ) {}
}
```

### 2.2 Claude Provider

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/provider/ClaudeAiProvider.java`

```java
@ApplicationScoped
@Named("claude")
public class ClaudeAiProvider implements AiProvider {

    @Inject
    AiTransformConfigService config;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    @Override
    public String getName() { return "claude"; }

    @Override
    public boolean testConnection() {
        // Simple API call to verify key works
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        // HTTP POST to Claude API
        // Parse response, extract code block
        // Return result
    }
}
```

### 2.3 Ollama Provider

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/provider/OllamaAiProvider.java`

```java
@ApplicationScoped
@Named("ollama")
public class OllamaAiProvider implements AiProvider {

    @Inject
    AiTransformConfigService config;

    @Override
    public String getName() { return "ollama"; }

    @Override
    public boolean testConnection() {
        // GET {ollama_url}/api/tags to verify connection
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        // POST to {ollama_url}/api/generate
        // Parse response
        // Return result
    }
}
```

### 2.4 Provider Factory

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/provider/AiProviderFactory.java`

```java
@ApplicationScoped
public class AiProviderFactory {

    @Inject
    @Named("claude")
    AiProvider claudeProvider;

    @Inject
    @Named("ollama")
    AiProvider ollamaProvider;

    @Inject
    AiTransformConfigService config;

    public AiProvider getActiveProvider() {
        return switch (config.getActiveProvider()) {
            case "claude" -> claudeProvider;
            case "ollama" -> ollamaProvider;
            default -> throw new IllegalStateException("Unknown provider: " + config.getActiveProvider());
        };
    }

    public AiProvider getProvider(String name) {
        return switch (name) {
            case "claude" -> claudeProvider;
            case "ollama" -> ollamaProvider;
            default -> throw new IllegalArgumentException("Unknown provider: " + name);
        };
    }
}
```

## Phase 3: Prompt Engineering

### 3.1 System Prompt Template

**File:** `src/main/resources/ai-prompts/system-prompt.txt`

```
You are an expert Oracle PL/SQL to PostgreSQL PL/pgSQL migration specialist.

You will receive partially transformed PostgreSQL functions with annotations marking
sections that need your completion. Your task is to complete ONLY the marked sections
while preserving all existing code exactly.

## Annotation Format

Sections requiring completion are marked with:
```
/*--AI_NEEDED:annotation_type
ORIGINAL: <original Oracle code>
CONTEXT: <relevant context>
HINT: <transformation hint>
--*/
<placeholder>
```

## Transformation Rules

1. PRESERVE all code outside AI_NEEDED blocks exactly as-is
2. REPLACE only the placeholder line(s) following each AI_NEEDED block
3. REMOVE the AI_NEEDED comment block after completing the transformation

### Package Variables
- Oracle: `pkg.variable` or `pkg.variable.field`
- PostgreSQL: Use getter function `schema.pkg__get_variable()` and field access
- Example: `bonus_pkg.config.rate` → `(hr.bonus_pkg__get_config()).rate`

### Package Functions
- Oracle: `pkg.function(args)`
- PostgreSQL: `schema.pkg__function(args)` (double underscore)

### Dynamic SQL
- Oracle: `EXECUTE IMMEDIATE sql_string INTO var`
- PostgreSQL: `EXECUTE sql_string INTO var`
- Use `format()` for safe string interpolation: `EXECUTE format('SELECT %I FROM %I', col, tbl)`

### DUAL Table
- Oracle: `SELECT expr FROM DUAL`
- PostgreSQL: `SELECT expr` (no FROM clause needed)

### NVL/NVL2/DECODE
- NVL(a, b) → COALESCE(a, b)
- NVL2(a, b, c) → CASE WHEN a IS NOT NULL THEN b ELSE c END
- DECODE(a, b, c, d, e, f) → CASE a WHEN b THEN c WHEN d THEN e ELSE f END

### String Concatenation
- Oracle allows: string || number
- PostgreSQL requires: string || number::text

### ROWNUM
- Oracle: WHERE ROWNUM <= n
- PostgreSQL: LIMIT n

### Sequences
- Oracle: seq.NEXTVAL, seq.CURRVAL
- PostgreSQL: nextval('schema.seq'), currval('schema.seq')

## Output Format

Return ONLY the completed PostgreSQL function. Do not include explanations in the code.

After the code block, provide a brief explanation of changes made in a separate section
marked with "## Explanation".
```

### 3.2 User Prompt Builder

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/prompt/PromptBuilder.java`

```java
@ApplicationScoped
public class PromptBuilder {

    public String buildUserPrompt(QueueItem item) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Task\n\n");
        sb.append("Complete the following PostgreSQL function by replacing all AI_NEEDED placeholders.\n\n");

        sb.append("## Intermediate Code (to complete)\n\n```sql\n");
        sb.append(item.getIntermediateCode());
        sb.append("\n```\n\n");

        sb.append("## Original Oracle Source (for reference)\n\n```sql\n");
        sb.append(item.getOracleSource());
        sb.append("\n```\n\n");

        sb.append("## Schema Metadata\n\n");
        sb.append(formatMetadata(item.getMetadataContext()));
        sb.append("\n\n");

        sb.append("## Annotations Summary\n\n");
        for (var annotation : item.getAnnotations()) {
            sb.append("- **").append(annotation.getType()).append("** at line ")
              .append(annotation.getLine()).append(": ").append(annotation.getHint()).append("\n");
        }

        return sb.toString();
    }

    private String formatMetadata(MetadataContext ctx) {
        // Format tables, types, packages as structured text
    }
}
```

## Phase 4: Processing Service

### 4.1 Queue Processor

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/processing/AiQueueProcessor.java`

```java
@ApplicationScoped
public class AiQueueProcessor {

    @Inject
    AiTransformQueueRepository repository;

    @Inject
    AiProviderFactory providerFactory;

    @Inject
    PromptBuilder promptBuilder;

    @Inject
    AiTransformConfigService config;

    @Inject
    @ConfigProperty(name = "ai.transform.system-prompt")
    String systemPrompt;

    private volatile boolean stopRequested = false;

    /**
     * Process a batch of pending items.
     * Returns number of items processed.
     */
    public ProcessingResult processBatch() {
        if (!config.isProcessingActive()) {
            return ProcessingResult.inactive();
        }

        int batchSize = config.getBatchSize();
        List<QueueItem> batch = repository.claimNextBatch(batchSize);

        if (batch.isEmpty()) {
            return ProcessingResult.empty();
        }

        AiProvider provider = providerFactory.getActiveProvider();
        String model = getModelForProvider(provider.getName());

        int processed = 0;
        int succeeded = 0;
        int failed = 0;

        for (QueueItem item : batch) {
            if (stopRequested) {
                // Release unclaimed items back to pending
                releaseRemainingItems(batch, processed);
                stopRequested = false;
                return new ProcessingResult(processed, succeeded, failed, true);
            }

            try {
                processItem(item, provider, model);
                succeeded++;
            } catch (Exception e) {
                failed++;
                repository.markStatus(item.getId(), "failed", e.getMessage());
                repository.logAction(item.getId(), "failed", provider.getName(), model,
                    Map.of("error", e.getMessage()));
            }
            processed++;
        }

        return new ProcessingResult(processed, succeeded, failed, false);
    }

    private void processItem(QueueItem item, AiProvider provider, String model) {
        repository.logAction(item.getId(), "started", provider.getName(), model, Map.of());

        String userPrompt = promptBuilder.buildUserPrompt(item);

        var request = new AiProvider.AiCompletionRequest(
            systemPrompt,
            userPrompt,
            model,
            8192  // max tokens
        );

        var result = provider.complete(request);

        if (result.success()) {
            String code = extractCodeBlock(result.completedCode());
            String explanation = extractExplanation(result.completedCode());

            repository.markCompleted(item.getId(), code, explanation, model, provider.getName());
            repository.logAction(item.getId(), "completed", provider.getName(), model,
                Map.of("explanation", explanation));
        } else {
            throw new AiProcessingException(result.errorMessage());
        }
    }

    public void requestStop() {
        this.stopRequested = true;
    }

    private String extractCodeBlock(String response) {
        // Extract SQL code from markdown code block
    }

    private String extractExplanation(String response) {
        // Extract explanation section
    }

    public record ProcessingResult(
        int processed,
        int succeeded,
        int failed,
        boolean interrupted
    ) {
        public static ProcessingResult inactive() { return new ProcessingResult(0, 0, 0, false); }
        public static ProcessingResult empty() { return new ProcessingResult(0, 0, 0, false); }
    }
}
```

### 4.2 Result Applier

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/processing/AiResultApplier.java`

```java
@ApplicationScoped
public class AiResultApplier {

    @Inject
    AiTransformQueueRepository repository;

    @Inject
    PostgresConnectionService postgres;

    /**
     * Validate and apply a single completed item.
     */
    public ApplyResult apply(int queueId) {
        var item = repository.findById(queueId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueId));

        if (!"completed".equals(item.getStatus())) {
            return ApplyResult.wrongStatus(item.getStatus());
        }

        // Validate syntax
        var validationResult = validateSyntax(item.getCompletedCode());
        if (!validationResult.valid()) {
            repository.update(item.toBuilder()
                .syntaxValid(false)
                .validationErrors(validationResult.errors())
                .build());
            return ApplyResult.syntaxError(validationResult.errors());
        }

        // Apply to database
        try {
            postgres.execute(item.getCompletedCode());
            repository.markApplied(queueId);
            repository.logAction(queueId, "applied", null, null, Map.of());
            return ApplyResult.success();
        } catch (SQLException e) {
            return ApplyResult.executionError(e.getMessage());
        }
    }

    /**
     * Apply all completed items.
     */
    public BulkApplyResult applyAllCompleted() {
        var completed = repository.findByStatus("completed");

        int applied = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (var item : completed) {
            var result = apply(item.getId());
            if (result.success()) {
                applied++;
            } else {
                failed++;
                errors.add(item.getSchemaName() + "." + item.getObjectName() + ": " + result.error());
            }
        }

        return new BulkApplyResult(applied, failed, errors);
    }

    private ValidationResult validateSyntax(String code) {
        // Use PostgreSQL parser to validate
        // Could use: SELECT * FROM pg_parse_query($code$...$code$)
        // Or simple heuristics for basic validation
    }

    public record ApplyResult(boolean success, String error) {
        public static ApplyResult success() { return new ApplyResult(true, null); }
        public static ApplyResult wrongStatus(String status) {
            return new ApplyResult(false, "Item status is '" + status + "', expected 'completed'");
        }
        public static ApplyResult syntaxError(List<String> errors) {
            return new ApplyResult(false, "Syntax errors: " + String.join(", ", errors));
        }
        public static ApplyResult executionError(String msg) {
            return new ApplyResult(false, "Execution error: " + msg);
        }
    }

    public record BulkApplyResult(int applied, int failed, List<String> errors) {}
}
```

## Phase 5: Integration with Existing Transformation

### 5.1 Annotation Types

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/annotation/AiAnnotationType.java`

```java
public enum AiAnnotationType {
    DYNAMIC_SQL("dynamic_sql", "Dynamic SQL execution"),
    PACKAGE_VARIABLE("package_variable", "Package variable access"),
    PACKAGE_CURSOR("package_cursor", "Package cursor reference"),
    COMPLEX_TYPE_ACCESS("complex_type_access", "Complex nested type access"),
    BULK_COLLECT("bulk_collect", "BULK COLLECT operation"),
    FORALL_DML("forall_dml", "FORALL bulk DML"),
    AUTONOMOUS_TRANSACTION("autonomous_transaction", "Autonomous transaction pragma"),
    PIPELINED_FUNCTION("pipelined_function", "Pipelined table function"),
    OBJECT_TYPE_METHOD("object_type_method", "Object type method call"),
    REF_CURSOR("ref_cursor", "REF CURSOR handling"),
    ASSOCIATIVE_ARRAY("associative_array", "Associative array (index-by table)"),
    UNSUPPORTED_BUILTIN("unsupported_builtin", "Unsupported Oracle built-in"),
    OTHER("other", "Other unsupported construct");

    private final String code;
    private final String description;
}
```

### 5.2 Intermediate Code Builder

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/intermediate/IntermediateCodeBuilder.java`

```java
@ApplicationScoped
public class IntermediateCodeBuilder {

    /**
     * Add an AI annotation to the code being built.
     * Called by visitors when they encounter unsupported constructs.
     */
    public String buildAnnotation(
            AiAnnotationType type,
            String originalCode,
            String context,
            String hint) {

        return String.format("""
            /*--AI_NEEDED:%s
            ORIGINAL: %s
            CONTEXT: %s
            HINT: %s
            --*/
            NULL; -- PLACEHOLDER""",
            type.getCode(),
            originalCode.replace("*/", "* /"),  // Escape nested comments
            context,
            hint);
    }

    /**
     * Check if code contains AI annotations.
     */
    public boolean hasAnnotations(String code) {
        return code.contains("/*--AI_NEEDED:");
    }

    /**
     * Extract annotations from intermediate code.
     */
    public List<QueueItem.Annotation> extractAnnotations(String code) {
        List<QueueItem.Annotation> annotations = new ArrayList<>();
        Pattern pattern = Pattern.compile(
            "/\\*--AI_NEEDED:(\\w+)\\s*\n" +
            "ORIGINAL:\\s*(.+?)\\s*\n" +
            "CONTEXT:\\s*(.+?)\\s*\n" +
            "HINT:\\s*(.+?)\\s*\n" +
            "--\\*/",
            Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(code);
        int line = 1;
        while (matcher.find()) {
            // Calculate line number
            line = countLines(code.substring(0, matcher.start()));

            annotations.add(new QueueItem.Annotation(
                matcher.group(1),  // type
                line,
                matcher.group(2),  // original
                matcher.group(4),  // hint
                Map.of("context", matcher.group(3))
            ));
        }

        return annotations;
    }
}
```

### 5.3 Queue Writer Service

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/queue/AiQueueWriterService.java`

```java
@ApplicationScoped
public class AiQueueWriterService {

    @Inject
    AiTransformQueueRepository repository;

    @Inject
    IntermediateCodeBuilder intermediateBuilder;

    @Inject
    MetadataContextBuilder metadataBuilder;

    /**
     * Queue a function for AI transformation.
     * Called when deterministic transformation produces code with annotations.
     */
    public int queueForAiTransformation(
            String schemaName,
            String objectName,
            String objectType,
            String oracleSource,
            String intermediateCode,
            TransformationContext transformContext) {

        var annotations = intermediateBuilder.extractAnnotations(intermediateCode);
        var metadata = metadataBuilder.buildContext(transformContext, annotations);

        var item = QueueItem.builder()
            .schemaName(schemaName)
            .objectName(objectName)
            .objectType(objectType)
            .oracleSource(oracleSource)
            .intermediateCode(intermediateCode)
            .annotations(annotations)
            .metadataContext(metadata)
            .status("pending")
            .build();

        return repository.insert(item);
    }
}
```

### 5.4 Visitor Integration Example

Modification to existing visitors to emit annotations instead of failing:

```java
// In a visitor method that encounters unsupported construct:

@Override
public String visitExecute_immediate(Execute_immediateContext ctx) {
    String sqlExpr = visit(ctx.expression());

    // Check if this is simple enough for deterministic transformation
    if (isSimpleLiteral(ctx.expression())) {
        return "EXECUTE " + sqlExpr;
    }

    // Complex dynamic SQL - emit annotation for AI
    String original = ctx.getText();
    String context = "SQL expression: " + sqlExpr;
    String hint = "Convert to EXECUTE format() with proper escaping";

    return intermediateBuilder.buildAnnotation(
        AiAnnotationType.DYNAMIC_SQL,
        original,
        context,
        hint
    );
}
```

## Phase 6: REST API

### 6.1 AI Transform Resource

**File:** `src/main/java/me/christianrobert/orapgsync/aitransform/AiTransformResource.java`

```java
@Path("/api/ai-transform")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiTransformResource {

    @Inject
    AiTransformConfigService configService;

    @Inject
    AiTransformQueueRepository queueRepository;

    @Inject
    AiQueueProcessor processor;

    @Inject
    AiResultApplier applier;

    @Inject
    AiProviderFactory providerFactory;

    // === Configuration ===

    @GET
    @Path("/config")
    public Map<String, String> getConfig() {
        return configService.getAllConfig();
    }

    @PUT
    @Path("/config")
    public Response updateConfig(Map<String, String> config) {
        for (var entry : config.entrySet()) {
            configService.setConfig(entry.getKey(), entry.getValue());
        }
        return Response.ok().build();
    }

    @POST
    @Path("/config/test-connection")
    public ConnectionTestResult testConnection(@QueryParam("provider") String provider) {
        try {
            var p = providerFactory.getProvider(provider);
            boolean success = p.testConnection();
            return new ConnectionTestResult(success, success ? "Connected" : "Connection failed");
        } catch (Exception e) {
            return new ConnectionTestResult(false, e.getMessage());
        }
    }

    // === Queue ===

    @GET
    @Path("/queue")
    public List<QueueItem> getQueue(
            @QueryParam("status") String status,
            @QueryParam("schema") String schema) {
        if (status != null) {
            return queueRepository.findByStatus(status);
        }
        return queueRepository.findAll();
    }

    @GET
    @Path("/queue/{id}")
    public QueueItem getQueueItem(@PathParam("id") int id) {
        return queueRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Item not found: " + id));
    }

    @DELETE
    @Path("/queue/{id}")
    public Response deleteQueueItem(@PathParam("id") int id) {
        queueRepository.delete(id);
        return Response.ok().build();
    }

    @POST
    @Path("/queue/{id}/retry")
    public Response retryQueueItem(@PathParam("id") int id) {
        queueRepository.markStatus(id, "pending", null);
        return Response.ok().build();
    }

    @POST
    @Path("/queue/{id}/reject")
    public Response rejectQueueItem(@PathParam("id") int id) {
        queueRepository.markStatus(id, "rejected", "Manually rejected");
        return Response.ok().build();
    }

    // === Statistics ===

    @GET
    @Path("/stats")
    public Map<String, Integer> getStats() {
        return queueRepository.getStatusCounts();
    }

    // === Processing ===

    @POST
    @Path("/process")
    public ProcessingResult processBatch() {
        configService.setProcessingActive(true);
        try {
            return processor.processBatch();
        } finally {
            configService.setProcessingActive(false);
        }
    }

    @POST
    @Path("/process/stop")
    public Response stopProcessing() {
        processor.requestStop();
        return Response.ok().build();
    }

    @GET
    @Path("/process/status")
    public ProcessingStatus getProcessingStatus() {
        return new ProcessingStatus(configService.isProcessingActive());
    }

    // === Apply ===

    @POST
    @Path("/apply/{id}")
    public ApplyResult applyItem(@PathParam("id") int id) {
        return applier.apply(id);
    }

    @POST
    @Path("/apply-all")
    public BulkApplyResult applyAllCompleted() {
        return applier.applyAllCompleted();
    }

    // === Records ===

    public record ConnectionTestResult(boolean success, String message) {}
    public record ProcessingStatus(boolean active) {}
}
```

## Phase 7: Frontend

### 7.1 HTML Page

**File:** `src/main/resources/META-INF/resources/ai-transform.html`

Single-page HTML with:
- Configuration panel (provider selection, API keys, batch size)
- Queue dashboard (status counts)
- Queue item list (filterable, sortable)
- Item detail modal (tabs: original, intermediate, completed, diff)
- Action buttons (process, stop, apply, retry, reject)

### 7.2 JavaScript Service

**File:** `src/main/resources/META-INF/resources/js/ai-transform-service.js`

```javascript
const AiTransformService = {

    // Config
    async getConfig() { return fetch('/api/ai-transform/config').then(r => r.json()); },
    async updateConfig(config) { return fetch('/api/ai-transform/config', { method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(config) }); },
    async testConnection(provider) { return fetch(`/api/ai-transform/config/test-connection?provider=${provider}`, { method: 'POST' }).then(r => r.json()); },

    // Queue
    async getQueue(status) { return fetch(`/api/ai-transform/queue${status ? '?status=' + status : ''}`).then(r => r.json()); },
    async getQueueItem(id) { return fetch(`/api/ai-transform/queue/${id}`).then(r => r.json()); },
    async deleteQueueItem(id) { return fetch(`/api/ai-transform/queue/${id}`, { method: 'DELETE' }); },
    async retryQueueItem(id) { return fetch(`/api/ai-transform/queue/${id}/retry`, { method: 'POST' }); },
    async rejectQueueItem(id) { return fetch(`/api/ai-transform/queue/${id}/reject`, { method: 'POST' }); },

    // Stats
    async getStats() { return fetch('/api/ai-transform/stats').then(r => r.json()); },

    // Processing
    async processBatch() { return fetch('/api/ai-transform/process', { method: 'POST' }).then(r => r.json()); },
    async stopProcessing() { return fetch('/api/ai-transform/process/stop', { method: 'POST' }); },
    async getProcessingStatus() { return fetch('/api/ai-transform/process/status').then(r => r.json()); },

    // Apply
    async applyItem(id) { return fetch(`/api/ai-transform/apply/${id}`, { method: 'POST' }).then(r => r.json()); },
    async applyAllCompleted() { return fetch('/api/ai-transform/apply-all', { method: 'POST' }).then(r => r.json()); }
};
```

## Implementation Phases

### Phase 1: Foundation (Database + Core Services)
1. Create `ai_transform` schema and tables
2. Implement `AiTransformConfigService`
3. Implement `AiTransformQueueRepository`
4. Implement `QueueItem` model with JSON mapping
5. Basic REST endpoints for config and queue CRUD

**Deliverable:** Can manually insert queue items, query them, update config

### Phase 2: AI Provider Layer
1. Define `AiProvider` interface
2. Implement `ClaudeAiProvider` with API integration
3. Implement `OllamaAiProvider` with API integration
4. Implement `AiProviderFactory`
5. Add connection test endpoints

**Deliverable:** Can test connections to Claude and Ollama

### Phase 3: Processing Pipeline
1. Create system prompt template
2. Implement `PromptBuilder`
3. Implement `AiQueueProcessor` with batch processing
4. Implement `AiResultApplier` with validation
5. Add processing and apply endpoints

**Deliverable:** Can process queue items and apply results

### Phase 4: Transformation Integration
1. Define `AiAnnotationType` enum
2. Implement `IntermediateCodeBuilder`
3. Implement `MetadataContextBuilder`
4. Implement `AiQueueWriterService`
5. Modify key visitors to emit annotations instead of failing

**Deliverable:** Transformation automatically queues failures

### Phase 5: Frontend
1. Create `ai-transform.html` page structure
2. Implement configuration panel
3. Implement queue dashboard
4. Implement item list with filtering
5. Implement item detail modal with tabs
6. Implement action buttons and status polling

**Deliverable:** Full UI for managing AI transformation

### Phase 6: Polish and Testing
1. Add comprehensive error handling
2. Add audit logging throughout
3. Add progress indicators
4. Write integration tests
5. Documentation updates

**Deliverable:** Production-ready feature

## File Structure Summary

```
src/main/java/me/christianrobert/orapgsync/aitransform/
├── AiTransformResource.java                 # REST API
├── annotation/
│   └── AiAnnotationType.java                # Annotation types enum
├── config/
│   └── AiTransformConfigService.java        # Configuration management
├── intermediate/
│   ├── IntermediateCodeBuilder.java         # Build annotated code
│   └── MetadataContextBuilder.java          # Build metadata context
├── processing/
│   ├── AiQueueProcessor.java                # Batch processing
│   └── AiResultApplier.java                 # Validate and apply
├── prompt/
│   └── PromptBuilder.java                   # Build AI prompts
├── provider/
│   ├── AiProvider.java                      # Provider interface
│   ├── AiProviderFactory.java               # Provider factory
│   ├── ClaudeAiProvider.java                # Claude implementation
│   └── OllamaAiProvider.java                # Ollama implementation
└── queue/
    ├── AiQueueWriterService.java            # Queue writer
    ├── AiTransformQueueRepository.java      # Queue repository
    └── QueueItem.java                       # Queue item model

src/main/resources/
├── ai-prompts/
│   └── system-prompt.txt                    # AI system prompt
├── db/
│   └── ai_transform_schema.sql              # Database schema
└── META-INF/resources/
    ├── ai-transform.html                    # Frontend page
    └── js/
        └── ai-transform-service.js          # Frontend service
```

## Configuration Properties

```properties
# application.properties additions

# AI Transform
ai.transform.enabled=true
ai.transform.system-prompt=${file:classpath:ai-prompts/system-prompt.txt}
ai.transform.max-tokens=8192
ai.transform.timeout-seconds=120
```

## Success Criteria

1. **Deterministic transformation** continues to work for supported constructs
2. **Unsupported constructs** produce annotated intermediate code (not failures)
3. **Queue** accurately tracks all items needing AI assistance
4. **Claude integration** successfully completes transformations
5. **Ollama integration** works as alternative provider
6. **Frontend** provides full visibility and control
7. **Apply** correctly updates PostgreSQL functions
8. **Graceful interruption** works without data loss
