package me.christianrobert.orapgsync.core.job.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.objectdatatype.job.OracleObjectDataTypeExtractionJob;
import me.christianrobert.orapgsync.objectdatatype.job.PostgresObjectDataTypeExtractionJob;
import me.christianrobert.orapgsync.table.job.OracleTableMetadataExtractionJob;
import me.christianrobert.orapgsync.table.job.PostgresTableMetadataExtractionJob;

@ApplicationScoped
public class JobFactory {

    @Inject
    StateService stateService;

    @Inject
    OracleConnectionService oracleConnectionService;

    @Inject
    PostgresConnectionService postgresConnectionService;

    @Inject
    ConfigService configService;

    public OracleTableMetadataExtractionJob createOracleTableMetadataExtractionJob() {
        OracleTableMetadataExtractionJob job = new OracleTableMetadataExtractionJob();

        // Manually inject dependencies since CDI doesn't work on manually created instances
        job.setStateService(stateService);
        job.setOracleConnectionService(oracleConnectionService);
        job.setConfigService(configService);

        return job;
    }

    public PostgresTableMetadataExtractionJob createPostgresTableMetadataExtractionJob() {
        PostgresTableMetadataExtractionJob job = new PostgresTableMetadataExtractionJob();

        // Manually inject dependencies since CDI doesn't work on manually created instances
        job.setStateService(stateService);
        job.setPostgresConnectionService(postgresConnectionService);
        job.setConfigService(configService);

        return job;
    }

    public OracleObjectDataTypeExtractionJob createOracleObjectDataTypeExtractionJob() {
        OracleObjectDataTypeExtractionJob job = new OracleObjectDataTypeExtractionJob();

        // Manually inject dependencies since CDI doesn't work on manually created instances
        job.setStateService(stateService);
        job.setOracleConnectionService(oracleConnectionService);
        job.setConfigService(configService);

        return job;
    }

    public PostgresObjectDataTypeExtractionJob createPostgresObjectDataTypeExtractionJob() {
        PostgresObjectDataTypeExtractionJob job = new PostgresObjectDataTypeExtractionJob();

        // Manually inject dependencies since CDI doesn't work on manually created instances
        job.setStateService(stateService);
        job.setPostgresConnectionService(postgresConnectionService);
        job.setConfigService(configService);

        return job;
    }
}