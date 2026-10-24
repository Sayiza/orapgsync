--------------------------------------------------------------------------------
-- Constructs that MUST be rejected, not transformed.
--
-- Companion to demo_types.sql. Every construct below corresponds to a row of the
-- rejection catalogue in documentation/completed/TYPE_METHOD_IMPLEMENTATION_PLAN.md section
-- 3.2, and each must produce a named TransformationException identifying the
-- construct -- never an approximation, and never a silent skip.
--
-- Two of these parse *clean*, so a test that only checks for parse failures will
-- pass while the transformer silently emits something wrong. Those are marked
-- PARSES CLEAN below and are the ones worth an explicit assertion.
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- 1. Java call_spec body.  PARSES CLEAN -- needs an explicit body-form check.
--    There is no PL/SQL here to translate at all.
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_external_t AS OBJECT (
    payload  varchar2(100),
    MEMBER FUNCTION render RETURN varchar2
);
/

CREATE OR REPLACE TYPE BODY demo_external_t IS
    MEMBER FUNCTION render RETURN varchar2
    IS LANGUAGE JAVA NAME 'com.example.Renderer.render() return java.lang.String';
END;
/

--------------------------------------------------------------------------------
-- 2. Type inheritance -- UNDER plus a NOT INSTANTIABLE supertype.
--    PostgreSQL composite types do not inherit; any emulation is a heuristic.
--
--    Note the extraction consequence, independent of transformation: the subtype
--    gets an ALL_TYPE_METHODS row for every *inherited* method, whose SELF
--    parameter is typed as the SUPERTYPE, and for which the subtype's body holds
--    no source. Extraction must filter INHERITED = 'NO'.
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_shape_t AS OBJECT (
    label  varchar2(30),
    NOT INSTANTIABLE MEMBER FUNCTION area RETURN number,
    MEMBER FUNCTION describe RETURN varchar2
) NOT INSTANTIABLE NOT FINAL;
/

CREATE OR REPLACE TYPE BODY demo_shape_t IS
    -- 'area' has no body here: it is abstract. That is a legitimate SKIP, the one
    -- place where "nothing to do" is the correct outcome rather than an error.
    MEMBER FUNCTION describe RETURN varchar2
    IS
    BEGIN
        RETURN self.label;
    END;
END;
/

CREATE OR REPLACE TYPE demo_circle_t UNDER demo_shape_t (
    radius  number,
    -- OVERRIDING reaches the parser on TWO different grammar alternatives
    -- depending on its return form and whether it has a body (plan section
    -- 3.1.1). The rejection must be attached to both, or this ordinary form
    -- slips through the plain member-function path and silently emits a
    -- function that ignores the inheritance it was meant to override.
    OVERRIDING MEMBER FUNCTION area RETURN number
);
/

CREATE OR REPLACE TYPE BODY demo_circle_t IS
    -- PARSES CLEAN, and routes to subprog_decl_in_type, NOT to
    -- overriding_subprogram_spec. Guarding only the latter misses this.
    OVERRIDING MEMBER FUNCTION area RETURN number
    IS
    BEGIN
        RETURN 3.14159 * self.radius * self.radius;
    END;
END;
/

--------------------------------------------------------------------------------
-- 3. REF -- an Oracle object reference has no PostgreSQL equivalent.
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_ref_holder_t AS OBJECT (
    note  varchar2(100),
    MEMBER FUNCTION target_label(p_ref REF demo_tag_t) RETURN varchar2
);
/

--------------------------------------------------------------------------------
-- 4. ODCI extensibility interface -- a user-defined aggregate.
--    The PostgreSQL target is CREATE AGGREGATE with sfunc/finalfunc, a different
--    kind of object entirely, so this is out of scope for type-method migration
--    rather than merely unimplemented.
--
--    Recognisable in the dictionary by a first parameter named SCTX rather than
--    SELF, which is what surfaced it during Phase 0.
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_concat_agg_t AS OBJECT (
    accumulated  varchar2(4000),

    STATIC FUNCTION ODCIAggregateInitialize(sctx IN OUT demo_concat_agg_t)
        RETURN number,
    MEMBER FUNCTION ODCIAggregateIterate(self IN OUT demo_concat_agg_t,
                                         value IN varchar2)
        RETURN number,
    MEMBER FUNCTION ODCIAggregateTerminate(self        IN  demo_concat_agg_t,
                                           returnValue OUT varchar2,
                                           flags       IN  number)
        RETURN number,
    MEMBER FUNCTION ODCIAggregateMerge(self IN OUT demo_concat_agg_t,
                                       ctx2 IN     demo_concat_agg_t)
        RETURN number
);
/

--------------------------------------------------------------------------------
-- 5. Mutating member FUNCTION -- SELF IN OUT *and* a return value.
--    PostgreSQL cannot express this: once a function has INOUT parameters the
--    return type is dictated by them, so there is no room for an independent
--    RETURNS. The only candidate workaround is returning ROW(self, result) and
--    destructuring at every call site -- a design decision to take only if real
--    code needs it. Until then, throw.
--------------------------------------------------------------------------------
CREATE OR REPLACE TYPE demo_counter_t AS OBJECT (
    hits  number,
    MEMBER FUNCTION next_value(SELF IN OUT demo_counter_t) RETURN number
);
/

CREATE OR REPLACE TYPE BODY demo_counter_t IS
    MEMBER FUNCTION next_value(SELF IN OUT demo_counter_t) RETURN number
    IS
    BEGIN
        self.hits := nvl(self.hits, 0) + 1;
        RETURN self.hits;
    END;
END;
/
