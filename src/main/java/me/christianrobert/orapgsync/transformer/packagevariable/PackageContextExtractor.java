package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts package variable declarations and type definitions from Oracle package specifications.
 *
 * <p>This class parses package specs using ANTLR and extracts:
 * <ul>
 *   <li><strong>Variables:</strong> Variable names, data types, default values, CONSTANT keyword</li>
 *   <li><strong>Types:</strong> Package-level type definitions (RECORD, TABLE OF, VARRAY, INDEX BY)</li>
 * </ul>
 *
 * <p><strong>Supported Type Categories:</strong>
 * <ul>
 *   <li>RECORD - Composite structures with named fields</li>
 *   <li>TABLE OF - Dynamic arrays</li>
 *   <li>VARRAY - Fixed-size arrays</li>
 *   <li>INDEX BY - Associative arrays (key-value maps)</li>
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

        // Extract variable declarations and type definitions from spec
        PlSqlParser.Create_packageContext packageCtx = (PlSqlParser.Create_packageContext) parseResult.getTree();
        if (packageCtx != null && packageCtx.package_obj_spec() != null) {
            for (PlSqlParser.Package_obj_specContext specCtx : packageCtx.package_obj_spec()) {
                // Extract variables
                if (specCtx.variable_declaration() != null) {
                    extractVariableDeclaration(specCtx.variable_declaration(), context);
                }
                // Extract types
                if (specCtx.type_declaration() != null) {
                    extractTypeDeclaration(specCtx.type_declaration(), context);
                }
            }
        }

        log.debug("Extracted {} variables and {} types from package spec {}.{}",
                  context.getVariables().size(), context.getTypes().size(), schema, packageName);
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

    /**
     * Extracts a single type declaration and adds it to the context.
     *
     * <p>Supported type categories:
     * <ul>
     *   <li>RECORD - Composite structures</li>
     *   <li>TABLE OF - Dynamic arrays</li>
     *   <li>VARRAY - Fixed-size arrays</li>
     *   <li>INDEX BY - Associative arrays</li>
     * </ul>
     *
     * @param typeCtx Type declaration context from ANTLR
     * @param context PackageContext to add type to
     */
    private void extractTypeDeclaration(PlSqlParser.Type_declarationContext typeCtx, PackageContext context) {
        // Extract type name
        String typeName = typeCtx.identifier().getText();

        InlineTypeDefinition typeDefinition = null;

        // Determine type category and extract accordingly
        if (typeCtx.record_type_def() != null) {
            typeDefinition = extractRecordType(typeName, typeCtx.record_type_def());
        } else if (typeCtx.table_type_def() != null) {
            typeDefinition = extractTableType(typeName, typeCtx.table_type_def());
        } else if (typeCtx.varray_type_def() != null) {
            typeDefinition = extractVarrayType(typeName, typeCtx.varray_type_def());
        } else if (typeCtx.ref_cursor_type_def() != null) {
            // REF CURSOR types are not supported in Phase 1A
            log.debug("Skipping REF CURSOR type: {}", typeName);
            return;
        } else if (typeCtx.type_spec() != null) {
            // TYPE alias (e.g., TYPE t IS VARCHAR2(100)) - not supported in Phase 1A
            log.debug("Skipping TYPE alias: {}", typeName);
            return;
        }

        if (typeDefinition != null) {
            context.addType(typeDefinition);
            log.trace("Extracted type: {} ({})", typeName, typeDefinition.getCategory());
        }
    }

    /**
     * Extracts a RECORD type definition.
     *
     * <p>Oracle example: TYPE salary_range_t IS RECORD (min_sal NUMBER, max_sal NUMBER);
     *
     * @param typeName Type name
     * @param recordCtx RECORD type context
     * @return InlineTypeDefinition for RECORD
     */
    private InlineTypeDefinition extractRecordType(String typeName, PlSqlParser.Record_type_defContext recordCtx) {
        List<FieldDefinition> fields = new ArrayList<>();

        // Extract field specifications
        for (PlSqlParser.Field_specContext fieldSpec : recordCtx.field_spec()) {
            String fieldName = fieldSpec.column_name().getText();
            String oracleType = extractDataType(fieldSpec.type_spec());
            String postgresType = TypeConverter.toPostgre(oracleType);

            fields.add(new FieldDefinition(fieldName, oracleType, postgresType));
        }

        return new InlineTypeDefinition(
                typeName,
                TypeCategory.RECORD,
                null,  // No element type for RECORD
                fields,
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                null   // No size limit
        );
    }

    /**
     * Extracts a TABLE OF type definition (with or without INDEX BY).
     *
     * <p>Oracle examples:
     * <ul>
     *   <li>TYPE num_list_t IS TABLE OF NUMBER;</li>
     *   <li>TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);</li>
     * </ul>
     *
     * @param typeName Type name
     * @param tableCtx TABLE OF type context
     * @return InlineTypeDefinition for TABLE_OF or INDEX_BY
     */
    private InlineTypeDefinition extractTableType(String typeName, PlSqlParser.Table_type_defContext tableCtx) {
        // Extract element type
        String elementType = extractDataType(tableCtx.type_spec());

        // Check if INDEX BY (associative array)
        if (tableCtx.table_indexed_by_part() != null) {
            String indexKeyType = extractDataType(tableCtx.table_indexed_by_part().type_spec());

            return new InlineTypeDefinition(
                    typeName,
                    TypeCategory.INDEX_BY,
                    elementType,  // Value type
                    null,         // No fields for INDEX BY
                    ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                    null,         // No size limit
                    indexKeyType  // Index key type
            );
        }

        // Regular TABLE OF (nested table)
        return new InlineTypeDefinition(
                typeName,
                TypeCategory.TABLE_OF,
                elementType,
                null,  // No fields for TABLE OF
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                null   // No size limit
        );
    }

    /**
     * Extracts a VARRAY type definition.
     *
     * <p>Oracle example: TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
     *
     * @param typeName Type name
     * @param varrayCtx VARRAY type context
     * @return InlineTypeDefinition for VARRAY
     */
    private InlineTypeDefinition extractVarrayType(String typeName, PlSqlParser.Varray_type_defContext varrayCtx) {
        // Extract element type
        String elementType = extractDataType(varrayCtx.type_spec());

        // Extract size limit from expression (e.g., VARRAY(10))
        Integer sizeLimit = null;
        if (varrayCtx.expression() != null) {
            try {
                String sizeLimitText = varrayCtx.expression().getText();
                sizeLimit = Integer.parseInt(sizeLimitText);
            } catch (NumberFormatException e) {
                log.warn("Could not parse VARRAY size limit: {}", varrayCtx.expression().getText());
            }
        }

        return new InlineTypeDefinition(
                typeName,
                TypeCategory.VARRAY,
                elementType,
                null,  // No fields for VARRAY
                ConversionStrategy.JSONB,  // Phase 1: Always JSONB
                sizeLimit
        );
    }
}
