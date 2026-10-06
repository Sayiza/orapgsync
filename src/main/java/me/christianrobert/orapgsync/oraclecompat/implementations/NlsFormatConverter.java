package me.christianrobert.orapgsync.oraclecompat.implementations;

/**
 * Converts Oracle NLS format strings to PostgreSQL TO_CHAR format strings.
 * <p>
 * Oracle and PostgreSQL have similar but not identical format elements.
 * This class handles the conversion for date/timestamp formatting.
 * <p>
 * Common Oracle format elements and their PostgreSQL equivalents:
 * <ul>
 *   <li>DD - Day of month (01-31) - same in both</li>
 *   <li>MON - Abbreviated month (JAN) - PostgreSQL uses Mon (Jan), needs UPPER()</li>
 *   <li>MONTH - Full month name - PostgreSQL uses Month, needs UPPER()</li>
 *   <li>RR - 2-digit year (Y2K aware) - PostgreSQL uses YY (output is same)</li>
 *   <li>YY - 2-digit year - same in both</li>
 *   <li>YYYY - 4-digit year - same in both</li>
 *   <li>HH or HH12 - Hour 01-12 - PostgreSQL uses HH12</li>
 *   <li>HH24 - Hour 00-23 - same in both</li>
 *   <li>MI - Minute 00-59 - same in both</li>
 *   <li>SS - Second 00-59 - same in both</li>
 *   <li>SSXFF - Seconds with fractional - PostgreSQL uses SS.US or SS.MS</li>
 *   <li>FF, FF3, FF6 - Fractional seconds - PostgreSQL uses MS (3 digits) or US (6 digits)</li>
 *   <li>AM/PM - Meridian indicator - same in both</li>
 * </ul>
 */
public class NlsFormatConverter {

    /**
     * Converts an Oracle NLS_DATE_FORMAT string to PostgreSQL TO_CHAR format.
     *
     * @param oracleFormat Oracle format string (e.g., "DD-MON-RR")
     * @return PostgreSQL format string (e.g., "DD-Mon-YY")
     */
    public static String convertDateFormat(String oracleFormat) {
        if (oracleFormat == null || oracleFormat.isEmpty()) {
            return "DD-Mon-YY"; // Default
        }

        String pgFormat = oracleFormat;

        // RR -> YY (for output purposes, they produce the same result)
        pgFormat = pgFormat.replaceAll("(?i)RR", "YY");

        // MONTH -> Month (PostgreSQL uses mixed case, we'll UPPER() the result)
        pgFormat = pgFormat.replaceAll("(?i)MONTH", "Month");

        // MON -> Mon (PostgreSQL uses mixed case, we'll UPPER() the result)
        pgFormat = pgFormat.replaceAll("(?i)MON", "Mon");

        // DAY -> Day (full day name)
        pgFormat = pgFormat.replaceAll("(?i)DAY", "Day");

        // DY -> Dy (abbreviated day name)
        pgFormat = pgFormat.replaceAll("(?i)DY", "Dy");

        return pgFormat;
    }

    /**
     * Converts an Oracle NLS_TIMESTAMP_FORMAT string to PostgreSQL TO_CHAR format.
     *
     * @param oracleFormat Oracle format string (e.g., "DD-MON-RR HH.MI.SSXFF AM")
     * @return PostgreSQL format string
     */
    public static String convertTimestampFormat(String oracleFormat) {
        if (oracleFormat == null || oracleFormat.isEmpty()) {
            return "DD-Mon-YY HH12.MI.SS AM"; // Default
        }

        // Start with date format conversion
        String pgFormat = convertDateFormat(oracleFormat);

        // SSXFF -> SS.US (seconds with microseconds)
        pgFormat = pgFormat.replaceAll("(?i)SSXFF", "SS.US");

        // FF3 -> MS (milliseconds - 3 digits) - must come before generic FF
        pgFormat = pgFormat.replaceAll("(?i)FF3", "MS");

        // FF6, FF9 -> US (microseconds - 6 digits)
        pgFormat = pgFormat.replaceAll("(?i)FF[6-9]", "US");

        // FF -> US (default to microseconds) - generic FF without digit
        pgFormat = pgFormat.replaceAll("(?i)FF(?![0-9])", "US");

        // HH (without 12/24) -> HH12 in PostgreSQL for clarity
        // But only if not already HH12 or HH24
        pgFormat = pgFormat.replaceAll("(?i)(?<!\\d)HH(?!\\d)", "HH12");

        return pgFormat;
    }

    /**
     * Returns whether the format contains month or day names that need uppercasing.
     * PostgreSQL TO_CHAR produces mixed-case names (Mon, Jan) while Oracle produces
     * uppercase (MON, JAN).
     *
     * @param pgFormat PostgreSQL format string
     * @return true if the result needs to be wrapped in UPPER()
     */
    public static boolean needsUpperCase(String pgFormat) {
        // Check for month/day name format elements
        return pgFormat.contains("Mon") ||
               pgFormat.contains("Month") ||
               pgFormat.contains("Day") ||
               pgFormat.contains("Dy");
    }
}
