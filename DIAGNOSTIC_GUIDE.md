# Package Variable Transformation Diagnostic Guide

**Problem:** Package variables like `gX := gX + 1` are not being transformed to getter/setter calls in production.

## Quick Diagnostic Steps

### Step 1: Check if Package Context is Being Extracted

Add this temporary logging to `PostgresStandaloneFunctionImplementationJob.ensurePackageContext()` at line 466:

```java
// AFTER: packageContextCache.put(cacheKey, context);
log.info("📦 DIAGNOSTIC: Cached package context: {} with {} variables",
         cacheKey, context.getVariables().size());
for (String varName : context.getVariables().keySet()) {
    log.info("  - Variable: {}", varName);
}
```

**Expected output:** You should see log messages like:
```
📦 DIAGNOSTIC: Cached package context: hr.your_pkg with 3 variables
  - Variable: gx
  - Variable: g_counter
  - Variable: g_status
```

**If you DON'T see this:**
- Package context is not being extracted
- Check if `!function.isStandalone()` is returning true (line 159)
- Check if `extractPackageName()` is finding the package name

---

### Step 2: Check if Context is Being Passed to Transformation

Add this temporary logging to `PostgresStandaloneFunctionImplementationJob.performTransformation()` at line 180:

```java
// BEFORE transformation call
log.info("🔧 DIAGNOSTIC: Transforming {} (package: {}) with cache size: {}",
         function.getObjectName(),
         function.getPackageName(),
         packageContextCache.size());

if (function.getPackageName() != null) {
    String cacheKey = (function.getSchema() + "." + function.getPackageName()).toLowerCase();
    PackageContext ctx = packageContextCache.get(cacheKey);
    if (ctx != null) {
        log.info("   Context found with {} variables", ctx.getVariables().size());
    } else {
        log.warn("   ⚠️  NO CONTEXT FOUND for key: {}", cacheKey);
    }
}
```

**Expected output:**
```
🔧 DIAGNOSTIC: Transforming increment_counter (package: your_pkg) with cache size: 1
   Context found with 3 variables
```

**If you see "NO CONTEXT FOUND":**
- The cache key doesn't match
- Case sensitivity issue
- Package name mismatch

---

### Step 3: Check if TransformationContext is Receiving the Cache

Add this logging to `TransformationContext` constructor (line 164):

```java
// AFTER: this.packageContextCache = ...
if (packageContextCache != null && !packageContextCache.isEmpty()) {
    log.info("📋 DIAGNOSTIC: TransformationContext created with {} package contexts",
             packageContextCache.size());
    for (String key : packageContextCache.keySet()) {
        PackageContext pc = packageContextCache.get(key);
        log.info("   - {}: {} variables", key, pc.getVariables().size());
    }
} else {
    log.warn("⚠️  DIAGNOSTIC: TransformationContext created with EMPTY package cache!");
}
```

---

### Step 4: Check if Variable is Being Detected

Add this logging to `PostgresCodeBuilder.isPackageVariable()` (around line 595):

```java
public boolean isPackageVariable(String packageName, String variableName) {
    if (context == null) {
        log.debug("isPackageVariable({}, {}): context is NULL", packageName, variableName);
        return false;
    }

    boolean result = context.isPackageVariable(packageName, variableName);
    log.info("🔍 DIAGNOSTIC: isPackageVariable({}, {}) = {}",
             packageName, variableName, result);
    return result;
}
```

**Expected output when processing `gX := gX + 1`:**
```
🔍 DIAGNOSTIC: isPackageVariable(your_pkg, gX) = true
```

**If result is false:**
- Variable name case mismatch (gX vs gx vs GX)
- Package name wrong
- Variable not in context

---

### Step 5: Check Case Sensitivity

Oracle identifiers are case-INSENSITIVE unless quoted. Add this check:

```java
// In handleSimplePart() where we check package variables
String identifier = partCtx.getText();
String identifierLower = identifier.toLowerCase();  // ADD THIS

log.info("🔍 DIAGNOSTIC: Checking identifier '{}' (lower: '{}') as package variable",
         identifier, identifierLower);

// Use identifierLower for the check:
if (context.isPackageVariable(currentPackageName, identifierLower)) {
```

---

## Common Issues and Fixes

### Issue 1: Case Sensitivity

**Symptom:** Variable `gX` in Oracle code, but context has `gx`

**Fix:** Ensure all comparisons use `.toLowerCase()`:

```java
// In VisitGeneralElement.handleSimplePart():
if (context.isPackageVariable(currentPackageName, identifier.toLowerCase())) {
    return context.getPackageVariableGetter(currentPackageName, identifier.toLowerCase());
}
```

### Issue 2: Package Name Extraction Fails

**Symptom:** `extractPackageName()` returns null

**Check:** Function name format should be `packagename__functionname` (double underscore)

```java
private String extractPackageName(FunctionMetadata function) {
    String objectName = function.getObjectName();
    int idx = objectName.indexOf("__");
    if (idx > 0) {
        String pkg = objectName.substring(0, idx);
        log.info("📦 Extracted package name: {} from {}", pkg, objectName);
        return pkg;
    }
    log.warn("⚠️  Could not extract package name from: {}", objectName);
    return null;
}
```

### Issue 3: Package Spec Not Found

**Symptom:** `ensurePackageContext()` fails to query package spec

**Check:** The ALL_SOURCE query (around line 425):

```java
// Log the query
log.info("📦 Querying package spec for {}.{}", schema, packageName);
log.debug("Query: {}", query);  // Add actual query

// Log results
if (packageSpecSql == null || packageSpecSql.trim().isEmpty()) {
    log.error("⚠️  Package spec is EMPTY for {}.{}", schema, packageName);
}
```

---

## Quick Test

Add this test method to verify the flow:

```java
@Test
void diagnostic_packageVariableDetection() {
    String plsql = "gX := gX + 1;";

    // Enable debug logging
    ch.qos.logback.classic.Logger rootLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);

    // Parse and check
    // ... transformation code
}
```

---

## Expected Complete Flow

For `gX := gX + 1` in package function:

1. ✅ Job detects package function → calls `ensurePackageContext()`
2. ✅ Queries Oracle for package spec
3. ✅ Parses spec, extracts `gX` variable
4. ✅ Generates helper functions, executes in PostgreSQL
5. ✅ Caches PackageContext with key `"hr.your_pkg"`
6. ✅ Calls transformProcedure with packageContextCache
7. ✅ Creates TransformationContext with cache
8. ✅ PostgresCodeBuilder gets context
9. ✅ VisitAssignment parses LHS (`gX`)
10. ✅ parsePackageVariableReference detects it (unqualified, in package)
11. ✅ Returns PackageVariableReference
12. ✅ Transforms to PERFORM setter call
13. ✅ VisitExpression parses RHS (`gX + 1`)
14. ✅ handleSimplePart detects `gX` as package variable
15. ✅ Transforms to getter call
16. ✅ Final SQL: `PERFORM hr.your_pkg__set_gx(hr.your_pkg__get_gx() + 1);`

**Find where this chain breaks!**
