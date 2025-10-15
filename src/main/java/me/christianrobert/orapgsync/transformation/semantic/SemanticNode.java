package me.christianrobert.orapgsync.transformation.semantic;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;

/**
 * Base interface for all semantic syntax tree nodes.
 * Each node knows how to transform itself to PostgreSQL.
 *
 * This is the core abstraction of the transformation module. Every SQL element
 * (statements, expressions, clauses, etc.) implements this interface and provides
 * its own transformation logic.
 */
public interface SemanticNode {

    /**
     * Transform this node to PostgreSQL equivalent.
     *
     * @param context Global context providing metadata, synonym resolution, type mapping, etc.
     * @return PostgreSQL SQL/PL/pgSQL string
     */
    String toPostgres(TransformationContext context);
}
