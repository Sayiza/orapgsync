package me.christianrobert.orapgsync.trigger.transformer;

import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;

/**
 * Generates PostgreSQL trigger function DDL.
 *
 * <p>PostgreSQL requires a two-part trigger definition:
 * <ol>
 *   <li><strong>Trigger Function</strong> - Contains the PL/pgSQL logic</li>
 *   <li><strong>Trigger Definition</strong> - Binds the function to table events</li>
 * </ol>
 *
 * <p>This class generates the trigger function (part 1).</p>
 *
 * <h3>PostgreSQL Trigger Function Template:</h3>
 * <pre>
 * CREATE OR REPLACE FUNCTION schema.trigger_name_func()
 * RETURNS TRIGGER AS $$
 * BEGIN
 *   -- Transformed trigger body (PL/pgSQL)
 *   RETURN NEW;
 * END;
 * $$ LANGUAGE plpgsql;
 * </pre>
 *
 * <h3>Naming Convention:</h3>
 * <p>Function name = trigger_name + "_func" suffix</p>
 * <p>Example: audit_trigger → audit_trigger_func</p>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * TriggerMetadata trigger = ...; // Oracle trigger metadata
 * String transformedBody = ...; // Transformed PL/pgSQL body with RETURN
 *
 * String functionDdl = TriggerFunctionGenerator.generateFunctionDdl(trigger, transformedBody);
 * // Execute functionDdl in PostgreSQL
 * </pre>
 */
public class TriggerFunctionGenerator {

    /**
     * Generates CREATE OR REPLACE FUNCTION DDL for a trigger function.
     *
     * <p>The generated function:
     * <ul>
     *   <li>Has no parameters (trigger context is implicit)</li>
     *   <li>Returns TRIGGER type</li>
     *   <li>Uses $$ dollar quoting for the function body</li>
     *   <li>Declares LANGUAGE plpgsql</li>
     * </ul>
     *
     * @param metadata Trigger metadata (schema, trigger name)
     * @param transformedBody Transformed PL/pgSQL body (must include RETURN statement)
     * @return CREATE FUNCTION DDL statement
     * @throws IllegalArgumentException if metadata or body is null/empty
     */
    public static String generateFunctionDdl(TriggerMetadata metadata, String transformedBody) {
        if (metadata == null) {
            throw new IllegalArgumentException("TriggerMetadata cannot be null");
        }

        if (transformedBody == null || transformedBody.trim().isEmpty()) {
            throw new IllegalArgumentException("Transformed body cannot be null or empty");
        }

        String schema = metadata.getSchema();
        String triggerName = metadata.getTriggerName();

        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("Schema cannot be null or empty");
        }

        if (triggerName == null || triggerName.isEmpty()) {
            throw new IllegalArgumentException("Trigger name cannot be null or empty");
        }

        // Generate function name: trigger_name + "_func"
        String functionName = triggerName + "_func";

        // Build the CREATE FUNCTION statement
        StringBuilder ddl = new StringBuilder();

        // Function signature
        ddl.append("CREATE OR REPLACE FUNCTION ")
           .append(schema)
           .append(".")
           .append(functionName)
           .append("()\n");

        // Return type
        ddl.append("RETURNS TRIGGER AS $$\n");

        // Function body (already transformed and includes RETURN statement)
        ddl.append(transformedBody);

        // Ensure body ends with newline before $$
        if (!transformedBody.endsWith("\n")) {
            ddl.append("\n");
        }

        // Close dollar quote and declare language
        ddl.append("$$ LANGUAGE plpgsql;\n");

        return ddl.toString();
    }

    /**
     * Generates just the function name (without schema qualification).
     *
     * <p>This is useful when you need to reference the function name
     * in other contexts (e.g., DROP FUNCTION, CREATE TRIGGER).</p>
     *
     * @param triggerName Original trigger name
     * @return Function name (trigger_name + "_func")
     */
    public static String getFunctionName(String triggerName) {
        if (triggerName == null || triggerName.isEmpty()) {
            throw new IllegalArgumentException("Trigger name cannot be null or empty");
        }
        return triggerName + "_func";
    }

    /**
     * Generates the qualified function name (schema.function_name).
     *
     * @param schema Schema name
     * @param triggerName Trigger name
     * @return Qualified function name (schema.trigger_name_func)
     */
    public static String getQualifiedFunctionName(String schema, String triggerName) {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("Schema cannot be null or empty");
        }
        return schema + "." + getFunctionName(triggerName);
    }
}
