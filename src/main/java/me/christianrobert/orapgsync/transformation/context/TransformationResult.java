package me.christianrobert.orapgsync.transformation.context;

/**
 * Result of a transformation operation.
 * Contains either the successfully transformed PostgreSQL SQL or an error message.
 */
public class TransformationResult {

    private final boolean success;
    private final String postgresSql;
    private final String errorMessage;
    private final String oracleSql;

    private TransformationResult(boolean success, String postgresSql, String errorMessage, String oracleSql) {
        this.success = success;
        this.postgresSql = postgresSql;
        this.errorMessage = errorMessage;
        this.oracleSql = oracleSql;
    }

    /**
     * Creates a successful transformation result.
     */
    public static TransformationResult success(String oracleSql, String postgresSql) {
        return new TransformationResult(true, postgresSql, null, oracleSql);
    }

    /**
     * Creates a failed transformation result.
     */
    public static TransformationResult failure(String oracleSql, String errorMessage) {
        return new TransformationResult(false, null, errorMessage, oracleSql);
    }

    /**
     * Creates a failed transformation result from an exception.
     */
    public static TransformationResult failure(String oracleSql, TransformationException exception) {
        return new TransformationResult(false, null, exception.getDetailedMessage(), oracleSql);
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

    @Override
    public String toString() {
        if (success) {
            return "TransformationResult{success=true, postgresSql='" + postgresSql + "'}";
        } else {
            return "TransformationResult{success=false, error='" + errorMessage + "'}";
        }
    }
}
