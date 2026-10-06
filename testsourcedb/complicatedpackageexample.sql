CREATE OR REPLACE PACKAGE BODY Complicated_Pkg AS  -- Package level type definition (RECORD with nested TABLE)
  TYPE Employee_Record IS RECORD (
    emp_id     NUMBER,
    emp_name   VARCHAR2(100),
    salaries   DBMS_SQL.NUMBER_TABLE  -- Nested collection type
  );  -- Package level variable (initialized to a complex expression)

  v_global_counter NUMBER := 23 + 42;  -- Comment with false END: /* This is a block comment with END; inside it, and even a false BEGIN END; that shouldn't count */
  -- Line comment with false END -- END of line comment, but not real  PROCEDURE "Weird_Proc_Name_With_Quotes" (p_input IN OUT VARCHAR2) IS
  v_local_var DATE;
  -- Variable name that looks like keyword: v_end_date DATE;
  v_end_date DATE := SYSDATE;  -- False positive for "END"

FUNCTION Func_One_Return_Number (p_param IN NUMBER DEFAULT 0) RETURN NUMBER IS
    -- Local type def to complicate
    TYPE local_array IS VARRAY(5) OF VARCHAR2(10);
    v_array local_array := local_array('One', 'Two', /* END */ 'Three');
    v_result NUMBER;
BEGIN
    -- Nested with loop that has begin-end like structures
    --<<func_label>>
BEGIN
FOR i IN 1..3 LOOP
BEGIN
          v_result := p_param + i;
          -- String with nested keywords: q'[BEGIN dbms_output.put_line('END'); END;]'
EXECUTE IMMEDIATE q'[BEGIN NULL; / false END */ END;]';
END;
END LOOP;  -- Unbalanced looking but balanced: BEGIN NULL; END;
END func_label;

RETURN v_result * v_global_counter;  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN -1;  -- Edge: exception without begin-end
END Func_One_Return_Number;

FUNCTION "Func_Two_With_Edge_Cases" (p_str IN VARCHAR2) RETURN VARCHAR2 IS
    v_output VARCHAR2(4000);
BEGIN
    -- Super nested: 5 levels deep
BEGIN
BEGIN
BEGIN
BEGIN
BEGIN
              v_output := p_str || ' Nested';
              -- Comment: -- END;
END;
END;
END;
END;
END;-- Dynamic with false nest: /* EXECUTE IMMEDIATE 'DECLARE v_end NUMBER; BEGIN v_end := 1; END;';
RETURN v_output || ' /* END */';
END "Func_Two_With_Edge_Cases";-- Initialization section with short content

procedure aprocedureend( doit number default Func_One_Return_Number(2) )
is
 -- begin end;
begin
  return;
end; --

BEGIN
null;
end;
/