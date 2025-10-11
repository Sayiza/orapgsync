-- ============================================================
-- Oracle Test Data for Constraint Migration Testing
-- Schema: user_robert
-- Tables: departments (2 rows), employees (2 rows)
-- Constraint Types: PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK
-- ============================================================

-- Clean up if exists
BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE user_robert.employees CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE user_robert.departments CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- Create departments table
CREATE TABLE user_robert.departments (
    dept_id NUMBER(10) NOT NULL,
    dept_name VARCHAR2(100) NOT NULL,
    dept_code VARCHAR2(10) NOT NULL,
    budget NUMBER(12,2),
    active_flag CHAR(1) DEFAULT 'Y'
);

-- Create employees table
CREATE TABLE user_robert.employees (
    emp_id NUMBER(10) NOT NULL,
    emp_name VARCHAR2(100) NOT NULL,
    email VARCHAR2(100) NOT NULL,
    dept_id NUMBER(10),
    salary NUMBER(10,2),
    hire_date DATE DEFAULT SYSDATE
);

-- Add PRIMARY KEY constraints
ALTER TABLE user_robert.departments
    ADD CONSTRAINT pk_departments PRIMARY KEY (dept_id);

ALTER TABLE user_robert.employees
    ADD CONSTRAINT pk_employees PRIMARY KEY (emp_id);

-- Add UNIQUE constraints
ALTER TABLE user_robert.departments
    ADD CONSTRAINT uk_dept_code UNIQUE (dept_code);

ALTER TABLE user_robert.employees
    ADD CONSTRAINT uk_emp_email UNIQUE (email);

-- Add FOREIGN KEY constraint
ALTER TABLE user_robert.employees
    ADD CONSTRAINT fk_emp_dept
    FOREIGN KEY (dept_id)
    REFERENCES user_robert.departments(dept_id)
    ON DELETE CASCADE;

-- Add CHECK constraints
ALTER TABLE user_robert.departments
    ADD CONSTRAINT ck_dept_budget CHECK (budget >= 0);

ALTER TABLE user_robert.departments
    ADD CONSTRAINT ck_dept_active CHECK (active_flag IN ('Y', 'N'));

ALTER TABLE user_robert.employees
    ADD CONSTRAINT ck_emp_salary CHECK (salary > 0);

-- Insert test data (departments first due to FK)
INSERT INTO user_robert.departments (dept_id, dept_name, dept_code, budget, active_flag)
VALUES (1, 'Engineering', 'ENG', 500000.00, 'Y');

INSERT INTO user_robert.departments (dept_id, dept_name, dept_code, budget, active_flag)
VALUES (2, 'Sales', 'SALES', 300000.00, 'Y');

-- Insert test data (employees)
INSERT INTO user_robert.employees (emp_id, emp_name, email, dept_id, salary, hire_date)
VALUES (101, 'Alice Johnson', 'alice.johnson@company.com', 1, 75000.00, DATE '2023-01-15');

INSERT INTO user_robert.employees (emp_id, emp_name, email, dept_id, salary, hire_date)
VALUES (102, 'Bob Smith', 'bob.smith@company.com', 2, 68000.00, DATE '2023-03-22');

COMMIT;

-- ============================================================
-- Verification Queries
-- ============================================================

-- Show all constraints
SELECT constraint_name, constraint_type, table_name, search_condition
FROM user_constraints
WHERE owner = 'USER_ROBERT'
ORDER BY table_name, constraint_type, constraint_name;

-- Show constraint columns
SELECT c.constraint_name, c.table_name, cc.column_name, cc.position
FROM user_constraints c
JOIN user_cons_columns cc ON c.constraint_name = cc.constraint_name
WHERE c.owner = 'USER_ROBERT'
ORDER BY c.table_name, c.constraint_name, cc.position;

-- Show data
SELECT * FROM user_robert.departments ORDER BY dept_id;
SELECT * FROM user_robert.employees ORDER BY emp_id;

-- ============================================================
-- Expected Constraint Summary:
-- ============================================================
-- DEPARTMENTS table:
--   - pk_departments: PRIMARY KEY (dept_id)
--   - uk_dept_code: UNIQUE (dept_code)
--   - ck_dept_budget: CHECK (budget >= 0)
--   - ck_dept_active: CHECK (active_flag IN ('Y', 'N'))
--
-- EMPLOYEES table:
--   - pk_employees: PRIMARY KEY (emp_id)
--   - uk_emp_email: UNIQUE (email)
--   - fk_emp_dept: FOREIGN KEY (dept_id) REFERENCES departments(dept_id) ON DELETE CASCADE
--   - ck_emp_salary: CHECK (salary > 0)
--
-- Total: 8 constraints (2 PK, 2 UNIQUE, 1 FK, 3 CHECK)
-- ============================================================
