SELECT
  n.nspname AS schema_name,
  p.proname AS function_name
FROM pg_proc p
       JOIN pg_namespace n ON p.pronamespace = n.oid
WHERE n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast');

select user_vanessa.testpackagevar1__testnumber();
