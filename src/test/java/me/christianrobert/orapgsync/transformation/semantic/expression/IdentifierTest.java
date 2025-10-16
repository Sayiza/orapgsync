package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Identifier semantic node.
 */
class IdentifierTest {

    private final TransformationContext context = new TransformationContext("test_schema", MetadataIndexBuilder.buildEmpty());

    @Test
    void simpleIdentifierPassesThrough() {
        Identifier identifier = new Identifier("empno");
        String result = identifier.toPostgres(context);
        assertEquals("empno", result);
    }

    @Test
    void uppercaseIdentifierPassesThrough() {
        Identifier identifier = new Identifier("EMPNO");
        String result = identifier.toPostgres(context);
        assertEquals("EMPNO", result);
    }

    @Test
    void mixedCaseIdentifierPassesThrough() {
        Identifier identifier = new Identifier("EmpNo");
        String result = identifier.toPostgres(context);
        assertEquals("EmpNo", result);
    }

    @Test
    void identifierWithUnderscorePassesThrough() {
        Identifier identifier = new Identifier("emp_no");
        String result = identifier.toPostgres(context);
        assertEquals("emp_no", result);
    }

    @Test
    void nullIdentifierThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new Identifier(null));
    }

    @Test
    void emptyIdentifierThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new Identifier(""));
    }

    @Test
    void whitespaceIdentifierThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new Identifier("   "));
    }

    @Test
    void identifierToStringIncludesName() {
        Identifier identifier = new Identifier("empno");
        String str = identifier.toString();
        assertTrue(str.contains("empno"));
    }
}
