# %ROWTYPE and %TYPE Support - Exploration Documentation

This directory contains comprehensive exploration and analysis of %ROWTYPE and %TYPE support for the Oracle-to-PostgreSQL transformer.

## Document Overview

### 1. **ROWTYPE_TYPE_EXPLORATION_SUMMARY.md** (647 lines)
Comprehensive technical analysis covering:

- **Executive Summary** - Quick assessment of infrastructure readiness
- **Infrastructure Audit** - Detailed review of all existing components
- **Implementation Requirements** - What needs to be built and where
- **Architecture & Design** - Strategic decisions and rationale
- **Dependencies & Prerequisites** - What's available vs what's needed
- **Potential Issues & Mitigations** - Risk analysis and solutions
- **Implementation Checklist** - Detailed task breakdown
- **Example Transformations** - Oracle → PostgreSQL output examples
- **References** - Links to existing code and plans

**Best for:** Understanding the complete picture, detailed implementation planning

### 2. **ROWTYPE_TYPE_INFRASTRUCTURE_CHECKLIST.md** (261 lines)
Quick reference guide with:

- **Component Status Matrix** - Table of all components with status
- **Dependency Tree** - Visual dependency relationships
- **What's Working** - RECORD types as reference implementation
- **What Needs Work** - New methods to implement
- **Implementation Flow Diagram** - Visual transformation process
- **Code Locations** - Exact file paths and line numbers
- **Test Strategy** - Unit and integration test approach
- **Success Criteria** - How to know when done

**Best for:** Quick lookup, implementation reference, quick start

## Quick Start

### For Understanding the System
1. Read the Executive Summary (5 min read)
2. Skim the Infrastructure Status section (10 min)
3. Review example transformations (5 min)

### For Implementation Planning
1. Review Component Status Matrix (CHECKLIST.md)
2. Check Dependency Tree (CHECKLIST.md)
3. Read Implementation Requirements (SUMMARY.md)
4. Follow Implementation Checklist (SUMMARY.md)

### For Actual Implementation
1. Start with the code location reference
2. Follow the implementation flow diagram
3. Use pseudocode as template
4. Reference RECORD types as working example
5. Run tests after each component

## Key Findings

### What Already Exists ✅
- Type categories (ROWTYPE, TYPE_REFERENCE)
- ANTLR grammar (tokens and parser rules)
- Data models (InlineTypeDefinition, FieldDefinition)
- Table metadata access (TransformationIndices)
- Type resolution cascade (three levels)
- Variable scope tracking
- Reference implementations (RECORD types, collections)

### What Needs to be Built ❌
- %ROWTYPE detection and resolution (~80 lines)
- %TYPE detection and resolution (~100 lines)
- Column type resolution (~50 lines)
- Circular reference detection
- Error handling

### Total Implementation Effort
- **Code:** ~250-300 lines
- **Tests:** ~400-500 lines
- **Time:** 8-12 hours (including tests)
- **Confidence:** Very High

## Architecture Highlights

### %ROWTYPE Strategy
```
Oracle:     v_emp employees%ROWTYPE;
PostgreSQL: v_emp jsonb := '{}'::jsonb;
            Field assignment: v_emp := jsonb_set(...)
            Field access: (v_emp->>'field')::type
```

### %TYPE Strategy
```
Oracle (column):   v_salary employees.salary%TYPE;
PostgreSQL:        v_salary numeric;

Oracle (variable): v_copy v_salary%TYPE;
PostgreSQL:        v_copy numeric;
```

## File Structure

The implementation will be contained in:

```
VisitVariable_declaration.java
├── resolveRowtypeReference() [NEW]
├── resolveTypeReference() [NEW]
├── resolveColumnTypeReference() [NEW]
└── v() [MODIFIED - lines 78-95]
```

No changes needed to:
- TypeCategory.java
- InlineTypeDefinition.java
- TransformationContext.java
- ANTLR grammar files

## Test Coverage

Tests will include:

**Unit Tests (8-10 tests each)**
- %ROWTYPE: basic, qualified, multiple columns, type conversion
- %TYPE: column refs, variable refs, chained refs, circular detection

**Integration Tests (5 tests each)**
- %ROWTYPE: field assignment, field access, expressions
- %TYPE: variable usage, complex scenarios

## References

Related documentation:
- `INLINE_TYPE_IMPLEMENTATION_PLAN.md` - Phase 1A-1D (RECORD/collections)
- `VARIABLE_SCOPE_TRACKING_PLAN.md` - Variable scope infrastructure
- `TRANSFORMATION.md` - Overall SQL/PL-SQL transformation strategy

Source files to review:
- `src/main/java/.../transformer/inline/TypeCategory.java`
- `src/main/java/.../transformer/inline/InlineTypeDefinition.java`
- `src/main/java/.../transformer/builder/VisitVariable_declaration.java`
- `src/main/java/.../transformer/context/TransformationContext.java`
- `src/main/java/.../transformer/context/TransformationIndices.java`

## Next Steps

1. **Review Documentation**
   - Read EXPLORATION_SUMMARY.md sections 1-2
   - Skim INFRASTRUCTURE_CHECKLIST.md for quick reference

2. **Understand Current Code**
   - Review VisitVariable_declaration.java lines 64-95
   - Study RECORD type implementation as reference

3. **Plan Implementation**
   - Create implementation tasks using CHECKLIST.md
   - Plan test cases using Test Strategy section

4. **Start Implementation**
   - Follow pseudocode in SUMMARY.md section 2
   - Reference RECORD/collection patterns
   - Run tests after each method

5. **Verify Success**
   - All existing tests pass
   - New unit tests pass
   - Integration tests pass with PostgreSQL

---

**Created:** 2025-11-07
**Status:** Exploration Complete, Implementation Ready
**Confidence:** Very High - All infrastructure in place, clear path forward
