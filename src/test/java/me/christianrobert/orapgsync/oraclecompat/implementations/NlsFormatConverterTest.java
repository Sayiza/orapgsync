package me.christianrobert.orapgsync.oraclecompat.implementations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NlsFormatConverter - Oracle NLS format to PostgreSQL TO_CHAR format conversion.
 */
class NlsFormatConverterTest {

    // ========== DATE FORMAT CONVERSION ==========

    @Test
    void convertDateFormat_standardOracleDefault() {
        // DD-MON-RR is the common Oracle default
        String result = NlsFormatConverter.convertDateFormat("DD-MON-RR");
        assertEquals("DD-Mon-YY", result);
    }

    @Test
    void convertDateFormat_isoFormat() {
        String result = NlsFormatConverter.convertDateFormat("YYYY-MM-DD");
        assertEquals("YYYY-MM-DD", result);
    }

    @Test
    void convertDateFormat_fullMonthName() {
        String result = NlsFormatConverter.convertDateFormat("DD-MONTH-YYYY");
        assertEquals("DD-Month-YYYY", result);
    }

    @Test
    void convertDateFormat_withDayName() {
        String result = NlsFormatConverter.convertDateFormat("DAY, DD-MON-RR");
        assertEquals("Day, DD-Mon-YY", result);
    }

    @Test
    void convertDateFormat_abbreviatedDay() {
        String result = NlsFormatConverter.convertDateFormat("DY DD-MON-RR");
        assertEquals("Dy DD-Mon-YY", result);
    }

    @Test
    void convertDateFormat_nullReturnsDefault() {
        String result = NlsFormatConverter.convertDateFormat(null);
        assertEquals("DD-Mon-YY", result);
    }

    @Test
    void convertDateFormat_emptyReturnsDefault() {
        String result = NlsFormatConverter.convertDateFormat("");
        assertEquals("DD-Mon-YY", result);
    }

    // ========== TIMESTAMP FORMAT CONVERSION ==========

    @Test
    void convertTimestampFormat_standardOracleDefault() {
        // DD-MON-RR HH.MI.SS AM is our simplified default
        String result = NlsFormatConverter.convertTimestampFormat("DD-MON-RR HH.MI.SS AM");
        assertEquals("DD-Mon-YY HH12.MI.SS AM", result);
    }

    @Test
    void convertTimestampFormat_withFractionalSeconds() {
        // SSXFF includes fractional seconds
        String result = NlsFormatConverter.convertTimestampFormat("DD-MON-RR HH.MI.SSXFF AM");
        assertEquals("DD-Mon-YY HH12.MI.SS.US AM", result);
    }

    @Test
    void convertTimestampFormat_24hourFormat() {
        String result = NlsFormatConverter.convertTimestampFormat("DD-MON-RR HH24:MI:SS");
        assertEquals("DD-Mon-YY HH24:MI:SS", result);
    }

    @Test
    void convertTimestampFormat_withFF3() {
        // FF3 = milliseconds (3 digits)
        String result = NlsFormatConverter.convertTimestampFormat("DD-MON-RR HH.MI.SS.FF3");
        assertEquals("DD-Mon-YY HH12.MI.SS.MS", result);
    }

    @Test
    void convertTimestampFormat_withFF6() {
        // FF6 = microseconds (6 digits)
        String result = NlsFormatConverter.convertTimestampFormat("DD-MON-RR HH.MI.SS.FF6");
        assertEquals("DD-Mon-YY HH12.MI.SS.US", result);
    }

    @Test
    void convertTimestampFormat_nullReturnsDefault() {
        String result = NlsFormatConverter.convertTimestampFormat(null);
        assertEquals("DD-Mon-YY HH12.MI.SS AM", result);
    }

    // ========== NEEDS UPPERCASE CHECK ==========

    @Test
    void needsUpperCase_withMonth() {
        assertTrue(NlsFormatConverter.needsUpperCase("DD-Mon-YY"));
    }

    @Test
    void needsUpperCase_withFullMonth() {
        assertTrue(NlsFormatConverter.needsUpperCase("DD-Month-YYYY"));
    }

    @Test
    void needsUpperCase_withDayName() {
        assertTrue(NlsFormatConverter.needsUpperCase("Day, DD-Mon-YY"));
    }

    @Test
    void needsUpperCase_withAbbreviatedDay() {
        assertTrue(NlsFormatConverter.needsUpperCase("Dy DD-Mon-YY"));
    }

    @Test
    void needsUpperCase_isoFormatNoNames() {
        assertFalse(NlsFormatConverter.needsUpperCase("YYYY-MM-DD"));
    }

    @Test
    void needsUpperCase_numericOnly() {
        assertFalse(NlsFormatConverter.needsUpperCase("YYYY-MM-DD HH24:MI:SS"));
    }
}
