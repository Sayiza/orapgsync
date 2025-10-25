/**
 * Data Transfer Service
 *
 * This module handles data transfer operations from Oracle to PostgreSQL.
 * It provides functions for initiating data transfer jobs, polling their status,
 * and displaying transfer results with detailed statistics about transferred,
 * skipped, and errored tables.
 */

// ============================================================================
// Data Transfer Functions
// ============================================================================

async function transferData() {
    console.log('Starting data transfer job...');
    const button = document.querySelector('#postgres-data .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateComponentCount("postgres-data", "-");

    updateMessage('Starting data transfer from Oracle to PostgreSQL...');
    updateProgress(0, 'Starting data transfer');

    try {
        const response = await fetch('/api/transfer/postgres/execute', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('Data transfer job started:', result.jobId);
            updateMessage('Data transfer job started successfully');
            // Start polling for progress and AWAIT completion
            await pollDataTransferJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start data transfer job');
        }
    } catch (error) {
        console.error('Error starting data transfer job:', error);
        updateMessage('Failed to start data transfer: ' + error.message);
        updateProgress(0, 'Failed to start data transfer');
    } finally {
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Transfer Data';
        }
    }
}

async function pollDataTransferJobStatus(jobId) {
    console.log('Polling data transfer job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log('Data transfer job status:', status);

                if (status.progress) {
                    // Show the table count in the progress bar status
                    const progressText = status.progress.details
                        ? `${status.progress.currentTask} (${status.progress.details})`
                        : status.progress.currentTask;
                    updateProgress(status.progress.percentage, progressText);

                    // Show full message with details
                    const messageText = status.progress.details
                        ? `${status.progress.currentTask} - ${status.progress.details}`
                        : status.progress.currentTask;
                    updateMessage(messageText);
                }

                if (status.isComplete) {
                    console.log('Data transfer job completed');
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleDataTransferJobComplete(result);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector('#postgres-data .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Transfer Data';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling data transfer job status:', error);
                updateMessage('Error checking data transfer progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector('#postgres-data .action-btn');
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Transfer Data';
                }
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleDataTransferJobComplete(result) {
    console.log('Data transfer job results:', result);

    const transferredCount = result.transferredCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;
    const totalRows = result.totalRowsTransferred || 0;

    updateProgress(100, `Data transfer completed: ${transferredCount} tables, ${totalRows.toLocaleString()} rows transferred`);

    if (result.isSuccessful) {
        updateMessage(`Data transfer completed successfully: ${transferredCount} tables, ${totalRows.toLocaleString()} rows transferred`);
    } else {
        updateMessage(`Data transfer completed with errors: ${transferredCount} transferred, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update data transfer results section
    displayDataTransferResults(result);

    // Refresh PostgreSQL row counts to show newly transferred data, not needed
    //setTimeout(() => {
    //    extractPostgresRowCounts();
    //}, 1000);
}

function displayDataTransferResults(result) {
    const resultsDiv = document.getElementById('postgres-data-transfer-results');
    const detailsDiv = document.getElementById('postgres-data-transfer-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Data transfer results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-data", (summary.totalRowsTransferred || 0).toLocaleString());

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item transferred">Transferred: ${summary.transferredCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        if (summary.errorCount > 0) {
            html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        }
        html += `<span class="stat-item total-rows">Total Rows: ${(summary.totalRowsTransferred || 0).toLocaleString()}</span>`;
        html += '</div>';

        if (summary.executionTimestamp) {
            const date = new Date(summary.executionTimestamp);
            html += `<div class="execution-time">Executed: ${date.toLocaleString()}</div>`;
        }

        html += '</div>';

        // Show transferred tables
        if (summary.transferredTables && Object.keys(summary.transferredTables).length > 0) {
            html += '<div class="created-items-section">';
            html += '<h4>Transferred Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.transferredTables).forEach(table => {
                const rowInfo = table.rowsTransferred ? ` (${table.rowsTransferred.toLocaleString()} rows)` : '';
                html += `<div class="table-item created">${table.tableName}${rowInfo} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped tables
        if (summary.skippedTables && Object.keys(summary.skippedTables).length > 0) {
            html += '<div class="skipped-items-section">';
            html += '<h4>Skipped Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedTables).forEach(table => {
                html += `<div class="table-item skipped">${table.tableName} (already synced or empty)</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errors && Object.keys(summary.errors).length > 0) {
            html += '<div class="error-items-section">';
            html += '<h4>Transfer Errors:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error"><strong>${error.tableName}</strong>: ${error.error}</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    } else {
        html += '<div class="no-results">No detailed results available</div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleDataTransferResults() {
    const detailsDiv = document.getElementById('postgres-data-transfer-details');
    const header = document.querySelector('#postgres-data-transfer-results .table-creation-header .toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (header) header.textContent = '▼';
    } else {
        detailsDiv.style.display = 'none';
        if (header) header.textContent = '▶';
    }
}
