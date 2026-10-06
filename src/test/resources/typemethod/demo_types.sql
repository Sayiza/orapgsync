--------------------------------------------------------------------------------
-- Neutral object-type fixture for type-method transformation.
--
-- Replaces an earlier fixture taken from a customer schema. Nothing here is
-- derived from real data: the domain is a deliberately dull order-line/tag pair.
--
-- Purpose: exercise every *transformable* row of the coverage matrix in
-- documentation/TYPE_METHOD_IMPLEMENTATION_PLAN.md section 3.1. Constructs that
-- must be rejected instead live in demo_types_rejected.sql.
--
-- DEMO_ITEM_T is deliberately multi-attribute, so it also triggers the section 6
-- warning condition: a MAP method on a type with more than one attribute, where
-- PostgreSQL's field-wise comparison and Oracle's map-value comparison diverge
-- silently. A single-attribute type would have hidden that case.
--
-- MAP and ORDER are mutually exclusive on one Oracle type, hence two types:
-- DEMO_ITEM_T carries the MAP method, DEMO_TAG_T carries the ORDER method.
--
-- Status: parses clean via type_body_elements (see TypeBodyElementsGrammarTest).
-- NOT yet round-tripped through a live Oracle instance -- see the note at the
-- foot of this file before relying on it to generate dictionary rows.
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- DEMO_ITEM_T -- constructors, member fn/proc, static fn/proc, MAP
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_item_t AS OBJECT (
    item_code    varchar2(30),
    qty          number,
    unit_price   number,

    -- constructor_declaration: no arguments
    CONSTRUCTOR FUNCTION demo_item_t
        RETURN SELF AS RESULT,

    -- constructor_declaration: implicit SELF
    CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2)
        RETURN SELF AS RESULT,

    -- constructor_declaration: explicit SELF IN OUT first parameter.
    -- Distinct remaining parameters from the overload above -- SELF is implicit
    -- either way, so identical remaining parameters would be a duplicate signature.
    CONSTRUCTOR FUNCTION demo_item_t(SELF IN OUT NOCOPY demo_item_t,
                                     p_code varchar2,
                                     p_qty  number)
        RETURN SELF AS RESULT,

    -- subprog_decl_in_type: MEMBER FUNCTION, read-only SELF, DETERMINISTIC
    MEMBER FUNCTION line_total RETURN number DETERMINISTIC,

    -- subprog_decl_in_type: MEMBER FUNCTION with a parameter
    MEMBER FUNCTION describe(p_separator varchar2) RETURN varchar2,

    -- subprog_decl_in_type: MEMBER PROCEDURE -- SELF is implicitly IN OUT.
    -- This is the mutating case that needs the statement-level call-site rewrite.
    MEMBER PROCEDURE add_qty(p_qty number),

    -- subprog_decl_in_type: MEMBER PROCEDURE with an OUT NOCOPY parameter
    MEMBER PROCEDURE apply_discount(p_pct number, p_new_total OUT NOCOPY number),

    -- subprog_decl_in_type: STATIC FUNCTION -- no SELF at all
    STATIC FUNCTION empty_item RETURN demo_item_t,

    -- subprog_decl_in_type: STATIC PROCEDURE
    STATIC PROCEDURE describe_all(p_left demo_item_t, p_right demo_item_t),

    -- map_order_func_declaration: MAP
    MAP MEMBER FUNCTION sort_key RETURN number
);
/

CREATE OR REPLACE TYPE BODY demo_item_t IS

    CONSTRUCTOR FUNCTION demo_item_t
        RETURN SELF AS RESULT
    IS
    BEGIN
        self.item_code  := NULL;
        self.qty        := 0;
        self.unit_price := 0;
        RETURN;
    END;

    CONSTRUCTOR FUNCTION demo_item_t(p_code varchar2)
        RETURN SELF AS RESULT
    IS
    BEGIN
        IF p_code IS NULL THEN
            raise_application_error(-20001, 'item_code must not be null');
        END IF;

        self.item_code  := upper(p_code);
        self.qty        := 0;
        self.unit_price := 0;
        RETURN;
    END;

    CONSTRUCTOR FUNCTION demo_item_t(SELF IN OUT NOCOPY demo_item_t,
                                     p_code varchar2,
                                     p_qty  number)
        RETURN SELF AS RESULT
    IS
    BEGIN
        self.item_code  := upper(p_code);
        self.qty        := nvl(p_qty, 0);
        self.unit_price := 0;
        RETURN;
    END;

    MEMBER FUNCTION line_total RETURN number DETERMINISTIC
    IS
    BEGIN
        -- read-only field access on SELF
        RETURN nvl(self.qty, 0) * nvl(self.unit_price, 0);
    END;

    MEMBER FUNCTION describe(p_separator varchar2) RETURN varchar2
    IS
        l_label varchar2(100);
    BEGIN
        -- local variable, and a call to another member method on SELF
        l_label := self.item_code || p_separator || to_char(self.qty);

        RETURN CASE
                 WHEN self.qty = 0 THEN l_label || p_separator || 'EMPTY'
                 ELSE l_label || p_separator || to_char(self.line_total())
               END;
    END;

    MEMBER PROCEDURE add_qty(p_qty number)
    IS
    BEGIN
        -- field assignment on SELF: the mutating case
        self.qty := nvl(self.qty, 0) + nvl(p_qty, 0);
    END;

    MEMBER PROCEDURE apply_discount(p_pct number, p_new_total OUT NOCOPY number)
    IS
    BEGIN
        self.unit_price := nvl(self.unit_price, 0) * (1 - nvl(p_pct, 0) / 100);
        p_new_total     := self.line_total();
    END;

    STATIC FUNCTION empty_item RETURN demo_item_t
    IS
    BEGIN
        -- constructor call from inside the type body, with NEW
        RETURN NEW demo_item_t();
    END;

    STATIC PROCEDURE describe_all(p_left demo_item_t, p_right demo_item_t)
    IS
    BEGIN
        -- instance method called on parameters, not on SELF
        dbms_output.put_line(p_left.describe(', '));
        dbms_output.put_line(p_right.describe(', '));
    END;

    MAP MEMBER FUNCTION sort_key RETURN number
    IS
    BEGIN
        RETURN nvl(self.qty, 0) * nvl(self.unit_price, 0);
    END;

END;
/

--------------------------------------------------------------------------------
-- DEMO_TAG_T -- ORDER method (mutually exclusive with MAP, hence a second type)
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_tag_t AS OBJECT (
    tag_name  varchar2(50),
    priority  number,

    CONSTRUCTOR FUNCTION demo_tag_t(p_name varchar2, p_priority number)
        RETURN SELF AS RESULT,

    MEMBER FUNCTION label RETURN varchar2,

    -- map_order_func_declaration: ORDER. Takes one argument of the same type
    -- and returns a negative / zero / positive integer.
    ORDER MEMBER FUNCTION compare_to(p_other demo_tag_t) RETURN integer
);
/

CREATE OR REPLACE TYPE BODY demo_tag_t IS

    CONSTRUCTOR FUNCTION demo_tag_t(p_name varchar2, p_priority number)
        RETURN SELF AS RESULT
    IS
    BEGIN
        self.tag_name := p_name;
        self.priority := nvl(p_priority, 0);
        RETURN;
    END;

    MEMBER FUNCTION label RETURN varchar2
    IS
    BEGIN
        RETURN self.tag_name || ' (' || to_char(self.priority) || ')';
    END;

    ORDER MEMBER FUNCTION compare_to(p_other demo_tag_t) RETURN integer
    IS
    BEGIN
        -- field access on a parameter of the same object type
        IF self.priority < p_other.priority THEN
            RETURN -1;
        ELSIF self.priority > p_other.priority THEN
            RETURN 1;
        ELSE
            RETURN 0;
        END IF;
    END;

END;
/

--------------------------------------------------------------------------------
-- Coverage matrix rows NOT represented here, and why
--------------------------------------------------------------------------------
-- %TYPE / %ROWTYPE parameters   -- would require a table, which would make this
--                                  fixture no longer self-contained. Cover them in
--                                  a unit test against type_elements_parameter.
-- DEFAULT parameter values      -- type_elements_parameter admits default_value_part
--                                  and it parses, but object-type methods are more
--                                  restrictive than the grammar and this was not
--                                  confirmed against a live Oracle. Left out rather
--                                  than shipped as an unverified claim.
-- modifier_clause (FINAL,       -- parse-level only; carries no transformation
--   INSTANTIABLE, PERSISTABLE)     consequence except OVERRIDING, which is rejected.
--
-- Before using this file to generate ALL_TYPE_METHODS rows for extraction tests,
-- create it in a scratch Oracle schema and confirm it compiles. It is currently
-- validated against the ANTLR grammar only.
--------------------------------------------------------------------------------
