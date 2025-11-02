package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts package variable declarations from Oracle package specifications.
 *
 * <p>This class parses package specs using ANTLR and extracts:
 * <ul>
 *   <li>Variable names</li>
 *   <li>Data types</li>
 *   <li>Default values</li>
 *   <li>CONSTANT keyword</li>
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 * <pre>
 * PackageContextExtractor extractor = new PackageContextExtractor(antlrParser);
 * PackageContext context = extractor.extractContext("HR", "EMP_PKG", packageSpecSql);
 * </pre>
 */
public class PackageContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PackageContextExtractor.class);

    private final AntlrParser antlrParser;

    /**
     * Creates a new package context extractor.
     *
     * @param antlrParser ANTLR parser for parsing package specs
     */
    public PackageContextExtractor(AntlrParser antlrParser) {
        this.antlrParser = antlrParser;
    }

    /**
     * Extracts package context from a package specification.
     *
     * @param schema Schema name (e.g., "HR")
     * @param packageName Package name (e.g., "EMP_PKG")
     * @param packageSpecSql Package spec SQL (CREATE [OR REPLACE] PACKAGE ... END;)
     * @return PackageContext with extracted variables (from spec only - call extractBodyVariables for body)
     * @throws TransformationException if parsing fails
     */
    public PackageContext extractContext(String schema, String packageName, String packageSpecSql) {
        log.debug("Extracting package context: {}.{}", schema, packageName);

        // Parse package spec
        ParseResult parseResult = antlrParser.parsePackageSpec(packageSpecSql);
        if (parseResult.hasErrors()) {
            String errorMsg = "Failed to parse package spec: " + parseResult.getErrorMessage();
            log.error(errorMsg);
            throw new TransformationException(errorMsg, packageSpecSql, "Package spec parsing");
        }

        // Create context
        PackageContext context = new PackageContext(schema, packageName);

        // Extract variable declarations from spec
        PlSqlParser.Create_packageContext packageCtx = (PlSqlParser.Create_packageContext) parseResult.getTree();
        if (packageCtx != null && packageCtx.package_obj_spec() != null) {
            for (PlSqlParser.Package_obj_specContext specCtx : packageCtx.package_obj_spec()) {
                if (specCtx.variable_declaration() != null) {
                    extractVariableDeclaration(specCtx.variable_declaration(), context);
                }
            }
        }

        log.debug("Extracted {} variables from package spec {}.{}",
                  context.getVariables().size(), schema, packageName);
        return context;
    }

    /**
     * Extracts package variable declarations from a package body.
     * This should be called after extractContext() to get body variables in addition to spec variables.
     *
     * <p>In Oracle, packages can declare variables in both:
     * <ul>
     *   <li>Package specification - Public variables (extracted by extractContext())</li>
     *   <li>Package body - Private variables (extracted by this method)</li>
     * </ul>
     *
     * <p>Both types of variables are stored in the same PackageContext and treated identically
     * for transformation purposes (both get helper functions).
     *
     * @param bodyAst Parsed package body AST (from AntlrParser.parsePackageBody())
     * @param context PackageContext to add body variables to
     */
    public void extractBodyVariables(PlSqlParser.Create_package_bodyContext bodyAst, PackageContext context) {
        if (bodyAst == null || bodyAst.package_obj_body() == null) {
            log.debug("No package body or no body objects to extract variables from");
            return;
        }

        int bodyVariableCount = 0;

        // Iterate through package body objects
        for (PlSqlParser.Package_obj_bodyContext bodyObjCtx : bodyAst.package_obj_body()) {
            // Extract variable declarations (package-level body variables)
            if (bodyObjCtx.variable_declaration() != null) {
                extractVariableDeclaration(bodyObjCtx.variable_declaration(), context);
                bodyVariableCount++;
            }
        }

        log.debug("Extracted {} variables from package body {}.{}",
                  bodyVariableCount, context.getSchema(), context.getPackageName());
    }

    /**
     * Extracts a single variable declaration and adds it to the context.
     */
    private void extractVariableDeclaration(PlSqlParser.Variable_declarationContext varCtx, PackageContext context) {
        // Extract variable name
        String variableName = varCtx.identifier().getText();

        // Check if constant
        boolean isConstant = varCtx.CONSTANT() != null;

        // Extract data type
        String dataType = extractDataType(varCtx.type_spec());

        // Extract default value (if present)
        String defaultValue = extractDefaultValue(varCtx.default_value_part());

        // Create and add variable
        PackageContext.PackageVariable variable = new PackageContext.PackageVariable(
                variableName, dataType, defaultValue, isConstant
        );
        context.addVariable(variable);

        log.trace("Extracted variable: {} {} {} DEFAULT {}",
                  variableName, isConstant ? "CONSTANT" : "", dataType, defaultValue);
    }

    /**
     * Extracts Oracle data type from type_spec context.
     * Returns the raw Oracle type (will be transformed to PostgreSQL later).
     */
    private String extractDataType(PlSqlParser.Type_specContext typeSpecCtx) {
        if (typeSpecCtx == null) {
            return "VARCHAR2(4000)"; // Default fallback
        }

        // Get the full text of the type specification
        // This includes things like VARCHAR2(100), NUMBER(10,2), DATE, etc.
        return typeSpecCtx.getText();
    }

    /**
     * Extracts default value expression from default_value_part context.
     * Returns null if no default value is specified.
     */
    private String extractDefaultValue(PlSqlParser.Default_value_partContext defaultCtx) {
        if (defaultCtx == null || defaultCtx.expression() == null) {
            return null;
        }

        // Get the full text of the default value expression
        // This could be a literal (0, 'ACTIVE'), function call (SYSDATE), etc.
        return defaultCtx.expression().getText();
    }
}
