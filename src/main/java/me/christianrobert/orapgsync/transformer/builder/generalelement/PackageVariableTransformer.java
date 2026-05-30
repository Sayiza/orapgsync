package me.christianrobert.orapgsync.transformer.builder.generalelement;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;

import java.util.List;

/**
 * Transforms Oracle package variable references to PostgreSQL getter calls.
 *
 * <p><strong>Three Oracle patterns for package variable references:</strong>
 * <ul>
 *   <li>Pattern 1 (unqualified, 1 part): {@code g_counter} inside package function
 *       → {@code schema.pkg__get_g_counter()}</li>
 *   <li>Pattern 2 (package-qualified, 2 parts): {@code pkg.g_counter}
 *       → {@code schema.pkg__get_g_counter()}</li>
 *   <li>Pattern 3 (schema-qualified, 3 parts): {@code hr.pkg.g_counter}
 *       → {@code schema.pkg__get_g_counter()}</li>
 * </ul>
 *
 * <p><strong>Detection criteria:</strong>
 * <ul>
 *   <li>Not in assignment target (LHS handling is separate)</li>
 *   <li>Last part has no function arguments (no parentheses)</li>
 *   <li>Identifier matches a registered package variable</li>
 * </ul>
 *
 * <p>This transformer must come BEFORE function call checks because package
 * variables can have the same syntax as package function calls without arguments.
 */
public class PackageVariableTransformer implements GeneralElementTransformer {

    @Override
    public GeneralElementResult tryTransform(
            List<PlSqlParser.General_element_partContext> parts,
            PostgresCodeBuilder builder) {

        // Skip if in assignment target (LHS handling is separate)
        if (builder.isInAssignmentTarget()) {
            return GeneralElementResult.notHandled();
        }

        // Check if last part has function arguments (not a variable reference)
        PlSqlParser.General_element_partContext lastPart = parts.get(parts.size() - 1);
        boolean hasArguments = lastPart.function_argument() != null && !lastPart.function_argument().isEmpty();
        if (hasArguments) {
            return GeneralElementResult.notHandled();
        }

        TransformationContext context = builder.getContext();
        if (context == null) {
            return GeneralElementResult.notHandled();
        }

        // Try Pattern 1: Unqualified variable in current package (1 part)
        if (parts.size() == 1) {
            String variableName = parts.get(0).id_expression().getText();
            String currentPackage = context.getCurrentPackageName();

            if (currentPackage != null && builder.isPackageVariable(currentPackage, variableName)) {
                String result = builder.transformToPackageVariableGetter(currentPackage, variableName);
                return GeneralElementResult.handled(result);
            }
        }

        // Try Pattern 2: Package-qualified variable (2 parts)
        if (parts.size() == 2) {
            String packageName = parts.get(0).id_expression().getText();
            String variableName = parts.get(1).id_expression().getText();

            if (builder.isPackageVariable(packageName, variableName)) {
                String result = builder.transformToPackageVariableGetter(packageName, variableName);
                return GeneralElementResult.handled(result);
            }
        }

        // Try Pattern 3: Schema-qualified variable (3 parts)
        if (parts.size() == 3) {
            String schemaName = parts.get(0).id_expression().getText();
            String packageName = parts.get(1).id_expression().getText();
            String variableName = parts.get(2).id_expression().getText();

            // Verify schema matches current schema (Oracle doesn't allow cross-schema package refs)
            String currentSchema = context.getCurrentSchema();
            if (schemaName.equalsIgnoreCase(currentSchema) &&
                    builder.isPackageVariable(packageName, variableName)) {
                // Transform to getter call (schema prefix not needed in getter name)
                String result = builder.transformToPackageVariableGetter(packageName, variableName);
                return GeneralElementResult.handled(result);
            }
        }

        return GeneralElementResult.notHandled();
    }
}
