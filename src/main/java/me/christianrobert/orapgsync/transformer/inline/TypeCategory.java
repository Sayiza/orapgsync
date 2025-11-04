package me.christianrobert.orapgsync.transformer.inline;

/**
 * Categories of inline types that can be defined in Oracle PL/SQL.
 *
 * <p>Oracle allows types to be defined at three levels:</p>
 * <ul>
 *   <li><strong>Schema-level:</strong> CREATE TYPE ... (already handled via composite types)</li>
 *   <li><strong>Package-level:</strong> TYPE declarations in package specifications</li>
 *   <li><strong>Block-level:</strong> TYPE declarations inside functions, procedures, or anonymous blocks</li>
 * </ul>
 *
 * <p>This enum categorizes the different kinds of inline types for proper transformation.</p>
 *
 * <p><strong>Phase 1 Strategy:</strong> All inline types transform to jsonb for consistency and comprehensive coverage.
 * This handles all Oracle complexity including INDEX BY (which has no PostgreSQL native equivalent).</p>
 *
 * @see InlineTypeDefinition
 * @see ConversionStrategy
 */
public enum TypeCategory {
    /**
     * RECORD type - Composite structure with named fields.
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * TYPE salary_range_t IS RECORD (
     *   min_sal NUMBER,
     *   max_sal NUMBER
     * );
     * }</pre>
     *
     * <p>PostgreSQL transformation: jsonb object</p>
     * <pre>{@code
     * v_range jsonb := '{}'::jsonb;
     * v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(1000));
     * }</pre>
     */
    RECORD,

    /**
     * TABLE OF - Dynamic array (nested table).
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * TYPE num_list_t IS TABLE OF NUMBER;
     * }</pre>
     *
     * <p>PostgreSQL transformation: jsonb array</p>
     * <pre>{@code
     * v_nums jsonb := '[10, 20, 30]'::jsonb;
     * v_nums := jsonb_set(v_nums, '{0}', to_jsonb(100)); -- Note: 0-based indexing
     * }</pre>
     */
    TABLE_OF,

    /**
     * VARRAY - Fixed-size array.
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
     * }</pre>
     *
     * <p>PostgreSQL transformation: jsonb array (size limit not enforced)</p>
     * <pre>{@code
     * v_codes jsonb := '["A", "B", "C"]'::jsonb;
     * }</pre>
     */
    VARRAY,

    /**
     * INDEX BY - Associative array (hash map).
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
     * }</pre>
     *
     * <p>PostgreSQL transformation: jsonb object (key-value map)</p>
     * <pre>{@code
     * v_map jsonb := '{}'::jsonb;
     * v_map := jsonb_set(v_map, '{dept10}', to_jsonb('Engineering'));
     * x := v_map->>'dept10';
     * }</pre>
     *
     * <p><strong>Note:</strong> PostgreSQL has no native associative array type, so jsonb is the only option.</p>
     */
    INDEX_BY,

    /**
     * %ROWTYPE - Reference to a table's row structure.
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * v_emp employees%ROWTYPE;
     * v_emp.empno := 100;
     * }</pre>
     *
     * <p>PostgreSQL transformation: jsonb object with table columns</p>
     * <pre>{@code
     * v_emp jsonb := '{}'::jsonb;
     * v_emp := jsonb_set(v_emp, '{empno}', to_jsonb(100));
     * }</pre>
     *
     * <p><strong>Resolution:</strong> Table structure obtained from TransformationIndices metadata.</p>
     */
    ROWTYPE,

    /**
     * %TYPE - Reference to another variable's or column's type.
     *
     * <p>Oracle example:</p>
     * <pre>{@code
     * v_salary employees.salary%TYPE;
     * v_copy v_salary%TYPE;
     * }</pre>
     *
     * <p>PostgreSQL transformation: Resolve to underlying type, then apply same rules as base type.</p>
     */
    TYPE_REFERENCE
}
