package me.christianrobert.orapgsync.integration;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct PostgreSQL test to understand REVERSE loop behavior.
 */
public class PostgresDirectReverseLoopTest extends PostgresSqlValidationTestBase {

    @Test
    void testReverseLoopWithIntegers() throws SQLException {
        // Test with literal integers
        String sql = """
            CREATE OR REPLACE FUNCTION test_reverse_int()
            RETURNS numeric
            LANGUAGE plpgsql
            AS $$
            DECLARE
              v_total numeric := 0;
            BEGIN
              FOR i IN REVERSE 1..5 LOOP
                v_total := v_total + i;
              END LOOP;
              RETURN v_total;
            END;
            $$;
            """;

        System.out.println("=== Testing REVERSE with literal integers ===");
        executeUpdate(sql);

        List<Map<String, Object>> rows = executeQuery("SELECT test_reverse_int() AS result");
        double result = ((Number) rows.get(0).get("result")).doubleValue();
        System.out.println("Result: " + result + " (expected 15)");
        assertEquals(15.0, result, 0.01);
    }

    @Test
    void testForwardLoopWithIntegers() throws SQLException {
        // Test without REVERSE
        String sql = """
            CREATE OR REPLACE FUNCTION test_forward_int()
            RETURNS numeric
            LANGUAGE plpgsql
            AS $$
            DECLARE
              v_total numeric := 0;
            BEGIN
              FOR i IN 1..5 LOOP
                v_total := v_total + i;
              END LOOP;
              RETURN v_total;
            END;
            $$;
            """;

        System.out.println("=== Testing forward loop with literal integers ===");
        executeUpdate(sql);

        List<Map<String, Object>> rows = executeQuery("SELECT test_forward_int() AS result");
        double result = ((Number) rows.get(0).get("result")).doubleValue();
        System.out.println("Result: " + result + " (expected 15)");
        assertEquals(15.0, result, 0.01);
    }
}
