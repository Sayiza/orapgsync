package me.christianrobert.orapgsync.transformation.context;

/**
 * Exception thrown during SQL/PL-SQL transformation process.
 * Captures detailed context about transformation failures.
 */
public class TransformationException extends RuntimeException {

    private final String oracleSql;
    private final String context;

    public TransformationException(String message) {
        super(message);
        this.oracleSql = null;
        this.context = null;
    }

    public TransformationException(String message, Throwable cause) {
        super(message, cause);
        this.oracleSql = null;
        this.context = null;
    }

    public TransformationException(String message, String oracleSql, String context) {
        super(message);
        this.oracleSql = oracleSql;
        this.context = context;
    }

    public TransformationException(String message, String oracleSql, String context, Throwable cause) {
        super(message, cause);
        this.oracleSql = oracleSql;
        this.context = context;
    }

    public String getOracleSql() {
        return oracleSql;
    }

    public String getContext() {
        return context;
    }

    /**
     * Gets a detailed error message including Oracle SQL and context.
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder(getMessage());
        if (oracleSql != null) {
            sb.append("\nOracle SQL: ").append(oracleSql);
        }
        if (context != null) {
            sb.append("\nContext: ").append(context);
        }
        return sb.toString();
    }
}
