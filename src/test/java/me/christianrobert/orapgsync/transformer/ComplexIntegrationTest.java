package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests combining multiple complex Oracle transformations.
 *
 * <p>These tests ensure that different transformation features work correctly
 * when combined in realistic, complex queries. Real-world Oracle SQL often
 * mixes multiple features in a single query, and we need to verify that:
 * <ul>
 *   <li>Transformations don't interfere with each other</li>
 *   <li>Visitor traversal handles deeply nested structures correctly</li>
 *   <li>Edge cases that only appear with feature combinations are handled</li>
 * </ul>
 *
 * <p><b>Features being tested in combination:</b>
 * <ul>
 *   <li>(+) Outer join syntax → ANSI JOIN</li>
 *   <li>CTEs (WITH clause) → WITH (recursive detection)</li>
 *   <li>ROWNUM → LIMIT</li>
 *   <li>String functions (INSTR, LPAD, RPAD, TRANSLATE)</li>
 *   <li>Date functions (ADD_MONTHS, TRUNC, ROUND, MONTHS_BETWEEN, LAST_DAY)</li>
 *   <li>SYSDATE → CURRENT_TIMESTAMP</li>
 *   <li>FROM DUAL removal</li>
 *   <li>Subqueries</li>
 *   <li>Aggregation and GROUP BY</li>
 * </ul>
 */
public class ComplexIntegrationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Outer Join + Date Functions + String Functions ====================

    @Test
    void outerJoinWithDateAndStringFunctions() {
        // Given: Complex query with (+) outer join, date functions, and string functions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "SELECT " +
            "  e.empno, " +
            "  LPAD(e.emp_id, 10, '0') AS formatted_id, " +
            "  INSTR(e.email, '@') AS at_position, " +
            "  TRUNC(e.hire_date) AS hire_date_trunc, " +
            "  ADD_MONTHS(e.hire_date, 6) AS review_date, " +
            "  d.dept_name " +
            "FROM employees e, departments d " +
            "WHERE e.dept_id = d.dept_id(+) " +
            "  AND TRUNC(e.hire_date) > TRUNC(SYSDATE) - 365 " +
            "  AND INSTR(e.email, '@company.com') > 0";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Debug output
        System.out.println("\n=== TEST: outerJoinWithDateAndStringFunctions ===");
        System.out.println("ORACLE SQL:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL SQL:");
        System.out.println(postgresSql);
        System.out.println("==================================================\n");

        // Then: All transformations should work together
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Outer join transformation
        assertTrue(normalized.contains("LEFT JOIN"),
            "Should transform (+) to LEFT JOIN, got: " + normalized);
        assertTrue(normalized.contains("ON e . dept_id = d . dept_id"),
            "Should have ON clause, got: " + normalized);

        // String functions
        assertTrue(normalized.contains("LPAD( e . emp_id , 10 , '0' )"),
            "LPAD should be transformed, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '@' IN e . email )"),
            "INSTR should be transformed to POSITION, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '@company.com' IN e . email )"),
            "Second INSTR should also be transformed, got: " + normalized);

        // Date functions
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , e . hire_date )::DATE"),
            "TRUNC should be transformed, got: " + normalized);
        assertTrue(normalized.contains("e . hire_date + INTERVAL '6 months'"),
            "ADD_MONTHS should be transformed, got: " + normalized);
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"),
            "SYSDATE should be transformed, got: " + normalized);

        // Schema qualification
        assertTrue(normalized.contains("FROM hr.employees e"),
            "Should have schema qualification, got: " + normalized);
    }

    // ==================== CTE + Outer Join + ROWNUM ====================

    @Test
    void cteWithOuterJoinAndRownum() {
        // Given: CTE with outer join and ROWNUM limit
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "WITH recent_hires AS ( " +
            "  SELECT e.empno, e.emp_name, e.hire_date, e.dept_id " +
            "  FROM employees e " +
            "  WHERE TRUNC(e.hire_date) >= ADD_MONTHS(SYSDATE, -6) " +
            ") " +
            "SELECT " +
            "  rh.empno, " +
            "  RPAD(rh.emp_name, 30, ' ') AS padded_name, " +
            "  rh.hire_date, " +
            "  d.dept_name " +
            "FROM recent_hires rh, departments d " +
            "WHERE rh.dept_id = d.dept_id(+) " +
            "  AND ROWNUM <= 10 " +
            "ORDER BY rh.hire_date DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Debug output
        System.out.println("\n=== TEST: cteWithOuterJoinAndRownum ===");
        System.out.println("ORACLE SQL:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL SQL:");
        System.out.println(postgresSql);
        System.out.println("==================================================\n");

        // Then: All transformations should work together
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // CTE transformation
        assertTrue(normalized.contains("WITH recent_hires AS ("),
            "CTE should be preserved, got: " + normalized);

        // Date functions inside CTE
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , e . hire_date )::DATE"),
            "TRUNC in CTE should be transformed, got: " + normalized);
        assertTrue(normalized.contains("CURRENT_TIMESTAMP + INTERVAL '-6 months'"),
            "ADD_MONTHS with SYSDATE in CTE should be transformed, got: " + normalized);

        // Outer join in main query
        assertTrue(normalized.contains("LEFT JOIN"),
            "Should transform (+) to LEFT JOIN, got: " + normalized);

        // String function
        assertTrue(normalized.contains("RPAD( rh . emp_name , 30 , ' ' )"),
            "RPAD should be transformed, got: " + normalized);

        // ROWNUM → LIMIT
        assertTrue(normalized.contains("LIMIT 10"),
            "ROWNUM should be transformed to LIMIT, got: " + normalized);
        assertFalse(normalized.contains("ROWNUM"),
            "ROWNUM should be removed from WHERE, got: " + normalized);

        // ORDER BY preserved
        assertTrue(normalized.contains("ORDER BY rh . hire_date DESC"),
            "ORDER BY should be preserved, got: " + normalized);
    }

    // ==================== Nested CTEs + Multiple Outer Joins + Complex Functions ====================

    @Test
    void nestedCTEsWithMultipleOuterJoinsAndComplexFunctions() {
        // Given: Very complex query with nested CTEs, chained outer joins, and multiple function types
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "WITH " +
            "  active_employees AS ( " +
            "    SELECT empno, emp_name, dept_id, hire_date, email " +
            "    FROM employees " +
            "    WHERE status = 'ACTIVE' " +
            "      AND MONTHS_BETWEEN(SYSDATE, hire_date) >= 6 " +
            "  ), " +
            "  formatted_data AS ( " +
            "    SELECT " +
            "      empno, " +
            "      TRANSLATE(LPAD(empno, 8, '0'), ' ', '0') AS formatted_empno, " +
            "      emp_name, " +
            "      dept_id, " +
            "      ROUND(hire_date, 'MM') AS hire_month, " +
            "      INSTR(email, '@', 1, 1) AS email_at_pos " +
            "    FROM active_employees " +
            "  ) " +
            "SELECT " +
            "  fd.formatted_empno, " +
            "  fd.emp_name, " +
            "  d.dept_name, " +
            "  l.location_name, " +
            "  fd.hire_month, " +
            "  LAST_DAY(fd.hire_month) AS month_end " +
            "FROM formatted_data fd, departments d, locations l " +
            "WHERE fd.dept_id = d.dept_id(+) " +
            "  AND d.location_id = l.location_id(+) " +
            "  AND fd.email_at_pos > 0 " +
            "  AND ROWNUM <= 25";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All transformations should work together correctly
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Multiple CTEs
        assertTrue(normalized.contains("WITH active_employees AS ("),
            "First CTE should be preserved, got: " + normalized);
        assertTrue(normalized.contains(", formatted_data AS ("),
            "Second CTE should be preserved, got: " + normalized);

        // Date functions in first CTE
        assertTrue(normalized.contains("EXTRACT( YEAR FROM AGE( CURRENT_TIMESTAMP , hire_date ) ) * 12 + EXTRACT( MONTH FROM AGE( CURRENT_TIMESTAMP , hire_date ) )"),
            "MONTHS_BETWEEN with SYSDATE should be transformed, got: " + normalized);

        // Complex nested functions in second CTE
        assertTrue(normalized.contains("TRANSLATE( LPAD( empno , 8 , '0' ) , ' ' , '0' )"),
            "Nested TRANSLATE(LPAD(...)) should be transformed, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '@' IN email )"),
            "INSTR should be transformed to POSITION, got: " + normalized);

        // Date ROUND in CTE
        assertTrue(normalized.contains("CASE WHEN EXTRACT( DAY FROM hire_date ) >= 16"),
            "ROUND(date, 'MM') should use CASE WHEN, got: " + normalized);

        // Chained outer joins in main query
        int leftJoinCount = normalized.split("LEFT JOIN", -1).length - 1;
        assertEquals(2, leftJoinCount,
            "Should have 2 LEFT JOINs for chained (+) joins, got: " + normalized);
        assertTrue(normalized.contains("LEFT JOIN hr.departments d ON fd . dept_id = d . dept_id"),
            "First outer join should be transformed, got: " + normalized);
        assertTrue(normalized.contains("LEFT JOIN hr.locations l ON d . location_id = l . location_id"),
            "Second outer join should be transformed, got: " + normalized);

        // LAST_DAY transformation
        assertTrue(normalized.contains("DATE_TRUNC( 'MONTH' , fd . hire_month ) + INTERVAL '1 month' - INTERVAL '1 day' )::DATE"),
            "LAST_DAY should be transformed, got: " + normalized);

        // ROWNUM → LIMIT
        assertTrue(normalized.contains("LIMIT 25"),
            "ROWNUM should be transformed to LIMIT, got: " + normalized);
    }

    // ==================== Subquery + Outer Join + String Functions ====================

    @Test
    void subqueryWithOuterJoinAndStringFunctions() {
        // Given: Subquery in WHERE with outer join and string functions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "SELECT " +
            "  e.empno, " +
            "  RPAD(e.emp_name, 20, '.') AS name, " +
            "  d.dept_name " +
            "FROM employees e, departments d " +
            "WHERE e.dept_id = d.dept_id(+) " +
            "  AND e.empno IN ( " +
            "    SELECT s.empno " +
            "    FROM salaries s " +
            "    WHERE s.amount > 50000 " +
            "      AND INSTR(s.currency, 'USD') > 0 " +
            "  ) " +
            "  AND TRUNC(e.hire_date, 'YYYY') = TRUNC(ADD_MONTHS(SYSDATE, -12), 'YYYY')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All transformations should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Outer join
        assertTrue(normalized.contains("LEFT JOIN hr.departments d ON e . dept_id = d . dept_id"),
            "Outer join should be transformed, got: " + normalized);

        // String function in main query
        assertTrue(normalized.contains("RPAD( e . emp_name , 20 , '.' )"),
            "RPAD should be transformed, got: " + normalized);

        // Subquery should be preserved
        assertTrue(normalized.contains("IN ( SELECT"),
            "Subquery should be preserved, got: " + normalized);

        // String function in subquery
        assertTrue(normalized.contains("POSITION( 'USD' IN s . currency )"),
            "INSTR in subquery should be transformed, got: " + normalized);

        // Date functions in WHERE
        assertTrue(normalized.contains("DATE_TRUNC( 'year' , e . hire_date )::DATE"),
            "TRUNC in WHERE should be transformed, got: " + normalized);
        assertTrue(normalized.contains("DATE_TRUNC( 'year' , CURRENT_TIMESTAMP + INTERVAL '-12 months' )::DATE"),
            "Nested ADD_MONTHS/TRUNC should be transformed, got: " + normalized);
    }

    // ==================== All Features Combined ====================

    @Test
    void kitchenSinkQuery() {
        // Given: The most complex query combining ALL transformations
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "WITH " +
            "  dept_summary AS ( " +
            "    SELECT " +
            "      d.dept_id, " +
            "      d.dept_name, " +
            "      COUNT(*) AS emp_count, " +
            "      TRUNC(MIN(e.hire_date)) AS first_hire " +
            "    FROM departments d, employees e " +
            "    WHERE d.dept_id = e.dept_id(+) " +
            "      AND e.status = 'ACTIVE' " +
            "    GROUP BY d.dept_id, d.dept_name " +
            "  ) " +
            "SELECT " +
            "  LPAD(ds.dept_id, 5, '0') AS dept_code, " +
            "  TRANSLATE(ds.dept_name, ' ', '_') AS dept_key, " +
            "  ds.emp_count, " +
            "  ROUND(ds.first_hire, 'YYYY') AS hire_year, " +
            "  MONTHS_BETWEEN(SYSDATE, ds.first_hire) AS months_active, " +
            "  CASE " +
            "    WHEN INSTR(ds.dept_name, 'Engineering') > 0 THEN 'TECH' " +
            "    WHEN INSTR(ds.dept_name, 'Sales') > 0 THEN 'SALES' " +
            "    ELSE 'OTHER' " +
            "  END AS dept_category, " +
            "  LAST_DAY(ADD_MONTHS(SYSDATE, 1)) AS next_month_end " +
            "FROM dept_summary ds " +
            "WHERE ds.emp_count > 5 " +
            "  AND TRUNC(ds.first_hire, 'MM') >= ADD_MONTHS(TRUNC(SYSDATE, 'YYYY'), -36) " +
            "  AND ROWNUM <= 50 " +
            "ORDER BY ds.emp_count DESC, ds.dept_name";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrorMessage());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Comprehensive validation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // CTE
        assertTrue(normalized.contains("WITH dept_summary AS ("),
            "CTE should be preserved, got: " + normalized);

        // Outer join in CTE
        assertTrue(normalized.contains("LEFT JOIN"),
            "Outer join in CTE should be transformed, got: " + normalized);

        // GROUP BY in CTE
        assertTrue(normalized.contains("GROUP BY d . dept_id , d . dept_name"),
            "GROUP BY should be preserved, got: " + normalized);

        // String functions (LPAD, TRANSLATE, INSTR x2)
        assertTrue(normalized.contains("LPAD( ds . dept_id , 5 , '0' )"),
            "LPAD should be transformed, got: " + normalized);
        assertTrue(normalized.contains("TRANSLATE( ds . dept_name , ' ' , '_' )"),
            "TRANSLATE should be transformed, got: " + normalized);

        int instrCount = normalized.split("POSITION\\(", -1).length - 1;
        assertEquals(2, instrCount,
            "Should have 2 POSITION calls (from 2 INSTR), got: " + normalized);

        // Date functions (TRUNC x4, ROUND, MONTHS_BETWEEN, ADD_MONTHS x2, LAST_DAY, SYSDATE x4)
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , MIN( e . hire_date ) )::DATE"),
            "TRUNC(MIN(...)) in CTE should be transformed, got: " + normalized);
        assertTrue(normalized.contains("CASE WHEN EXTRACT( DAY FROM ds . first_hire ) >= 16"),
            "ROUND(date, 'YYYY') should use CASE WHEN, got: " + normalized);
        assertTrue(normalized.contains("EXTRACT( YEAR FROM AGE( CURRENT_TIMESTAMP , ds . first_hire ) ) * 12 + EXTRACT( MONTH FROM AGE( CURRENT_TIMESTAMP , ds . first_hire ) )"),
            "MONTHS_BETWEEN should be transformed, got: " + normalized);

        // Complex nested date functions
        assertTrue(normalized.contains("DATE_TRUNC( 'MONTH' , CURRENT_TIMESTAMP + INTERVAL '1 months' ) + INTERVAL '1 month' - INTERVAL '1 day' )::DATE"),
            "LAST_DAY(ADD_MONTHS(SYSDATE, 1)) should be transformed, got: " + normalized);

        // CASE expression
        assertTrue(normalized.contains("CASE WHEN"),
            "CASE expression should be preserved, got: " + normalized);

        // ROWNUM → LIMIT
        assertTrue(normalized.contains("LIMIT 50"),
            "ROWNUM should be transformed to LIMIT, got: " + normalized);
        assertFalse(normalized.contains("ROWNUM"),
            "ROWNUM should be removed, got: " + normalized);

        // ORDER BY
        assertTrue(normalized.contains("ORDER BY ds . emp_count DESC , ds . dept_name"),
            "ORDER BY should be preserved, got: " + normalized);

        // Schema qualification
        assertTrue(normalized.contains("FROM hr.departments d"),
            "Tables should have schema qualification, got: " + normalized);

        // Verify no Oracle-specific syntax remains
        assertFalse(normalized.contains("(+)"),
            "No (+) should remain, got: " + normalized);
        assertFalse(normalized.contains("SYSDATE"),
            "No SYSDATE should remain, got: " + normalized);
        assertFalse(normalized.contains("INSTR("),
            "No INSTR should remain, got: " + normalized);
    }

    // ==================== Edge Case: Outer Join with Multiple String Functions ====================

    @Test
    void outerJoinWithNestedStringFunctions() {
        // Given: Complex nested string functions with outer join
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "SELECT " +
            "  RPAD(TRANSLATE(LPAD(e.emp_id, 8, '0'), ' ', '_'), 12, '-') AS complex_id, " +
            "  d.dept_name " +
            "FROM employees e, departments d " +
            "WHERE e.dept_id = d.dept_id(+) " +
            "  AND INSTR(TRANSLATE(e.email, '@.', '__'), '__') = INSTR(e.email, '@')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Deeply nested functions should all be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Outer join
        assertTrue(normalized.contains("LEFT JOIN"),
            "Outer join should be transformed, got: " + normalized);

        // Verify all string function nesting is preserved
        assertTrue(normalized.contains("RPAD( TRANSLATE( LPAD("),
            "Triple-nested string functions should be transformed, got: " + normalized);

        // Both TRANSLATE calls should be transformed
        int translateCount = normalized.split("TRANSLATE\\(", -1).length - 1;
        assertEquals(2, translateCount,
            "Should have 2 TRANSLATE calls, got: " + normalized);

        // Both INSTR calls should be transformed to POSITION
        int positionCount = normalized.split("POSITION\\(", -1).length - 1;
        assertEquals(2, positionCount,
            "Should have 2 POSITION calls (from 2 INSTR), got: " + normalized);
    }

    // ==================== Edge Case: CTE with Recursive Detection + Outer Join ====================

    @Test
    void recursiveCTEWithOuterJoinAndDateFunctions() {
        // Given: Recursive CTE with outer join and date functions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql =
            "WITH employee_hierarchy AS ( " +
            "  SELECT empno, emp_name, manager_id, hire_date, 1 AS level " +
            "  FROM employees " +
            "  WHERE manager_id IS NULL " +
            "  UNION ALL " +
            "  SELECT e.empno, e.emp_name, e.manager_id, e.hire_date, eh.level + 1 " +
            "  FROM employees e, employee_hierarchy eh " +
            "  WHERE e.manager_id = eh.empno " +
            ") " +
            "SELECT " +
            "  eh.empno, " +
            "  LPAD(' ', eh.level * 2, ' ') || eh.emp_name AS indented_name, " +
            "  d.dept_name, " +
            "  MONTHS_BETWEEN(SYSDATE, eh.hire_date) AS tenure_months " +
            "FROM employee_hierarchy eh, departments d " +
            "WHERE eh.empno = d.manager_empno(+) " +
            "  AND eh.level <= 5 " +
            "ORDER BY eh.level, eh.emp_name";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Recursive CTE should be detected and marked
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Recursive keyword should be added
        assertTrue(normalized.contains("WITH RECURSIVE employee_hierarchy AS ("),
            "Should add RECURSIVE keyword, got: " + normalized);

        // UNION ALL should be preserved
        assertTrue(normalized.contains("UNION ALL"),
            "UNION ALL should be preserved, got: " + normalized);

        // Outer join in main query
        assertTrue(normalized.contains("LEFT JOIN"),
            "Outer join should be transformed, got: " + normalized);

        // String function with concatenation
        assertTrue(normalized.contains("LPAD( ' ' , eh . level * 2 , ' ' )"),
            "LPAD should be transformed, got: " + normalized);
        assertTrue(normalized.contains("CONCAT("),
            "|| should be transformed to CONCAT, got: " + normalized);

        // Date function
        assertTrue(normalized.contains("EXTRACT( YEAR FROM AGE( CURRENT_TIMESTAMP , eh . hire_date ) ) * 12"),
            "MONTHS_BETWEEN should be transformed, got: " + normalized);
    }
}
