package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * PostgreSQL implementations for DBMS_LOB package.
 * <p>
 * Provides LOB (Large Object) manipulation functions.
 */
public class DbmsLobImpl {

    public static String getGetLength() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_lob__getlength(lob_loc TEXT)
            RETURNS INTEGER
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RETURN LENGTH(lob_loc);
            END;
            $$;

            -- Overload for bytea (BLOB)
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_lob__getlength(lob_loc BYTEA)
            RETURNS INTEGER
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RETURN LENGTH(lob_loc);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_lob__getlength(TEXT) IS
            'Oracle DBMS_LOB.GETLENGTH equivalent - returns length of CLOB (text)';

            COMMENT ON FUNCTION oracle_compat.dbms_lob__getlength(BYTEA) IS
            'Oracle DBMS_LOB.GETLENGTH equivalent - returns length of BLOB (bytea)';
            """;
    }

    public static String getSubstr() {
        return """
            CREATE OR REPLACE FUNCTION oracle_compat.dbms_lob__substr(
                lob_loc TEXT,
                amount INTEGER DEFAULT 32767,
                start_pos INTEGER DEFAULT 1
            )
            RETURNS TEXT
            LANGUAGE plpgsql
            AS $$
            BEGIN
                -- Oracle LOB positions are 1-based
                RETURN SUBSTRING(lob_loc FROM start_pos FOR amount);
            END;
            $$;

            COMMENT ON FUNCTION oracle_compat.dbms_lob__substr(TEXT, INTEGER, INTEGER) IS
            'Oracle DBMS_LOB.SUBSTR equivalent - extracts substring from CLOB';
            """;
    }

    public static String getAppend() {
        return """
            CREATE OR REPLACE PROCEDURE oracle_compat.dbms_lob__append(
                INOUT dest_lob TEXT,
                IN src_lob TEXT
            )
            LANGUAGE plpgsql
            AS $$
            BEGIN
                dest_lob := dest_lob || src_lob;
            END;
            $$;

            -- Overload for bytea (BLOB)
            CREATE OR REPLACE PROCEDURE oracle_compat.dbms_lob__append(
                INOUT dest_lob BYTEA,
                IN src_lob BYTEA
            )
            LANGUAGE plpgsql
            AS $$
            BEGIN
                dest_lob := dest_lob || src_lob;
            END;
            $$;

            COMMENT ON PROCEDURE oracle_compat.dbms_lob__append(TEXT, TEXT) IS
            'Oracle DBMS_LOB.APPEND equivalent - appends to CLOB (text)';

            COMMENT ON PROCEDURE oracle_compat.dbms_lob__append(BYTEA, BYTEA) IS
            'Oracle DBMS_LOB.APPEND equivalent - appends to BLOB (bytea)';
            """;
    }
}
