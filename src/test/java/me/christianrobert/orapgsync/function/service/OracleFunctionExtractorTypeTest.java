package me.christianrobert.orapgsync.function.service;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for type extraction from Oracle function declarations in package bodies.
 */
class OracleFunctionExtractorTypeTest {

    private final AntlrParser parser = new AntlrParser();

    @Test
    void extractTypeFromFunctionBody_numberReturnType() {
        // Oracle package body with a function returning NUMBER
        String packageBody = """
            CREATE OR REPLACE PACKAGE BODY testpackagevar1 IS
              FUNCTION test1 RETURN NUMBER IS
              BEGIN
                RETURN 42;
              END;
            END testpackagevar1;
            """;

        ParseResult parseResult = parser.parsePackageBody(packageBody);
        assertFalse(parseResult.hasErrors(), "Package body should parse without errors");

        PlSqlParser.Create_package_bodyContext bodyCtx =
            (PlSqlParser.Create_package_bodyContext) parseResult.getTree();

        assertNotNull(bodyCtx.package_obj_body(), "Package body should have objects");
        assertTrue(bodyCtx.package_obj_body().size() > 0, "Package body should have at least one object");

        // Find the function_body
        PlSqlParser.Function_bodyContext funcCtx = null;
        for (PlSqlParser.Package_obj_bodyContext objCtx : bodyCtx.package_obj_body()) {
            if (objCtx.function_body() != null) {
                funcCtx = objCtx.function_body();
                break;
            }
        }

        assertNotNull(funcCtx, "Should find function_body in package");
        assertNotNull(funcCtx.type_spec(), "Function should have a return type specification");

        // Extract the type - this tests the logic that was added to the fix
        PlSqlParser.Type_specContext typeSpec = funcCtx.type_spec();
        assertNotNull(typeSpec.datatype(), "Return type should be a datatype");
        assertNotNull(typeSpec.datatype().native_datatype_element(), "Should be a native datatype");

        String returnType = typeSpec.datatype().native_datatype_element().getText().toUpperCase();
        assertEquals("NUMBER", returnType, "Return type should be NUMBER");
    }

    @Test
    void extractTypeFromFunctionBody_varchar2ReturnType() {
        // Oracle package body with a function returning VARCHAR2
        String packageBody = """
            CREATE OR REPLACE PACKAGE BODY test_pkg IS
              FUNCTION get_name RETURN VARCHAR2 IS
              BEGIN
                RETURN 'test';
              END;
            END test_pkg;
            """;

        ParseResult parseResult = parser.parsePackageBody(packageBody);
        assertFalse(parseResult.hasErrors(), "Package body should parse without errors");

        PlSqlParser.Create_package_bodyContext bodyCtx =
            (PlSqlParser.Create_package_bodyContext) parseResult.getTree();

        // Find the function_body
        PlSqlParser.Function_bodyContext funcCtx = null;
        for (PlSqlParser.Package_obj_bodyContext objCtx : bodyCtx.package_obj_body()) {
            if (objCtx.function_body() != null) {
                funcCtx = objCtx.function_body();
                break;
            }
        }

        assertNotNull(funcCtx, "Should find function_body in package");
        assertNotNull(funcCtx.type_spec(), "Function should have a return type specification");

        String returnType = funcCtx.type_spec().datatype().native_datatype_element().getText().toUpperCase();
        assertEquals("VARCHAR2", returnType, "Return type should be VARCHAR2");
    }

    @Test
    void extractTypeFromFunctionBody_dateReturnType() {
        // Oracle package body with a function returning DATE
        String packageBody = """
            CREATE OR REPLACE PACKAGE BODY test_pkg IS
              FUNCTION get_date RETURN DATE IS
              BEGIN
                RETURN SYSDATE;
              END;
            END test_pkg;
            """;

        ParseResult parseResult = parser.parsePackageBody(packageBody);
        assertFalse(parseResult.hasErrors(), "Package body should parse without errors");

        PlSqlParser.Create_package_bodyContext bodyCtx =
            (PlSqlParser.Create_package_bodyContext) parseResult.getTree();

        // Find the function_body
        PlSqlParser.Function_bodyContext funcCtx = null;
        for (PlSqlParser.Package_obj_bodyContext objCtx : bodyCtx.package_obj_body()) {
            if (objCtx.function_body() != null) {
                funcCtx = objCtx.function_body();
                break;
            }
        }

        assertNotNull(funcCtx, "Should find function_body in package");
        assertNotNull(funcCtx.type_spec(), "Function should have a return type specification");

        String returnType = funcCtx.type_spec().datatype().native_datatype_element().getText().toUpperCase();
        assertEquals("DATE", returnType, "Return type should be DATE");
    }
}
