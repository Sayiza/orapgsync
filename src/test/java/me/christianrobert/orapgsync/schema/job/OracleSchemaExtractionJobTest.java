package me.christianrobert.orapgsync.schema.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaExtractionJobTest {

    @Test
    void filtersOracleMaintainedUsersFrom12c() {
        assertTrue(OracleSchemaExtractionJob.buildUserQuery(12).contains("oracle_maintained = 'N'"));
        assertTrue(OracleSchemaExtractionJob.buildUserQuery(23).contains("oracle_maintained = 'N'"));
    }

    @Test
    void omitsOracleMaintainedFilterBefore12c() {
        // The column does not exist on 11g; referencing it would fail the whole extraction
        assertFalse(OracleSchemaExtractionJob.buildUserQuery(11).contains("oracle_maintained"));
    }
}
