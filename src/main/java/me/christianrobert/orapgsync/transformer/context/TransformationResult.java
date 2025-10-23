package me.christianrobert.orapgsync.transformer.context;

/**
 * Result of a transformation operation.
 * Contains either the successfully transformed PostgreSQL SQL or an error message.
 * Optionally includes AST tree representation for debugging.
 */
public class TransformationResult {

    private final boolean success;
    private final String postgresSql;
    private final String errorMessage;
    private final String oracleSql;
    private final String astTree;  // Optional AST tree representation (null by default)

    private TransformationResult(boolean success, String postgresSql, String errorMessage, String oracleSql, String astTree) {
        this.success = success;
        this.postgresSql = postgresSql;
        this.errorMessage = errorMessage;
        this.oracleSql = oracleSql;
        this.astTree = astTree;
    }

    /**
     * Creates a successful transformation result.
     */
    public static TransformationResult success(String oracleSql, String postgresSql) {
        return new TransformationResult(true, postgresSql, null, oracleSql, null);
    }

    /**
     * Creates a successful transformation result with AST tree.
     */
    public static TransformationResult successWithAst(String oracleSql, String postgresSql, String astTree) {
        return new TransformationResult(true, postgresSql, null, oracleSql, astTree);
    }

    /**
     * Creates a failed transformation result.
     */
    public static TransformationResult failure(String oracleSql, String errorMessage) {
        return new TransformationResult(false, null, errorMessage, oracleSql, null);
    }

    /**
     * Creates a failed transformation result with AST tree.
     */
    public static TransformationResult failureWithAst(String oracleSql, String errorMessage, String astTree) {
        return new TransformationResult(false, null, errorMessage, oracleSql, astTree);
    }

    /**
     * Creates a failed transformation result from an exception.
     */
    public static TransformationResult failure(String oracleSql, TransformationException exception) {
        return new TransformationResult(false, null, exception.getDetailedMessage(), oracleSql, null);
    }

    /**
     * Creates a failed transformation result from an exception with AST tree.
     */
    public static TransformationResult failureWithAst(String oracleSql, TransformationException exception, String astTree) {
        return new TransformationResult(false, null, exception.getDetailedMessage(), oracleSql, astTree);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public String getPostgresSql() {
        return postgresSql;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOracleSql() {
        return oracleSql;
    }

    public String getAstTree() {
        return astTree;
    }

    public boolean hasAstTree() {
        return astTree != null;
    }

    @Override
    public String toString() {
        if (success) {
            return "TransformationResult{success=true, postgresSql='" + postgresSql + "'" +
                   (astTree != null ? ", hasAstTree=true" : "") + "}";
        } else {
            return "TransformationResult{success=false, error='" + errorMessage + "'" +
                   (astTree != null ? ", hasAstTree=true" : "") + "}";
        }
    }
}
