package me.christianrobert.orapgsync.integration;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to verify PostgreSQL FOR loop syntax.
 */
public class PostgresMinimalLoopTest extends PostgresSqlValidationTestBase {

    @Test
    void testMinimalForLoop() throws SQLException {
        // Create a simple table
        executeUpdate("CREATE TABLE test_table (id INT, val NUMERIC)");
        executeUpdate("INSERT INTO test_table VALUES (1, 100), (2, 200)");

        // Create function with FOR loop - manually written PostgreSQL
        // Try explicitly declaring the loop variable as RECORD
        String sql = """
            CREATE OR REPLACE FUNCTION test_sum()
            RETURNS numeric
            LANGUAGE plpgsql
            AS $$
            DECLARE
              v_total numeric := 0;
              my_record RECORD;
            BEGIN
              FOR my_record IN SELECT val FROM test_table LOOP
                v_total := v_total + my_record.val;
              END LOOP;
              RETURN v_total;
            END;
            $$;
            """;

        System.out.println("Testing PostgreSQL FOR loop syntax:");
        System.out.println(sql);

        // This should work if syntax is correct
        executeUpdate(sql);

        // Test execution
        List<Map<String, Object>> rows = executeQuery("SELECT test_sum() AS result");
        double result = ((Number) rows.get(0).get("result")).doubleValue();
        assertEquals(300.0, result, 0.01, "Should sum to 300");

        System.out.println("✅ PostgreSQL FOR loop syntax is valid!");
    }
}
