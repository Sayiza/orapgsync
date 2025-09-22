-- PostgreSQL implementation of Oracle HTP (Hypertext Procedures) package
-- This provides equivalent functionality to Oracle's HTP package for generating HTML output

CREATE SCHEMA IF NOT EXISTS SYS
;

-- Initialize HTP buffer - equivalent to Oracle's HTP.init
CREATE OR REPLACE PROCEDURE SYS.HTP_init()
AS $$
BEGIN
    DROP TABLE IF EXISTS temp_htp_buffer;
    CREATE TEMP TABLE temp_htp_buffer (
        line_no SERIAL,
        content TEXT
    );
END;
$$ LANGUAGE plpgsql
;

-- Print content to HTP buffer - equivalent to Oracle's HTP.p
CREATE OR REPLACE PROCEDURE SYS.HTP_p(content TEXT)
AS $$
BEGIN
    INSERT INTO temp_htp_buffer (content) VALUES (content);
END;
$$ LANGUAGE plpgsql
;

-- For NUMERIC (custom formatting, e.g., 2 decimal places)
CREATE OR REPLACE PROCEDURE SYS.HTP_p(content NUMERIC)
AS $$
BEGIN
INSERT INTO temp_htp_buffer (content) VALUES (TO_CHAR(content, 'FM999999999.99'));
END;
$$ LANGUAGE plpgsql;

-- For INTEGER
CREATE OR REPLACE PROCEDURE SYS.HTP_p(content INTEGER)
AS $$
BEGIN
INSERT INTO temp_htp_buffer (content) VALUES (content::TEXT);
END;
$$ LANGUAGE plpgsql;

-- For VARCHAR
CREATE OR REPLACE PROCEDURE SYS.HTP_p(content VARCHAR)
AS $$
BEGIN
INSERT INTO temp_htp_buffer (content) VALUES (content);
END;
$$ LANGUAGE plpgsql;

-- For DATE (custom formatting)
CREATE OR REPLACE PROCEDURE SYS.HTP_p(content DATE)
AS $$
BEGIN
INSERT INTO temp_htp_buffer (content) VALUES (TO_CHAR(content, 'YYYY-MM-DD'));
END;
$$ LANGUAGE plpgsql;

-- Get complete HTML page from buffer - equivalent to Oracle's HTP.get_page
CREATE OR REPLACE FUNCTION SYS.HTP_page()
RETURNS TEXT AS $$
DECLARE
    html_output TEXT := '';
BEGIN
    SELECT string_agg(content, chr(10) ORDER BY line_no)
    INTO html_output
    FROM temp_htp_buffer;
   
    RETURN COALESCE(html_output, '');
END;
$$ LANGUAGE plpgsql;

-- Additional HTP functions for better Oracle compatibility

-- Print line with newline - equivalent to Oracle's HTP.prn
CREATE OR REPLACE PROCEDURE SYS.HTP_prn(content TEXT)
AS $$
BEGIN
    INSERT INTO temp_htp_buffer (content) VALUES (content || chr(10));
END;
$$ LANGUAGE plpgsql
;

-- Print without newline (alias for HTP_p) - equivalent to Oracle's HTP.print
CREATE OR REPLACE PROCEDURE SYS.HTP_print(content TEXT)
AS $$
BEGIN
    CALL SYS.HTP_p(content);
END;
$$ LANGUAGE plpgsql
;

-- Clear the HTP buffer - equivalent to Oracle's HTP.flush
CREATE OR REPLACE PROCEDURE SYS.HTP_flush()
AS $$
BEGIN
    DELETE FROM temp_htp_buffer;
END;
$$ LANGUAGE plpgsql
;

-- Get buffer size
CREATE OR REPLACE FUNCTION SYS.HTP_buffer_size()
RETURNS INTEGER AS $$
BEGIN
    RETURN (SELECT COUNT(*) FROM temp_htp_buffer);
END;
$$ LANGUAGE plpgsql
;

-- HTML utility functions for common HTML generation

-- Generate HTML tag with content
CREATE OR REPLACE PROCEDURE SYS.HTP_tag(tag_name TEXT, content TEXT DEFAULT '', attributes TEXT DEFAULT '')
AS $$
BEGIN
    IF attributes IS NOT NULL AND attributes != '' THEN
        CALL SYS.HTP_p('<' || tag_name || ' ' || attributes || '>');
    ELSE
        CALL SYS.HTP_p('<' || tag_name || '>');
    END IF;
    
    IF content IS NOT NULL AND content != '' THEN
        CALL SYS.HTP_p(content);
    END IF;
    
    CALL SYS.HTP_p('</' || tag_name || '>');
END;
$$ LANGUAGE plpgsql
;

-- Generate HTML header
CREATE OR REPLACE PROCEDURE SYS.HTP_htmlOpen(title TEXT DEFAULT 'Generated Page')
AS $$
BEGIN
    CALL SYS.HTP_p('<!DOCTYPE html>');
    CALL SYS.HTP_p('<html>');
    CALL SYS.HTP_p('<head>');
    CALL SYS.HTP_p('<title>' || COALESCE(title, 'Generated Page') || '</title>');
    CALL SYS.HTP_p('</head>');
    CALL SYS.HTP_p('<body>');
END;
$$ LANGUAGE plpgsql
;

-- Close HTML document
CREATE OR REPLACE PROCEDURE SYS.HTP_htmlClose()
AS $$
BEGIN
    CALL SYS.HTP_p('</body>');
    CALL SYS.HTP_p('</html>');
END;
$$ LANGUAGE plpgsql
;

-- Package Variable Accessor Functions
-- These functions provide direct access to package variables stored in temporary tables
-- Following the same session-isolated pattern as HTP buffer functions

-- Read package variable (returns text, caller handles casting)
CREATE OR REPLACE FUNCTION SYS.get_package_var(
  target_schema text,
  package_name text, 
  var_name text
) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  value text;
BEGIN
  -- Build table name using target schema instead of current_schema()
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Read from session temp table
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO value;
  
  RETURN value;
EXCEPTION
  WHEN undefined_table THEN
    -- Table doesn't exist, return NULL (will be handled by caller)
    RETURN NULL;
  WHEN others THEN
    -- Log error and return NULL for graceful degradation
    RAISE WARNING 'Error reading package variable %.%: %', package_name, var_name, SQLERRM;
    RETURN NULL;
END;
$$;

-- Write package variable (accepts JSONB for unified storage)
CREATE OR REPLACE FUNCTION SYS.set_package_var(
  target_schema text,
  package_name text, 
  var_name text, 
  value jsonb
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
BEGIN
  -- Build table name using target schema instead of current_schema()
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Update session temp table with JSONB value
  EXECUTE format('UPDATE %I SET value = %L', table_name, value::text);
EXCEPTION
  WHEN undefined_table THEN
    -- Table doesn't exist, log warning for debugging
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    -- Log error for debugging
    RAISE WARNING 'Error writing package variable %.%: %', package_name, var_name, SQLERRM;
END;
$$;

-- JSON-Based Unified Package Variable System
-- All package variables are now stored as JSONB for consistency, extensibility, and future complex type support

-- Get collection element by index (1-based, Oracle-style, returns JSONB)
CREATE OR REPLACE FUNCTION SYS.get_package_var_element(
  target_schema text, 
  package_name text, 
  var_name text, 
  index_pos integer
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get the JSONB array from the package variable table
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    RETURN NULL;
  END IF;
  
  -- Parse as JSONB and extract array element (convert to 0-based indexing)
  json_array := json_value::jsonb;
  
  IF jsonb_typeof(json_array) = 'array' AND jsonb_array_length(json_array) >= index_pos THEN
    RETURN json_array -> (index_pos - 1);
  ELSE
    RETURN NULL;
  END IF;
EXCEPTION
  WHEN undefined_table THEN
    RETURN NULL;
  WHEN others THEN
    RAISE WARNING 'Error reading package variable element %.%[%]: %', package_name, var_name, index_pos, SQLERRM;
    RETURN NULL;
END;
$$;

-- Set collection element by index (1-based, Oracle-style, accepts JSONB)
CREATE OR REPLACE FUNCTION SYS.set_package_var_element(
  target_schema text, 
  package_name text, 
  var_name text, 
  index_pos integer, 
  value jsonb
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
  new_array jsonb;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get current JSONB array from the package variable table
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    -- Initialize as empty array if NULL
    json_array := '[]'::jsonb;
  ELSE
    json_array := json_value::jsonb;
  END IF;
  
  -- Ensure it's an array
  IF jsonb_typeof(json_array) != 'array' THEN
    json_array := '[]'::jsonb;
  END IF;
  
  -- Extend array if needed (fill with nulls up to index_pos)
  WHILE jsonb_array_length(json_array) < index_pos LOOP
    json_array := json_array || 'null'::jsonb;
  END LOOP;
  
  -- Update the element at index_pos (convert to 0-based indexing)
  new_array := jsonb_set(json_array, ARRAY[(index_pos - 1)::text], value);
  
  -- Update the package variable table with new array
  EXECUTE format('UPDATE %I SET value = %L', table_name, new_array::text);
EXCEPTION
  WHEN undefined_table THEN
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    RAISE WARNING 'Error writing package variable element %.%[%]: %', package_name, var_name, index_pos, SQLERRM;
END;
$$;

-- Collection COUNT method (Oracle arr.COUNT equivalent)
CREATE OR REPLACE FUNCTION SYS.get_package_var_count(target_schema text, package_name text, var_name text) 
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get the JSONB array from the package variable table
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    RETURN 0;
  END IF;
  
  json_array := json_value::jsonb;
  
  IF jsonb_typeof(json_array) = 'array' THEN
    RETURN jsonb_array_length(json_array);
  ELSE
    RETURN 0;
  END IF;
EXCEPTION
  WHEN undefined_table THEN
    RETURN 0;
  WHEN others THEN
    RAISE WARNING 'Error counting package variable %.%: %', package_name, var_name, SQLERRM;
    RETURN 0;
END;
$$;

-- Collection FIRST method (Oracle arr.FIRST equivalent) - returns 1 or NULL
CREATE OR REPLACE FUNCTION SYS.get_package_var_first(target_schema text, package_name text, var_name text) 
RETURNS integer LANGUAGE plpgsql AS $$
BEGIN
  IF SYS.get_package_var_count(target_schema, package_name, var_name) > 0 THEN
    RETURN 1;
  ELSE
    RETURN NULL;
  END IF;
END;
$$;

-- Collection LAST method (Oracle arr.LAST equivalent) - returns count or NULL
CREATE OR REPLACE FUNCTION SYS.get_package_var_last(target_schema text, package_name text, var_name text) 
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
  count_result integer;
BEGIN
  count_result := SYS.get_package_var_count(target_schema, package_name, var_name);
  IF count_result > 0 THEN
    RETURN count_result;
  ELSE
    RETURN NULL;
  END IF;
END;
$$;

-- Collection EXISTS method (Oracle arr.EXISTS(i) equivalent)
CREATE OR REPLACE FUNCTION SYS.get_package_var_exists(
  target_schema text, 
  package_name text, 
  var_name text, 
  index_pos integer
) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
  count_result integer;
BEGIN
  count_result := SYS.get_package_var_count(target_schema, package_name, var_name);
  RETURN index_pos > 0 AND index_pos <= count_result;
END;
$$;

-- Collection EXTEND method (Oracle arr.EXTEND equivalent)
CREATE OR REPLACE FUNCTION SYS.extend_package_var(
  target_schema text, 
  package_name text, 
  var_name text, 
  value jsonb DEFAULT NULL
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
  new_array jsonb;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get current JSONB array
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    json_array := '[]'::jsonb;
  ELSE
    json_array := json_value::jsonb;
  END IF;
  
  -- Ensure it's an array
  IF jsonb_typeof(json_array) != 'array' THEN
    json_array := '[]'::jsonb;
  END IF;
  
  -- Append new element
  IF value IS NULL THEN
    new_array := json_array || 'null'::jsonb;
  ELSE
    new_array := json_array || jsonb_build_array(value);
  END IF;
  
  -- Update package variable
  EXECUTE format('UPDATE %I SET value = %L', table_name, new_array::text);
EXCEPTION
  WHEN undefined_table THEN
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    RAISE WARNING 'Error extending package variable %.%: %', package_name, var_name, SQLERRM;
END;
$$;

-- Collection DELETE operations
CREATE OR REPLACE FUNCTION SYS.delete_package_var_element(
  target_schema text, 
  package_name text, 
  var_name text, 
  index_pos integer
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
  new_array jsonb := '[]'::jsonb;
  i integer;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get current array
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    RETURN; -- Nothing to delete
  END IF;
  
  json_array := json_value::jsonb;
  
  IF jsonb_typeof(json_array) != 'array' THEN
    RETURN; -- Not an array
  END IF;
  
  -- Rebuild array without the element at index_pos
  FOR i IN 1..jsonb_array_length(json_array) LOOP
    IF i != index_pos THEN
      new_array := new_array || jsonb_build_array(json_array -> (i - 1));
    END IF;
  END LOOP;
  
  -- Update package variable
  EXECUTE format('UPDATE %I SET value = %L', table_name, new_array::text);
EXCEPTION
  WHEN undefined_table THEN
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    RAISE WARNING 'Error deleting package variable element %.%[%]: %', package_name, var_name, index_pos, SQLERRM;
END;
$$;

-- Delete all elements (clear array)
CREATE OR REPLACE FUNCTION SYS.delete_package_var_all(
  target_schema text, 
  package_name text, 
  var_name text
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Set to empty array
  EXECUTE format('UPDATE %I SET value = %L', table_name, '[]'::jsonb::text);
EXCEPTION
  WHEN undefined_table THEN
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    RAISE WARNING 'Error clearing package variable %.%: %', package_name, var_name, SQLERRM;
END;
$$;

-- Collection TRIM method
CREATE OR REPLACE FUNCTION SYS.trim_package_var(
  target_schema text, 
  package_name text, 
  var_name text, 
  trim_count integer DEFAULT 1
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  table_name text;
  json_value text;
  json_array jsonb;
  current_length integer;
  new_length integer;
  new_array jsonb := '[]'::jsonb;
  i integer;
BEGIN
  table_name := lower(target_schema) || '_' || lower(package_name) || '_' || lower(var_name);
  
  -- Get current array
  EXECUTE format('SELECT value FROM %I LIMIT 1', table_name) INTO json_value;
  
  IF json_value IS NULL THEN
    RETURN; -- Nothing to trim
  END IF;
  
  json_array := json_value::jsonb;
  
  IF jsonb_typeof(json_array) != 'array' THEN
    RETURN; -- Not an array
  END IF;
  
  current_length := jsonb_array_length(json_array);
  new_length := current_length - trim_count;
  
  IF new_length <= 0 THEN
    new_array := '[]'::jsonb;
  ELSE
    -- Keep only the first new_length elements
    FOR i IN 1..new_length LOOP
      new_array := new_array || jsonb_build_array(json_array -> (i - 1));
    END LOOP;
  END IF;
  
  -- Update package variable
  EXECUTE format('UPDATE %I SET value = %L', table_name, new_array::text);
EXCEPTION
  WHEN undefined_table THEN
    RAISE WARNING 'Package variable table does not exist: %', table_name;
  WHEN others THEN
    RAISE WARNING 'Error trimming package variable %.%: %', package_name, var_name, SQLERRM;
END;
$$;

-- Example usage (commented out):
/*
-- Example of using HTP functions
DO $$
BEGIN
    -- Initialize the buffer
    CALL SYS.HTP_init();
    
    -- Generate HTML content
    CALL SYS.HTP_htmlOpen('My Page');
    CALL SYS.HTP_tag('h1', 'Welcome to My Page');
    CALL SYS.HTP_tag('p', 'This is generated content.');
    CALL SYS.HTP_htmlClose();
    
    -- Get the complete page
    RAISE NOTICE 'Generated HTML: %', SYS.HTP_page();
END;
$$;

-- Example of using package variable functions
DO $$
BEGIN
    -- Example assumes package variable table exists
    -- CREATE TEMP TABLE test_schema_minitest_gx (value text DEFAULT '1');
    
    -- Read package variable
    RAISE NOTICE 'Package variable gX: %', SYS.get_package_var_numeric('user_robert', 'minitest', 'gX');
    
    -- Write package variable
    PERFORM SYS.set_package_var_numeric('user_robert', 'minitest', 'gX', 42);
    
    -- Read updated value
    RAISE NOTICE 'Updated package variable gX: %', SYS.get_package_var_numeric('user_robert', 'minitest', 'gX');
END;
$$;

-- Example of using package collection functions
DO $$
BEGIN
    -- Example assumes package collection table exists
    -- CREATE TEMP TABLE test_schema_minitest_arr (value text);
    -- INSERT INTO test_schema_minitest_arr (value) VALUES ('1'), ('2'), ('3');
    
    -- Read collection element
    RAISE NOTICE 'Collection element arr[1]: %', SYS.get_package_collection_element_numeric('user_robert', 'minitest', 'arr', 1);
    
    -- Write collection element
    PERFORM SYS.set_package_collection_element_numeric('user_robert', 'minitest', 'arr', 1, 42);
    
    -- Read updated element
    RAISE NOTICE 'Updated collection element arr[1]: %', SYS.get_package_collection_element_numeric('user_robert', 'minitest', 'arr', 1);
    
    -- Collection operations
    RAISE NOTICE 'Collection count: %', SYS.get_package_collection_count('user_robert', 'minitest', 'arr');
    RAISE NOTICE 'Collection first: %', SYS.get_package_collection_first('user_robert', 'minitest', 'arr');
    RAISE NOTICE 'Collection last: %', SYS.get_package_collection_last('user_robert', 'minitest', 'arr');
    
    -- Extend collection
    PERFORM SYS.extend_package_collection('user_robert', 'minitest', 'arr', '99');
    RAISE NOTICE 'Collection after extend: %', SYS.get_package_collection_count('user_robert', 'minitest', 'arr');
END;
$$;
*/