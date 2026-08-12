/**
 * Table Service Module
 *
 * This module handles all table-related operations for the Oracle to PostgreSQL migration tool.
 *
 * Key Responsibilities:
 * - Table Metadata Extraction: Extract table metadata from Oracle and PostgreSQL databases
 * - Table Creation: Create PostgreSQL tables based on Oracle table metadata
 * - Row Count Operations: Extract and display row counts for both Oracle and PostgreSQL tables
 * - Display Operations: Render table lists, row counts, and creation results in the UI
 * - Job Management: Poll job status and handle completion for table-related jobs
 *
 * Functions included:
 * - extractTableMetadata(): Extract Oracle table metadata
 * - extractPostgresTableMetadata(): Extract PostgreSQL table metadata
 * - getJobResults(): Retrieve job results for table extraction
 * - displayTableResults(): Display table extraction results
 * - populateTableList(): Populate UI with extracted table metadata
 * - toggleTableList(): Toggle table list visibility
 * - extractOracleRowCounts(): Extract row counts from Oracle tables
 * - pollRowCountJobStatus(): Poll row count job status
 * - getRowCountJobResults(): Retrieve row count job results
 * - displayRowCountResults(): Display row count results
 * - populateRowCountList(): Populate UI with row count data
 * - toggleRowCountList(): Toggle row count list visibility
 * - extractPostgresRowCounts(): Extract row counts from PostgreSQL tables
 * - createPostgresTables(): Create tables in PostgreSQL
 * - pollTableCreationJobStatus(): Poll table creation job status
 * - handleTableCreationJobComplete(): Handle table creation completion
 * - displayTableCreationResults(): Display table creation results
 * - toggleTableCreationResults(): Toggle table creation results visibility
 */

// Table Metadata Extraction Job Management Functions

// Extract Oracle table metadata (starts the job)
async function extractTableMetadata() {
    console.log('Starting Oracle table metadata extraction job...');

    const button = document.querySelector('#oracle-tables .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle table metadata extraction...');
    updateProgress(0, 'Starting Oracle table metadata extraction');

    try {
        const response = await fetch('/api/tables/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle table extraction job started:', result.jobId);
            updateMessage('Oracle table extraction job started successfully');

            // Start polling for progress
            pollJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle table extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle table extraction job:', error);
        updateMessage('Failed to start Oracle table extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle table extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Extract PostgreSQL table metadata (starts the job)
async function extractPostgresTableMetadata() {
    console.log('Starting PostgreSQL table metadata extraction job...');

    const button = document.querySelector('#postgres-tables .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL table metadata extraction...');
    updateProgress(0, 'Starting PostgreSQL table metadata extraction');

    try {
        const response = await fetch('/api/tables/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL table extraction job started:', result.jobId);
            updateMessage('PostgreSQL table extraction job started successfully');

            // Start polling for progress
            pollJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL table extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL table extraction job:', error);
        updateMessage('Failed to start PostgreSQL table extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL table extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Poll job status until completion
async function pollJobStatus(jobId, database = 'oracle') {
    console.log('Polling job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Job status:', result);

        // Update progress if available
        if (result.progress) {
            const percentage = result.progress.percentage;
            const currentTask = result.progress.currentTask || 'Processing...';
            const details = result.progress.details || '';

            updateProgress(percentage, currentTask);
            if (details) {
                updateMessage(details);
            }
        }

        // Check if job is complete
        if (result.isComplete) {
            if (result.status === 'COMPLETED') {
                console.log('Job completed successfully');
                updateProgress(100, 'Job completed successfully');
                updateMessage('Table metadata extraction completed');

                // Get job results
                await getJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('Job failed:', result.error);
                updateProgress(0, 'Job failed');
                updateMessage('Table extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-tables .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling job status:', error);
        updateMessage('Error checking job status: ' + error.message);
        updateProgress(0, 'Error checking job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-tables .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Get job results and display them
async function getJobResults(jobId, database = 'oracle') {
    console.log('Getting job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Job results:', result);
            displayTableResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get job results');
        }

    } catch (error) {
        console.error('Error getting job results:', error);
        updateMessage('Error getting results: ' + error.message);
    }
}

// Display table extraction results
function displayTableResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Update table count badge
        updateComponentCount(`${database}-tables`, summary.totalTables);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted ${summary.totalTables} ${databaseName} tables with ${summary.totalColumns} columns from ${Object.keys(summary.schemaTableCounts).length} schemas`);

        // Populate table list
        populateTableList(summary, database);

        // Show table list
        if (summary.totalTables > 0) {
            document.getElementById(`${database}-table-list`).style.display = 'block';
        }
    }
}

// Populate table list with extracted table metadata
function populateTableList(summary, database = 'oracle') {
    // summary.tables is keyed by qualified name; the rows carry their own schema.
    const tables = Object.values(summary.tables || {});

    setDeferredList(`${database}-table-list`, `${database}-table-items`,
        () => renderSchemaGroups(tables,
            table => `<div class="table-item">${escapeHtml(table.name)} (${table.columnCount} cols)</div>`,
            { label: 'tables', groupIdPrefix: `${database}-table-group` }),
        `Table Names (${formatCount(tables.length)})`);
}

function toggleTableList(database) {
    toggleDeferredList(`${database}-table-list`);
}

// Row Count Extraction Functions

// Extract Oracle row counts (starts the job)
async function extractOracleRowCounts() {
    console.log('Starting Oracle row count extraction job...');

    updateComponentCount("oracle-data", "-");

    const button = document.querySelector('#oracle-data .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle row count extraction...');
    updateProgress(0, 'Starting Oracle row count extraction');

    try {
        const response = await fetch('/api/transfer/oracle/row-counts', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle row count extraction job started:', result.jobId);
            updateMessage('Oracle row count extraction job started successfully');

            // Start polling for progress
            pollRowCountJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle row count extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle row count extraction job:', error);
        updateMessage('Failed to start Oracle row count extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle row count extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Poll row count job status until completion
async function pollRowCountJobStatus(jobId, database = 'oracle') {
    console.log('Polling row count job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Row count job status:', result);

        // Update progress if available
        if (result.progress) {
            const percentage = result.progress.percentage;
            const currentTask = result.progress.currentTask || 'Processing...';
            const details = result.progress.details || '';

            updateProgress(percentage, currentTask);
            if (details) {
                updateMessage(details);
            }
        }

        // Check if job is complete
        if (result.isComplete) {
            if (result.status === 'COMPLETED') {
                console.log('Row count job completed successfully');
                updateProgress(100, 'Row count extraction completed successfully');
                updateMessage('Row count extraction completed');

                // Get job results
                await getRowCountJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('Row count job failed:', result.error);
                updateProgress(0, 'Row count extraction failed');
                updateMessage('Row count extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-data .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollRowCountJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling row count job status:', error);
        updateMessage('Error checking row count job status: ' + error.message);
        updateProgress(0, 'Error checking row count job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-data .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Get row count job results and display them
async function getRowCountJobResults(jobId, database) {
    console.log('Getting row count job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Row count job results:', result);
            displayRowCountResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get row count job results');
        }

    } catch (error) {
        console.error('Error getting row count job results:', error);
        updateMessage('Error getting row count results: ' + error.message);
    }
}

// Display row count extraction results
function displayRowCountResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Extract total row count from summary message
        const rowCounts = result.result || [];
        const totalRows = rowCounts.reduce((sum, rc) => sum + (rc.rowCount >= 0 ? rc.rowCount : 0), 0);

        // Format the total row count
        const formattedTotal = totalRows.toLocaleString();

        // Update row count badge with formatted number
        updateComponentCount(`${database}-data`, formattedTotal);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted row counts for ${rowCounts.length} ${databaseName} tables: ${formattedTotal} total rows`);

        // Populate row count list
        populateRowCountList(rowCounts, database);

        // Show row count list
        if (rowCounts.length > 0) {
            document.getElementById(`${database}-rowcount-list`).style.display = 'block';
        }
    }
}

// Populate row count list with extracted row count data
function populateRowCountList(rowCounts, database = 'oracle') {
    const counts = rowCounts || [];

    setDeferredList(`${database}-rowcount-list`, `${database}-rowcount-items`,
        () => renderSchemaGroups(counts, rowCount => {
            if (rowCount.rowCount >= 0) {
                return `<div class="table-item">${escapeHtml(rowCount.tableName)}: ${formatCount(rowCount.rowCount)} rows</div>`;
            }
            return `<div class="table-item" style="color: #666;">${escapeHtml(rowCount.tableName)}: `
                 + `<span style="color: #d73502;">Error counting rows</span></div>`;
        }, {
            label: 'tables',
            groupIdPrefix: `${database}-rowcount-group`,
            // Row totals per schema are the point of this list, so they belong in the header.
            groupSummary: group => {
                const total = group.reduce((sum, rc) => sum + (rc.rowCount >= 0 ? rc.rowCount : 0), 0);
                return `, ${formatCount(total)} rows`;
            }
        }),
        `Row Counts (${formatCount(counts.length)} tables)`);
}

function toggleRowCountList(database) {
    toggleDeferredList(`${database}-rowcount-list`);
}

// Extract PostgreSQL row counts (starts the job)
async function extractPostgresRowCounts() {
    console.log('Starting PostgreSQL row count extraction job...');

    const button = document.querySelector('#postgres-data .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL row count extraction...');
    updateProgress(0, 'Starting PostgreSQL row count extraction');

    try {
        const response = await fetch('/api/transfer/postgres/row-counts', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL row count extraction job started:', result.jobId);
            updateMessage('PostgreSQL row count extraction job started successfully');

            // Start polling for progress
            pollRowCountJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL row count extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL row count extraction job:', error);
        updateMessage('Failed to start PostgreSQL row count extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL row count extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Table Creation Functions
async function createPostgresTables() {
    console.log('Starting PostgreSQL table creation job...');

    updateComponentCount("postgres-tables", "-");

    const button = document.querySelector('#postgres-tables .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }
    updateMessage('Starting PostgreSQL table creation...');
    updateProgress(0, 'Starting PostgreSQL table creation');

    try {
        const response = await fetch('/api/tables/postgres/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL table creation job started:', result.jobId);
            updateMessage('PostgreSQL table creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollTableCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL table creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL table creation job:', error);
        updateMessage('Failed to start PostgreSQL table creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL table creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Tables';
        }
    }
}

async function pollTableCreationJobStatus(jobId, database) {
    console.log(`Polling table creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Table creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Table creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleTableCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-tables .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Tables';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling table creation job status:', error);
                updateMessage('Error checking table creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-tables .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Tables';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleTableCreationJobComplete(result, database) {
    console.log(`Table creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Table creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Table creation completed successfully: ${createdCount} tables created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Table creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update table creation results section
    displayTableCreationResults(result, database);

    // Refresh PostgreSQL tables to show newly created ones not need any more
    //setTimeout(() => {
    //    extractPostgresTableMetadata();
    //}, 1000);
}

function displayTableCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-tables", summary.createdCount + summary.skippedCount + summary.errorCount);

    const stats = [
        { label: 'Created', value: summary.createdCount, cssClass: 'created' },
        { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
        { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
    ];
    if (summary.unmappedDefaultCount > 0) {
        stats.push({ label: 'Unmapped Defaults', value: summary.unmappedDefaultCount, cssClass: 'warnings' });
    }

    setResultsPanel(`${database}-table-creation`, {
        summaryHtml: renderSummaryStats(stats),
        detailLabel: 'created tables',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Tables',
                items: toSortedArray(summary.createdTables, 'tableName'),
                cssClass: 'created', nameKey: 'tableName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Tables (already exist)',
                items: toSortedArray(summary.skippedTables, 'tableName'),
                cssClass: 'skipped', nameKey: 'tableName'
            },
            {
                title: 'Failed Tables',
                items: toSortedArray(summary.errors, 'tableName'),
                cssClass: 'error', nameKey: 'tableName', showError: true
            },
            {
                title: 'Columns with Unmapped Default Values (Require Manual Review)',
                items: toSortedArray(summary.unmappedDefaults, 'tableName', 'columnName'),
                renderItem: renderUnmappedDefault
            }
        ], { jobId: result.jobId, label: 'tables' })
    });
}

// A column whose Oracle default could not be transformed. The table was created without the
// default, so this is a to-do list rather than a failure.
function renderUnmappedDefault(warning) {
    let html = '<div class="table-item warning">';
    html += `<strong>${escapeHtml(warning.tableName)}.${escapeHtml(warning.columnName)}</strong>`;
    html += '<div style="margin-left: 15px; margin-top: 5px;">';
    html += `<div><strong>Oracle Default:</strong> <code>${escapeHtml(warning.oracleDefault)}</code></div>`;
    html += `<div><strong>Note:</strong> ${escapeHtml(warning.note)}</div>`;
    html += '</div></div>';
    return html;
}

function toggleTableCreationResults(database) {
    toggleResultsPanel(`${database}-table-creation`);
}
