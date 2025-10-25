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
 * - toggleTableSchemaGroup(): Toggle schema group visibility in table lists
 * - extractOracleRowCounts(): Extract row counts from Oracle tables
 * - pollRowCountJobStatus(): Poll row count job status
 * - getRowCountJobResults(): Retrieve row count job results
 * - displayRowCountResults(): Display row count results
 * - populateRowCountList(): Populate UI with row count data
 * - toggleRowCountList(): Toggle row count list visibility
 * - toggleRowCountSchemaGroup(): Toggle schema group visibility in row count lists
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
            button.innerHTML = '⚙';
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
            button.innerHTML = '⚙';
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
                button.innerHTML = '⚙';
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
            button.innerHTML = '⚙';
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
    const tableItemsElement = document.getElementById(`${database}-table-items`);

    if (!tableItemsElement) {
        console.warn('Table items element not found');
        return;
    }

    // Clear existing items
    tableItemsElement.innerHTML = '';

    if (summary.schemaTableCounts && Object.keys(summary.schemaTableCounts).length > 0) {
        Object.entries(summary.schemaTableCounts).forEach(([schemaName, tableCount]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${tableCount} tables)`;
            schemaHeader.onclick = () => toggleTableSchemaGroup(database, schemaName);

            const tableItems = document.createElement('div');
            tableItems.className = 'table-items-list';
            tableItems.id = `${database}-${schemaName}-tables`;

            // Add individual table entries for this schema
            if (summary.tables) {
                const schemaTables = Object.entries(summary.tables).filter(([key, table]) =>
                    table.schema === schemaName);

                schemaTables.forEach(([tableKey, table]) => {
                    const tableItem = document.createElement('div');
                    tableItem.className = 'table-item';
                    // Note: Constraints are extracted but NOT created yet (will be created after data transfer - Step C)
                    tableItem.innerHTML = `${table.name} (${table.columnCount} cols)`;
                    tableItems.appendChild(tableItem);
                });
            }

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(tableItems);
            tableItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noTablesItem = document.createElement('div');
        noTablesItem.className = 'table-item';
        noTablesItem.textContent = 'No tables found';
        noTablesItem.style.fontStyle = 'italic';
        noTablesItem.style.color = '#999';
        tableItemsElement.appendChild(noTablesItem);
    }
}

// Toggle table list visibility
function toggleTableList(database) {
    const tableItems = document.getElementById(`${database}-table-items`);
    const header = document.querySelector(`#${database}-table-list .table-list-header`);

    if (!tableItems || !header) {
        console.warn(`Table list elements not found for database: ${database}`);
        return;
    }

    if (tableItems.style.display === 'none') {
        tableItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        tableItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle table schema group visibility
function toggleTableSchemaGroup(database, schemaName) {
    const tableItems = document.getElementById(`${database}-${schemaName}-tables`);
    const header = event.target;

    if (!tableItems || !header) {
        console.warn(`Table schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (tableItems.style.display === 'none') {
        tableItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        tableItems.style.display = 'none';
        header.classList.add('collapsed');
    }
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
            button.innerHTML = '⚙';
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
                button.innerHTML = '⚙';
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
            button.innerHTML = '⚙';
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
    const rowCountItemsElement = document.getElementById(`${database}-rowcount-items`);

    if (!rowCountItemsElement) {
        console.warn('Row count items element not found');
        return;
    }

    // Clear existing items
    rowCountItemsElement.innerHTML = '';

    if (rowCounts && rowCounts.length > 0) {
        // Group row counts by schema
        const rowCountsBySchema = {};
        rowCounts.forEach(rowCount => {
            if (!rowCountsBySchema[rowCount.schema]) {
                rowCountsBySchema[rowCount.schema] = [];
            }
            rowCountsBySchema[rowCount.schema].push(rowCount);
        });

        Object.entries(rowCountsBySchema).forEach(([schemaName, schemaRowCounts]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            // Calculate total rows for this schema
            const schemaTotalRows = schemaRowCounts.reduce((sum, rc) => sum + (rc.rowCount >= 0 ? rc.rowCount : 0), 0);

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaRowCounts.length} tables, ${schemaTotalRows.toLocaleString()} rows)`;
            schemaHeader.onclick = () => toggleRowCountSchemaGroup(database, schemaName);

            const rowCountItems = document.createElement('div');
            rowCountItems.className = 'table-items-list';
            rowCountItems.id = `${database}-${schemaName}-rowcounts`;

            // Add individual table row counts for this schema
            schemaRowCounts.forEach(rowCount => {
                const rowCountItem = document.createElement('div');
                rowCountItem.className = 'table-item';

                if (rowCount.rowCount >= 0) {
                    rowCountItem.innerHTML = `${rowCount.tableName}: ${rowCount.rowCount.toLocaleString()} rows`;
                } else {
                    rowCountItem.innerHTML = `${rowCount.tableName}: <span style="color: #d73502;">Error counting rows</span>`;
                    rowCountItem.style.color = '#666';
                }

                rowCountItems.appendChild(rowCountItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(rowCountItems);
            rowCountItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noRowCountsItem = document.createElement('div');
        noRowCountsItem.className = 'table-item';
        noRowCountsItem.textContent = 'No row count data found';
        noRowCountsItem.style.fontStyle = 'italic';
        noRowCountsItem.style.color = '#999';
        rowCountItemsElement.appendChild(noRowCountsItem);
    }
}

// Toggle row count list visibility
function toggleRowCountList(database) {
    const rowCountItems = document.getElementById(`${database}-rowcount-items`);
    const header = document.querySelector(`#${database}-rowcount-list .table-list-header`);

    if (!rowCountItems || !header) {
        console.warn(`Row count list elements not found for database: ${database}`);
        return;
    }

    if (rowCountItems.style.display === 'none') {
        rowCountItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        rowCountItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle row count schema group visibility
function toggleRowCountSchemaGroup(database, schemaName) {
    const rowCountItems = document.getElementById(`${database}-${schemaName}-rowcounts`);
    const header = event.target;

    if (!rowCountItems || !header) {
        console.warn(`Row count schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (rowCountItems.style.display === 'none') {
        rowCountItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        rowCountItems.style.display = 'none';
        header.classList.add('collapsed');
    }
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
            button.innerHTML = '⚙';
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
    const resultsDiv = document.getElementById(`${database}-table-creation-results`);
    const detailsDiv = document.getElementById(`${database}-table-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Table creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-tables", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        if (summary.unmappedDefaultCount > 0) {
            html += `<span class="stat-item warnings">Unmapped Defaults: ${summary.unmappedDefaultCount}</span>`;
        }
        html += `</div>`;
        html += '</div>';

        // Show created tables
        if (summary.createdCount > 0) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdTables).forEach(table => {
                html += `<div class="table-item created">${table.tableName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped tables
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Tables (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedTables).forEach(table => {
                html += `<div class="table-item skipped">${table.tableName} (${table.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.tableName}</strong>: ${error.error}`;
                if (error.sql) {
                    html += `<div class="sql-statement"><pre>${error.sql}</pre></div>`;
                }
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show unmapped defaults (columns with complex Oracle default values that need manual review)
        if (summary.unmappedDefaultCount > 0) {
            html += '<div class="warning-tables-section">';
            html += '<p style="font-size:0.9rem; font-weight: bold;">Columns with Unmapped Default Values (Require Manual Review):</p>';
            html += '<div class="table-items">';
            html += '<p style="font-size: 0.7rem; font-style: italic; color: #666; margin: 5px 0;">The following columns have complex Oracle default values that could not be automatically transformed. Tables were created without these defaults. You can add them manually later.</p>';
            Object.values(summary.unmappedDefaults).forEach(warning => {
                html += `<div class="table-item warning">`;
                html += `<strong>${warning.tableName}.${warning.columnName}</strong>`;
                html += `<div style="margin-left: 15px; margin-top: 5px;">`;
                html += `<div><strong>Oracle Default:</strong> <code>${warning.oracleDefault}</code></div>`;
                html += `<div><strong>Note:</strong> ${warning.note}</div>`;
                html += `</div>`;
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleTableCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-table-creation-results`);
    const detailsDiv = document.getElementById(`${database}-table-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}
