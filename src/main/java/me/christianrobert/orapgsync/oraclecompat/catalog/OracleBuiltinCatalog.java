package me.christianrobert.orapgsync.oraclecompat.catalog;

import me.christianrobert.orapgsync.oraclecompat.implementations.DbmsLobImpl;
import me.christianrobert.orapgsync.oraclecompat.implementations.DbmsOutputImpl;
import me.christianrobert.orapgsync.oraclecompat.implementations.DbmsUtilityImpl;
import me.christianrobert.orapgsync.oraclecompat.implementations.UtlFileImpl;
import me.christianrobert.orapgsync.oraclecompat.model.OracleBuiltinFunction;
import me.christianrobert.orapgsync.oraclecompat.model.SupportLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central catalog of all Oracle built-in packages and their PostgreSQL equivalents.
 * <p>
 * Priority packages (Phase 1):
 * - DBMS_OUTPUT (debugging, logging)
 * - DBMS_UTILITY (error handling, formatting)
 * - UTL_FILE (file operations - limited)
 * - DBMS_LOB (LOB operations)
 * <p>
 * Future additions:
 * - DBMS_SQL (dynamic SQL - complex)
 * - UTL_HTTP (HTTP operations - security concerns)
 * - DBMS_RANDOM (random numbers)
 * - DBMS_SCHEDULER (job scheduling)
 */
public class OracleBuiltinCatalog {

    private final List<OracleBuiltinFunction> allFunctions;

    public OracleBuiltinCatalog() {
        allFunctions = new ArrayList<>();

        // Register all packages
        registerDbmsOutput();
        registerDbmsUtility();
        registerUtlFile();
        registerDbmsLob();
    }

    private void registerDbmsOutput() {
        // DBMS_OUTPUT.PUT_LINE - Full support via RAISE NOTICE
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_OUTPUT")
                .functionName("PUT_LINE")
                .signature("PUT_LINE(text)")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_output__put_line")
                .notes("Uses RAISE NOTICE to output to PostgreSQL log")
                .sqlDefinition(DbmsOutputImpl.getPutLine())
                .build());

        // DBMS_OUTPUT.PUT - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_OUTPUT")
                .functionName("PUT")
                .signature("PUT(text)")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_output__put")
                .notes("Uses session variable to buffer output")
                .sqlDefinition(DbmsOutputImpl.getPut())
                .build());

        // DBMS_OUTPUT.NEW_LINE - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_OUTPUT")
                .functionName("NEW_LINE")
                .signature("NEW_LINE()")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_output__new_line")
                .notes("Flushes buffered output with newline")
                .sqlDefinition(DbmsOutputImpl.getNewLine())
                .build());

        // DBMS_OUTPUT.ENABLE - Stub (always enabled in PostgreSQL)
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_OUTPUT")
                .functionName("ENABLE")
                .signature("ENABLE(buffer_size INTEGER DEFAULT NULL)")
                .supportLevel(SupportLevel.STUB)
                .postgresFunction("oracle_compat.dbms_output__enable")
                .notes("No-op stub - output always enabled in PostgreSQL")
                .sqlDefinition(DbmsOutputImpl.getEnable())
                .build());

        // DBMS_OUTPUT.DISABLE - Stub
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_OUTPUT")
                .functionName("DISABLE")
                .signature("DISABLE()")
                .supportLevel(SupportLevel.STUB)
                .postgresFunction("oracle_compat.dbms_output__disable")
                .notes("No-op stub - output cannot be disabled in PostgreSQL")
                .sqlDefinition(DbmsOutputImpl.getDisable())
                .build());
    }

    private void registerDbmsUtility() {
        // DBMS_UTILITY.FORMAT_ERROR_STACK - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_UTILITY")
                .functionName("FORMAT_ERROR_STACK")
                .signature("FORMAT_ERROR_STACK() RETURNS TEXT")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_utility__format_error_stack")
                .notes("Returns current exception message from GET STACKED DIAGNOSTICS")
                .sqlDefinition(DbmsUtilityImpl.getFormatErrorStack())
                .build());

        // DBMS_UTILITY.FORMAT_ERROR_BACKTRACE - Partial support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_UTILITY")
                .functionName("FORMAT_ERROR_BACKTRACE")
                .signature("FORMAT_ERROR_BACKTRACE() RETURNS TEXT")
                .supportLevel(SupportLevel.PARTIAL)
                .postgresFunction("oracle_compat.dbms_utility__format_error_backtrace")
                .notes("Returns exception context - less detailed than Oracle")
                .sqlDefinition(DbmsUtilityImpl.getFormatErrorBacktrace())
                .build());

        // DBMS_UTILITY.GET_TIME - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_UTILITY")
                .functionName("GET_TIME")
                .signature("GET_TIME() RETURNS INTEGER")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_utility__get_time")
                .notes("Returns milliseconds since epoch")
                .sqlDefinition(DbmsUtilityImpl.getGetTime())
                .build());
    }

    private void registerUtlFile() {
        // UTL_FILE operations - Partial support with limitations

        // UTL_FILE.FOPEN - Partial (limited to allowed directories)
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("UTL_FILE")
                .functionName("FOPEN")
                .signature("FOPEN(location TEXT, filename TEXT, open_mode TEXT, max_linesize INTEGER DEFAULT 1024) RETURNS INTEGER")
                .supportLevel(SupportLevel.PARTIAL)
                .postgresFunction("oracle_compat.utl_file__fopen")
                .notes("Limited to server-configured directories. Requires superuser or pg_read/write_server_files role.")
                .sqlDefinition(UtlFileImpl.getFopen())
                .build());

        // UTL_FILE.PUT_LINE - Partial
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("UTL_FILE")
                .functionName("PUT_LINE")
                .signature("PUT_LINE(file INTEGER, buffer TEXT)")
                .supportLevel(SupportLevel.PARTIAL)
                .postgresFunction("oracle_compat.utl_file__put_line")
                .notes("Writes to file via pg_write_file. Security restrictions apply.")
                .sqlDefinition(UtlFileImpl.getPutLine())
                .build());

        // UTL_FILE.FCLOSE - Partial
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("UTL_FILE")
                .functionName("FCLOSE")
                .signature("FCLOSE(file INTEGER)")
                .supportLevel(SupportLevel.PARTIAL)
                .postgresFunction("oracle_compat.utl_file__fclose")
                .notes("Closes file handle")
                .sqlDefinition(UtlFileImpl.getFclose())
                .build());
    }

    private void registerDbmsLob() {
        // DBMS_LOB.GETLENGTH - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_LOB")
                .functionName("GETLENGTH")
                .signature("GETLENGTH(lob_loc BYTEA) RETURNS INTEGER")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_lob__getlength")
                .notes("Returns length of BLOB/CLOB (bytea/text)")
                .sqlDefinition(DbmsLobImpl.getGetLength())
                .build());

        // DBMS_LOB.SUBSTR - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_LOB")
                .functionName("SUBSTR")
                .signature("SUBSTR(lob_loc TEXT, amount INTEGER DEFAULT 32767, offset INTEGER DEFAULT 1) RETURNS TEXT")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_lob__substr")
                .notes("Extracts substring from CLOB (text)")
                .sqlDefinition(DbmsLobImpl.getSubstr())
                .build());

        // DBMS_LOB.APPEND - Full support
        allFunctions.add(OracleBuiltinFunction.builder()
                .packageName("DBMS_LOB")
                .functionName("APPEND")
                .signature("APPEND(dest_lob INOUT TEXT, src_lob TEXT)")
                .supportLevel(SupportLevel.FULL)
                .postgresFunction("oracle_compat.dbms_lob__append")
                .notes("Appends text to existing LOB")
                .sqlDefinition(DbmsLobImpl.getAppend())
                .build());
    }

    // Query methods

    public List<OracleBuiltinFunction> getAllFunctions() {
        return new ArrayList<>(allFunctions);
    }

    public List<OracleBuiltinFunction> getFunctionsByPackage(String packageName) {
        return allFunctions.stream()
                .filter(f -> f.getPackageName().equalsIgnoreCase(packageName))
                .collect(Collectors.toList());
    }

    public List<OracleBuiltinFunction> getFunctionsBySupportLevel(SupportLevel level) {
        return allFunctions.stream()
                .filter(f -> f.getSupportLevel() == level)
                .collect(Collectors.toList());
    }

    public Map<SupportLevel, List<OracleBuiltinFunction>> groupBySupportLevel() {
        return allFunctions.stream()
                .collect(Collectors.groupingBy(OracleBuiltinFunction::getSupportLevel));
    }

    public int getTotalCount() {
        return allFunctions.size();
    }
}
