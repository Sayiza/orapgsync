package me.christianrobert.orapgsync.transformer.context;

/**
 * Represents an inline type definition (FUTURE - stub for now).
 *
 * <p>Inline types are defined locally within a PL/SQL function or procedure,
 * such as RECORD types or collection types. These are distinct from:
 * <ul>
 *   <li>Global types (schema-level object types in TransformationIndices)</li>
 *   <li>Package types (package-level types in PackageContext)</li>
 * </ul>
 *
 * <p><strong>Future Use Case Example:</strong></p>
 * <pre>
 * CREATE FUNCTION calculate_bonus(p_emp_id NUMBER) RETURN NUMBER AS
 *   -- Inline type definition (local to this function)
 *   TYPE salary_breakdown_t IS RECORD (
 *     base_salary NUMBER,
 *     bonus NUMBER,
 *     total NUMBER
 *   );
 *
 *   v_salary salary_breakdown_t;
 * BEGIN
 *   SELECT base_sal, bonus_amt, total_comp
 *   INTO v_salary.base_salary, v_salary.bonus, v_salary.total
 *   FROM emp_summary WHERE emp_id = p_emp_id;
 *
 *   RETURN v_salary.total * 0.10;
 * END;
 * </pre>
 *
 * <p><strong>Type Resolution Cascade (with inline types):</strong></p>
 * <ol>
 *   <li>Check inline types (function-local) - highest precedence</li>
 *   <li>Check package types (package-level)</li>
 *   <li>Check global types (schema-level from TransformationIndices)</li>
 *   <li>Built-in types (NUMBER, VARCHAR2, etc.)</li>
 * </ol>
 *
 * <p><strong>Status:</strong> STUB - infrastructure ready, implementation deferred until needed.</p>
 *
 * @since 2025-11-02 (as stub for future inline type support)
 */
public class InlineTypeDefinition {

    private final String typeName;
    private final String postgresType;

    /**
     * Creates an inline type definition.
     *
     * @param typeName Oracle type name (e.g., "salary_breakdown_t")
     * @param postgresType PostgreSQL equivalent type (e.g., composite type name or ROW type)
     */
    public InlineTypeDefinition(String typeName, String postgresType) {
        this.typeName = typeName;
        this.postgresType = postgresType;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getPostgresType() {
        return postgresType;
    }

    @Override
    public String toString() {
        return "InlineTypeDefinition{" +
               "typeName='" + typeName + '\'' +
               ", postgresType='" + postgresType + '\'' +
               '}';
    }
}
