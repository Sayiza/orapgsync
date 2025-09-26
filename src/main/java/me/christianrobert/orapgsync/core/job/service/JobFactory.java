package me.christianrobert.orapgsync.core.job.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.table.job.TableMetadataExtractionJob;

@ApplicationScoped
public class JobFactory {

    @Inject
    StateService stateService;

    @Inject
    OracleConnectionService oracleConnectionService;

    public TableMetadataExtractionJob createTableMetadataExtractionJob() {
        TableMetadataExtractionJob job = new TableMetadataExtractionJob();

        // Manually inject dependencies since CDI doesn't work on manually created instances
        job.setStateService(stateService);
        job.setOracleConnectionService(oracleConnectionService);

        return job;
    }
}