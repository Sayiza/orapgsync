package me.christianrobert.orapgsync.trigger.transformer;

import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;

/**
 * Generates PostgreSQL CREATE TRIGGER DDL statements.
 *
 * <p>This class generates the trigger definition (part 2 of the two-part
 * PostgreSQL trigger system) that binds a trigger function to table events.</p>
 *
 * <h3>PostgreSQL Trigger Definition Template:</h3>
 * <pre>
 * CREATE TRIGGER trigger_name
 *   {BEFORE|AFTER|INSTEAD OF} {INSERT|UPDATE|DELETE}
 *   ON schema.table_name
 *   [FOR EACH ROW]
 *   [WHEN (condition)]
 *   EXECUTE FUNCTION schema.trigger_name_func();
 * </pre>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * TriggerMetadata trigger = ...; // Oracle trigger metadata
 *
 * String triggerDdl = TriggerDefinitionGenerator.generateTriggerDdl(trigger);
 * // Execute triggerDdl in PostgreSQL AFTER creating the trigger function
 * </pre>
 */
public class TriggerDefinitionGenerator {

    /**
     * Generates CREATE TRIGGER DDL statement.
     *
     * <p>The generated trigger definition:
     * <ul>
     *   <li>Uses the trigger name from metadata (lowercased)</li>
     *   <li>Preserves timing (BEFORE/AFTER/INSTEAD OF)</li>
     *   <li>Preserves event (INSERT/UPDATE/DELETE or combinations)</li>
     *   <li>Includes FOR EACH ROW for row-level triggers</li>
     *   <li>Transforms WHEN clause (removes colons from :NEW/:OLD)</li>
     *   <li>References the trigger function (trigger_name_func)</li>
     * </ul>
     *
     * @param metadata Trigger metadata with all trigger characteristics
     * @return CREATE TRIGGER DDL statement
     * @throws IllegalArgumentException if metadata is null or incomplete
     */
    public static String generateTriggerDdl(TriggerMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("TriggerMetadata cannot be null");
        }

        validateMetadata(metadata);

        String schema = metadata.getSchema();
        String triggerName = metadata.getTriggerName();
        String tableName = metadata.getTableName();
        String triggerType = metadata.getTriggerType();
        String triggerEvent = metadata.getTriggerEvent();
        String triggerLevel = metadata.getTriggerLevel();
        String whenClause = metadata.getWhenClause();

        // Build the CREATE TRIGGER statement
        StringBuilder ddl = new StringBuilder();

        // CREATE TRIGGER trigger_name
        ddl.append("CREATE TRIGGER ")
           .append(triggerName)
           .append("\n");

        // BEFORE/AFTER/INSTEAD OF INSERT/UPDATE/DELETE
        ddl.append("  ")
           .append(triggerType)
           .append(" ")
           .append(triggerEvent)
           .append("\n");

        // ON schema.table_name
        ddl.append("  ON ")
           .append(schema)
           .append(".")
           .append(tableName)
           .append("\n");

        // FOR EACH ROW (if row-level trigger)
        if ("ROW".equalsIgnoreCase(triggerLevel)) {
            ddl.append("  FOR EACH ROW\n");
        }

        // WHEN (condition) - if present
        if (whenClause != null && !whenClause.trim().isEmpty()) {
            String transformedWhen = transformWhenClause(whenClause);
            ddl.append("  WHEN (")
               .append(transformedWhen)
               .append(")\n");
        }

        // EXECUTE FUNCTION schema.trigger_name_func()
        String functionName = TriggerFunctionGenerator.getFunctionName(triggerName);
        ddl.append("  EXECUTE FUNCTION ")
           .append(schema)
           .append(".")
           .append(functionName)
           .append("();\n");

        return ddl.toString();
    }

    /**
     * Validates that trigger metadata contains all required fields.
     *
     * @param metadata Trigger metadata to validate
     * @throws IllegalArgumentException if any required field is missing
     */
    private static void validateMetadata(TriggerMetadata metadata) {
        if (metadata.getSchema() == null || metadata.getSchema().isEmpty()) {
            throw new IllegalArgumentException("Schema cannot be null or empty");
        }

        if (metadata.getTriggerName() == null || metadata.getTriggerName().isEmpty()) {
            throw new IllegalArgumentException("Trigger name cannot be null or empty");
        }

        if (metadata.getTableName() == null || metadata.getTableName().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        if (metadata.getTriggerType() == null || metadata.getTriggerType().isEmpty()) {
            throw new IllegalArgumentException("Trigger type cannot be null or empty");
        }

        if (metadata.getTriggerEvent() == null || metadata.getTriggerEvent().isEmpty()) {
            throw new IllegalArgumentException("Trigger event cannot be null or empty");
        }

        if (metadata.getTriggerLevel() == null || metadata.getTriggerLevel().isEmpty()) {
            throw new IllegalArgumentException("Trigger level cannot be null or empty");
        }
    }

    /**
     * Transforms WHEN clause from Oracle to PostgreSQL syntax.
     *
     * <p>The main transformation is removing colons from :NEW and :OLD
     * correlation names:</p>
     * <ul>
     *   <li>:NEW.salary > 1000 → NEW.salary > 1000</li>
     *   <li>:OLD.status = 'ACTIVE' → OLD.status = 'ACTIVE'</li>
     * </ul>
     *
     * @param whenClause Oracle WHEN clause condition
     * @return PostgreSQL WHEN clause condition
     */
    private static String transformWhenClause(String whenClause) {
        if (whenClause == null || whenClause.trim().isEmpty()) {
            return whenClause;
        }

        // Remove colons from :NEW/:OLD references
        return ColonReferenceTransformer.removeColonReferences(whenClause);
    }
}
