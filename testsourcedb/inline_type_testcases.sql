-- ============================================================================
-- INLINE TYPE TEST CASES FOR user_vanessa SCHEMA
-- ============================================================================
-- Purpose: Manual testing for INLINE_TYPE_IMPLEMENTATION_PLAN.md
-- Status: Phase 1A (100%) + Phase 1B (60%) - LHS assignments only
--
-- What's tested:
--    Package-level TYPE declarations (RECORD, TABLE OF, VARRAY, INDEX BY)
--    Block-level TYPE declarations (function-local)
--    Variable declarations ’ jsonb conversion
--    Field assignment (LHS) ’ jsonb_set transformation
--    Nested field assignment (LHS) ’ jsonb_set with paths
--   ó RHS field access (deferred to Phase 1B.5)
-- ============================================================================

-- Create test schema if needed
-- Note: Assumes user_vanessa already exists

-- ============================================================================
-- TEST 1: Package with RECORD Type (Package-level)
-- ============================================================================
-- Tests: RECORD type extraction, field assignment (LHS)
-- Expected PostgreSQL:
--   - TYPE commented out, registered in context
--   - v_range jsonb := '{}'::jsonb
--   - v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(50000))
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg1 AS
  -- Package-level RECORD type
  TYPE salary_range_t IS RECORD (
    min_sal NUMBER,
    max_sal NUMBER,
    currency VARCHAR2(10)
  );

  FUNCTION get_salary_range(p_dept_id NUMBER) RETURN NUMBER;
END inline_type_pkg1;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg1 AS

  FUNCTION get_salary_range(p_dept_id NUMBER) RETURN NUMBER IS
    v_range salary_range_t;
  BEGIN
    -- Test: Variable with inline type ’ jsonb
    -- Test: Field assignment (LHS) ’ jsonb_set
    v_range.min_sal := 50000;
    v_range.max_sal := 150000;
    v_range.currency := 'USD';

    -- Note: RHS field access not yet implemented (Phase 1B.5)
    -- Would be: RETURN v_range.max_sal - v_range.min_sal;
    RETURN 100000; -- Placeholder return
  END get_salary_range;

END inline_type_pkg1;
/

-- ============================================================================
-- TEST 2: Block-level RECORD Type (Function-local)
-- ============================================================================
-- Tests: Block-level TYPE extraction, nested field assignment
-- Expected PostgreSQL:
--   - TYPE commented out, registered in context
--   - v_emp jsonb := '{}'::jsonb
--   - Nested: v_emp := jsonb_set(v_emp, '{address,city}', to_jsonb('Boston'), true)
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg2 AS
  FUNCTION process_employee(p_empno NUMBER) RETURN NUMBER;
END inline_type_pkg2;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg2 AS

  FUNCTION process_employee(p_empno NUMBER) RETURN NUMBER IS
    -- Block-level nested RECORD types
    TYPE address_t IS RECORD (
      street VARCHAR2(100),
      city VARCHAR2(50),
      zipcode VARCHAR2(10)
    );

    TYPE employee_t IS RECORD (
      empno NUMBER,
      ename VARCHAR2(50),
      address address_t
    );

    v_emp employee_t;
  BEGIN
    -- Test: Simple field assignment
    v_emp.empno := p_empno;
    v_emp.ename := 'John Smith';

    -- Test: Nested field assignment ’ jsonb_set with path array
    v_emp.address.street := '123 Main St';
    v_emp.address.city := 'Boston';
    v_emp.address.zipcode := '02101';

    RETURN p_empno;
  END process_employee;

END inline_type_pkg2;
/

-- ============================================================================
-- TEST 3: TABLE OF Type (Collection - Package-level)
-- ============================================================================
-- Tests: TABLE OF extraction, initialization
-- Expected PostgreSQL:
--   - TYPE commented out, registered in context
--   - v_nums jsonb := '[]'::jsonb (array initializer)
-- Note: Array element access not yet implemented (Phase 1C)
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg3 AS
  -- Package-level TABLE OF type
  TYPE num_list_t IS TABLE OF NUMBER;

  FUNCTION sum_numbers RETURN NUMBER;
END inline_type_pkg3;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg3 AS

  FUNCTION sum_numbers RETURN NUMBER IS
    v_nums num_list_t;
  BEGIN
    -- Test: Collection variable ’ jsonb array
    v_nums := num_list_t(10, 20, 30, 40, 50);

    -- Note: Array access and iteration not yet implemented (Phase 1C)
    -- Would be: FOR i IN 1..v_nums.COUNT LOOP total := total + v_nums(i); END LOOP;
    RETURN 150; -- Placeholder return
  END sum_numbers;

END inline_type_pkg3;
/

-- ============================================================================
-- TEST 4: VARRAY Type (Collection with size limit)
-- ============================================================================
-- Tests: VARRAY extraction, size limit registration
-- Expected PostgreSQL:
--   - TYPE commented out, registered with size limit metadata
--   - v_codes jsonb := '[]'::jsonb
-- Note: Size limit not enforced in PostgreSQL (acceptable trade-off)
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg4 AS
  TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);

  FUNCTION get_codes_count RETURN NUMBER;
END inline_type_pkg4;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg4 AS

  FUNCTION get_codes_count RETURN NUMBER IS
    v_codes codes_t;
  BEGIN
    -- Test: VARRAY variable ’ jsonb array
    v_codes := codes_t('A001', 'B002', 'C003');

    -- Note: Collection methods (.COUNT) not yet implemented (Phase 1E)
    -- Would be: RETURN v_codes.COUNT;
    RETURN 3; -- Placeholder return
  END get_codes_count;

END inline_type_pkg4;
/

-- ============================================================================
-- TEST 5: INDEX BY Type (Associative Array)
-- ============================================================================
-- Tests: INDEX BY extraction with string keys
-- Expected PostgreSQL:
--   - TYPE commented out, registered as INDEX_BY category
--   - v_map jsonb := '{}'::jsonb (object initializer)
-- Note: Map access not yet implemented (Phase 1D)
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg5 AS
  TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);

  FUNCTION get_dept_name(p_dept_code VARCHAR2) RETURN VARCHAR2;
END inline_type_pkg5;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg5 AS

  FUNCTION get_dept_name(p_dept_code VARCHAR2) RETURN VARCHAR2 IS
    v_map dept_map_t;
  BEGIN
    -- Test: INDEX BY variable ’ jsonb object
    v_map('DEPT10') := 'Engineering';
    v_map('DEPT20') := 'Sales';
    v_map('DEPT30') := 'Marketing';

    -- Note: Map access not yet implemented (Phase 1D)
    -- Would be: RETURN v_map(p_dept_code);
    RETURN 'Engineering'; -- Placeholder return
  END get_dept_name;

END inline_type_pkg5;
/

-- ============================================================================
-- TEST 6: Multiple Types in One Package
-- ============================================================================
-- Tests: Multiple TYPE declarations in same package spec
-- Expected PostgreSQL:
--   - All three types registered in PackageContext
--   - Variables use appropriate jsonb initializers
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg6 AS
  -- Multiple types in one package
  TYPE config_t IS RECORD (
    timeout NUMBER,
    retries NUMBER,
    enabled VARCHAR2(1)
  );

  TYPE log_entries_t IS TABLE OF VARCHAR2(500);

  TYPE status_map_t IS TABLE OF NUMBER INDEX BY VARCHAR2(50);

  FUNCTION initialize_system RETURN NUMBER;
END inline_type_pkg6;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg6 AS

  FUNCTION initialize_system RETURN NUMBER IS
    v_config config_t;
    v_logs log_entries_t;
    v_status status_map_t;
  BEGIN
    -- Test: Multiple inline type variables in one function
    v_config.timeout := 30;
    v_config.retries := 3;
    v_config.enabled := 'Y';

    v_logs := log_entries_t('System started', 'Config loaded');

    v_status('ORACLE') := 1;
    v_status('POSTGRES') := 1;

    RETURN 1; -- Success
  END initialize_system;

END inline_type_pkg6;
/

-- ============================================================================
-- TEST 7: Deep Nested RECORD Types
-- ============================================================================
-- Tests: Three-level nested field assignment
-- Expected PostgreSQL:
--   - v_company := jsonb_set(v_company, '{department,manager,name}', to_jsonb('Jane Doe'), true)
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg7 AS
  FUNCTION setup_company RETURN NUMBER;
END inline_type_pkg7;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg7 AS

  FUNCTION setup_company RETURN NUMBER IS
    TYPE person_t IS RECORD (
      name VARCHAR2(100),
      title VARCHAR2(50)
    );

    TYPE department_t IS RECORD (
      dept_name VARCHAR2(100),
      manager person_t,
      budget NUMBER
    );

    TYPE company_t IS RECORD (
      company_name VARCHAR2(200),
      department department_t
    );

    v_company company_t;
  BEGIN
    -- Test: Three-level nested field assignment
    v_company.company_name := 'Acme Corp';
    v_company.department.dept_name := 'Engineering';
    v_company.department.manager.name := 'Jane Doe';
    v_company.department.manager.title := 'VP Engineering';
    v_company.department.budget := 1000000;

    RETURN 1;
  END setup_company;

END inline_type_pkg7;
/

-- ============================================================================
-- TEST 8: Mixed Simple and Complex Types
-- ============================================================================
-- Tests: Package with both inline types and regular variables
-- Expected PostgreSQL:
--   - Regular variables: text, numeric (existing behavior)
--   - Inline type variables: jsonb
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg8 AS
  -- Regular package variable (existing functionality)
  g_default_dept VARCHAR2(50) := 'Engineering';

  -- Inline type (new functionality)
  TYPE metrics_t IS RECORD (
    total_count NUMBER,
    success_count NUMBER,
    failure_count NUMBER
  );

  FUNCTION process_batch(p_batch_size NUMBER) RETURN NUMBER;
END inline_type_pkg8;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg8 AS

  FUNCTION process_batch(p_batch_size NUMBER) RETURN NUMBER IS
    v_metrics metrics_t;
    v_status VARCHAR2(20);
    v_count NUMBER := 0;
  BEGIN
    -- Test: Mix of inline types and regular variables
    v_metrics.total_count := p_batch_size;
    v_metrics.success_count := 0;
    v_metrics.failure_count := 0;

    v_status := 'COMPLETED';
    v_count := p_batch_size;

    RETURN v_count;
  END process_batch;

END inline_type_pkg8;
/

-- ============================================================================
-- TEST 9: RECORD with All Common Oracle Types
-- ============================================================================
-- Tests: Type conversion for RECORD fields
-- Expected PostgreSQL:
--   - NUMBER ’ numeric
--   - VARCHAR2 ’ text
--   - DATE ’ timestamp
--   - CLOB ’ text
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg9 AS
  FUNCTION test_type_conversion RETURN NUMBER;
END inline_type_pkg9;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg9 AS

  FUNCTION test_type_conversion RETURN NUMBER IS
    TYPE all_types_t IS RECORD (
      numeric_field NUMBER,
      text_field VARCHAR2(200),
      date_field DATE,
      clob_field CLOB,
      integer_field INTEGER,
      float_field FLOAT,
      char_field CHAR(10)
    );

    v_data all_types_t;
  BEGIN
    -- Test: All common Oracle types in RECORD fields
    v_data.numeric_field := 12345.67;
    v_data.text_field := 'Test String';
    v_data.date_field := SYSDATE;
    v_data.clob_field := 'Large text data';
    v_data.integer_field := 42;
    v_data.float_field := 3.14159;
    v_data.char_field := 'CHAR10';

    RETURN 1;
  END test_type_conversion;

END inline_type_pkg9;
/

-- ============================================================================
-- TEST 10: Empty Inline Type Variable (No assignments)
-- ============================================================================
-- Tests: Variable declaration without any field assignments
-- Expected PostgreSQL:
--   - v_config jsonb := '{}'::jsonb
--   - No jsonb_set calls
-- ============================================================================
CREATE OR REPLACE PACKAGE inline_type_pkg10 AS
  FUNCTION test_empty_var RETURN NUMBER;
END inline_type_pkg10;
/

CREATE OR REPLACE PACKAGE BODY inline_type_pkg10 AS

  FUNCTION test_empty_var RETURN NUMBER IS
    TYPE config_t IS RECORD (
      setting1 VARCHAR2(50),
      setting2 NUMBER
    );

    v_config config_t;
    v_unused NUMBER;
  BEGIN
    -- Test: Variable declared but never used
    -- Should still get jsonb initialization
    v_unused := 100;

    RETURN v_unused;
  END test_empty_var;

END inline_type_pkg10;
/

-- ============================================================================
-- GRANT PERMISSIONS (if needed)
-- ============================================================================
-- GRANT EXECUTE ON inline_type_pkg1 TO PUBLIC;
-- GRANT EXECUTE ON inline_type_pkg2 TO PUBLIC;
-- ... (repeat for all packages)

-- ============================================================================
-- VERIFICATION QUERIES (Run in Oracle to verify creation)
-- ============================================================================
/*
-- Check that all packages compiled successfully
SELECT object_name, object_type, status
FROM user_objects
WHERE object_name LIKE 'INLINE_TYPE_PKG%'
ORDER BY object_name, object_type;

-- Check package specifications
SELECT object_name, object_type, created, last_ddl_time
FROM user_objects
WHERE object_name LIKE 'INLINE_TYPE_PKG%'
  AND object_type = 'PACKAGE'
ORDER BY object_name;

-- Check package bodies
SELECT object_name, object_type, created, last_ddl_time
FROM user_objects
WHERE object_name LIKE 'INLINE_TYPE_PKG%'
  AND object_type = 'PACKAGE BODY'
ORDER BY object_name;

-- Test execution (Oracle side)
SELECT inline_type_pkg1.get_salary_range(10) FROM dual;
SELECT inline_type_pkg2.process_employee(100) FROM dual;
SELECT inline_type_pkg3.sum_numbers FROM dual;
*/

-- ============================================================================
-- EXPECTED POSTGRESQL TRANSFORMATION SUMMARY
-- ============================================================================
/*
After migration, verify:

1. All TYPE declarations commented out with registration comments
2. All inline type variables ’ jsonb
3. Initialization: v_range jsonb := '{}'::jsonb (RECORD) or '[]'::jsonb (collections)
4. Field assignments ’ jsonb_set calls:
   - Simple: v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(50000))
   - Nested: v_emp := jsonb_set(v_emp, '{address,city}', to_jsonb('Boston'), true)
5. Regular variables unchanged (existing behavior)
6. All functions should compile and execute in PostgreSQL

What WON'T work yet (pending Phase 1B.5+):
- RHS field access: x := v_range.max_sal
- Array element access: x := v_nums(1)
- Map access: x := v_map('key')
- Collection methods: v_nums.COUNT, v_nums.EXISTS(1)
*/

-- ============================================================================
-- END OF TEST CASES
-- ============================================================================
