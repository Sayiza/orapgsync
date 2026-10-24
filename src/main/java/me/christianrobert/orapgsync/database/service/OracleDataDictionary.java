package me.christianrobert.orapgsync.database.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses which family of Oracle data dictionary views the tool reads: {@code DBA_*} when the
 * account can read every one of them, {@code ALL_*} otherwise.
 *
 * <p>{@code ALL_*} views show only what the connected user has been granted. For PL/SQL that means
 * {@code EXECUTE}, so a read-only migration account ({@code SELECT ANY DICTIONARY},
 * {@code READ ANY TABLE}) sees a few percent of the source in {@code ALL_SOURCE},
 * {@code ALL_PROCEDURES} and {@code ALL_ARGUMENTS} — and every extraction silently reports a much
 * smaller database. The {@code DBA_*} views show everything, without granting anything that writes.
 *
 * <p>The decision is <b>all-or-nothing</b>. Mixing families — {@code DBA_PROCEDURES} with
 * {@code ALL_ARGUMENTS}, say — would extract routines without their parameters and build wrong
 * stub signatures with no error. So every view in {@link #VIEWS} is probed, and one unreadable
 * view (e.g. {@code DBA_ARGUMENTS}, which does not exist before 18c) keeps all of them on
 * {@code ALL_*}.
 *
 * <p>Queries keep naming {@code all_*} views; {@link #wrap} rewrites them on the way to the driver,
 * at the one point every Oracle connection is created ({@link OracleConnectionService#getConnection}),
 * so query sites added later are covered without anyone remembering. A query using a dictionary
 * view not listed in {@link #VIEWS} is left on {@code ALL_*}; {@code OracleDataDictionaryTest}
 * fails when main code does that.
 */
public final class OracleDataDictionary {

    public enum Family { DBA, ALL }

    /** The outcome of probing one account: the family to use and, for ALL, why. */
    public record Choice(Family family, String reason) { }

    /** Every dictionary view the tool queries, without its {@code ALL_}/{@code DBA_} prefix. */
    static final List<String> VIEWS = List.of(
            "arguments", "cons_columns", "constraints", "ind_columns", "ind_expressions", "indexes",
            "method_params", "method_results", "objects", "procedures", "sequences", "source",
            "synonyms", "tab_cols", "tab_columns", "tables", "triggers", "type_attrs",
            "type_methods", "users", "views");

    private static final Set<String> VIEW_SET = Set.copyOf(VIEWS);

    // Not part of a qualified name, a quoted identifier or a string literal on either side.
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![\\w$#.\"'])(all_)(" + String.join("|", VIEWS) + ")(?![\\w$#\"'])",
            Pattern.CASE_INSENSITIVE);

    private OracleDataDictionary() {
    }

    /** True when {@code view} (no prefix, any case) is one of {@link #VIEWS}. */
    static boolean isKnownView(String view) {
        return VIEW_SET.contains(view.toLowerCase());
    }

    /** Probes the {@code DBA_*} views on {@code connection}; never throws for a missing privilege. */
    public static Choice probe(Connection connection) {
        for (String view : VIEWS) {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeQuery("SELECT 1 FROM dba_" + view + " WHERE 1 = 0").close();
            } catch (SQLException e) {
                return new Choice(Family.ALL, "cannot read DBA_" + view.toUpperCase() + ": "
                        + firstLine(e.getMessage()));
            }
        }
        return new Choice(Family.DBA, null);
    }

    /** Rewrites every reference to a {@link #VIEWS} {@code all_*} view into its {@code dba_*} twin. */
    static String toDba(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = REFERENCE.matcher(sql);
        StringBuilder out = new StringBuilder(sql.length());
        while (m.find()) {
            String prefix = Character.isUpperCase(m.group(1).charAt(0)) ? "DBA_" : "dba_";
            m.appendReplacement(out, Matcher.quoteReplacement(prefix + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Returns {@code connection} unchanged for {@link Family#ALL}; for {@link Family#DBA}, a view of
     * it whose prepared, callable and plain statements have their SQL passed through {@link #toDba}.
     */
    public static Connection wrap(Connection connection, Family family) {
        if (family != Family.DBA) {
            return connection;
        }
        return proxy(Connection.class, connection, (target, method, args) -> {
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("nativeSQL"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                args[0] = toDba(sql);
            }
            Object result = invoke(method, target, args);
            if (name.equals("createStatement") && result instanceof Statement stmt) {
                return wrapStatement(stmt);
            }
            return result;
        });
    }

    private static Statement wrapStatement(Statement statement) {
        return proxy(Statement.class, statement, (target, method, args) -> {
            String name = method.getName();
            if ((name.startsWith("execute") || name.equals("addBatch"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                args[0] = toDba(sql);
            }
            return invoke(method, target, args);
        });
    }

    private interface Handler {
        Object handle(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, T target, Handler handler) {
        InvocationHandler ih = (p, method, args) -> handler.handle(target, method, args);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, ih));
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).trim();
    }
}
