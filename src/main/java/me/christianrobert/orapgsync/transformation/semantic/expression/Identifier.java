package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a simple identifier (column name, table name, alias, etc.).
 * In this minimal implementation, identifiers are passed through as-is.
 */
public class Identifier implements SemanticNode {

    private final String name;

    public Identifier(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifier name cannot be null or empty");
        }
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // In this minimal implementation, just pass through the identifier as-is
        // Future phases will add:
        // - Case normalization
        // - Quoting for reserved words
        // - Schema qualification
        return name;
    }

    @Override
    public String toString() {
        return "Identifier{name='" + name + "'}";
    }
}
