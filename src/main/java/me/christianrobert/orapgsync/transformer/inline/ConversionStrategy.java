package me.christianrobert.orapgsync.transformer.inline;

/**
 * Strategy for converting Oracle inline types to PostgreSQL equivalents.
 *
 * <p><strong>Phase 1:</strong> JSON-first approach - All inline types convert to jsonb for consistency
 * and comprehensive Oracle feature coverage.</p>
 *
 * <p><strong>Phase 2 (Future):</strong> If profiling shows performance issues, optimize simple cases
 * to native PostgreSQL types while keeping complex types as jsonb.</p>
 *
 * <h3>Why JSON-First?</h3>
 * <ul>
 *   <li><strong>Comprehensive coverage:</strong> Handles all Oracle complexity including INDEX BY (no PostgreSQL equivalent)</li>
 *   <li><strong>Consistent approach:</strong> Single access pattern, single transformation logic</li>
 *   <li><strong>Infrastructure exists:</strong> Similar to existing complex type handling (ANYDATA, XMLTYPE → jsonb)</li>
 *   <li><strong>Pragmatic:</strong> Inline types typically not in performance-critical paths</li>
 * </ul>
 *
 * @see InlineTypeDefinition
 * @see TypeCategory
 */
public enum ConversionStrategy {
    /**
     * Convert to PostgreSQL jsonb type.
     *
     * <p><strong>Phase 1 (Current):</strong> ALL inline types use this strategy.</p>
     *
     * <p>Supports:</p>
     * <ul>
     *   <li>RECORD → jsonb object: {@code '{}'::jsonb}</li>
     *   <li>TABLE OF → jsonb array: {@code '[]'::jsonb}</li>
     *   <li>VARRAY → jsonb array: {@code '[]'::jsonb}</li>
     *   <li>INDEX BY → jsonb object: {@code '{}'::jsonb}</li>
     *   <li>%ROWTYPE → jsonb object with table columns</li>
     *   <li>Nested types → jsonb nested structures</li>
     * </ul>
     *
     * <p>Access patterns:</p>
     * <ul>
     *   <li>Field access (RHS): {@code v.field} → {@code (v->>'field')::type}</li>
     *   <li>Field assignment (LHS): {@code v.field := value} → {@code v := jsonb_set(v, '{field}', to_jsonb(value))}</li>
     *   <li>Array access (RHS): {@code v(i)} → {@code (v->(i-1))::type} (Oracle 1-based → JSON 0-based)</li>
     *   <li>Array assignment (LHS): {@code v(i) := value} → {@code v := jsonb_set(v, '{\(i-1\)}', to_jsonb(value))}</li>
     *   <li>Map access (RHS): {@code v('key')} → {@code v->>'key'}</li>
     *   <li>Map assignment (LHS): {@code v('key') := value} → {@code v := jsonb_set(v, '{key}', to_jsonb(value))}</li>
     * </ul>
     */
    JSONB,

    /**
     * Convert to PostgreSQL array type.
     *
     * <p><strong>Phase 2 (Future):</strong> Optimize simple TABLE OF primitives to native arrays.</p>
     *
     * <p>Example: {@code TYPE num_list_t IS TABLE OF NUMBER;} → {@code numeric[]}</p>
     *
     * <p>Benefits: Better performance, native array operations, familiar PostgreSQL syntax.</p>
     *
     * <p>Limitations: Only for simple collections of primitives, no INDEX BY support.</p>
     */
    ARRAY,

    /**
     * Convert to PostgreSQL composite type.
     *
     * <p><strong>Phase 2 (Future):</strong> Optimize simple RECORD types to inline composite types.</p>
     *
     * <p>Example: {@code TYPE point_t IS RECORD (x NUMBER, y NUMBER);} → anonymous composite type</p>
     *
     * <p>Benefits: Type safety, better performance, native PostgreSQL support.</p>
     *
     * <p>Limitations: Only for simple records, no nested complex types.</p>
     */
    COMPOSITE
}
