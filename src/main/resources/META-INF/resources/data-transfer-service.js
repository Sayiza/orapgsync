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
    const summary = result.summary;

    if (!summary) {
        setResultsPanel('postgres-data-transfer', {
            summaryHtml: '<div class="no-results">No detailed results available</div>'
        });
        return;
    }

    updateComponentCount("postgres-data", (summary.totalRowsTransferred || 0).toLocaleString());

    const stats = [
        { label: 'Transferred', value: summary.transferredCount, cssClass: 'transferred' },
        { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' }
    ];
    if (summary.errorCount > 0) {
        stats.push({ label: 'Errors', value: summary.errorCount, cssClass: 'errors' });
    }
    stats.push({ label: 'Total Rows', value: summary.totalRowsTransferred || 0, cssClass: 'total-rows' });

    let summaryHtml = renderSummaryStats(stats);
    if (summary.executionTimestamp) {
        const date = new Date(summary.executionTimestamp);
        summaryHtml += `<div class="execution-time">Executed: ${escapeHtml(date.toLocaleString())}</div>`;
    }

    setResultsPanel('postgres-data-transfer', {
        summaryHtml: summaryHtml,
        detailLabel: 'transferred tables',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Transferred Tables',
                items: toSortedArray(summary.transferredTables, 'tableName'),
                renderItem: table => {
                    const rowInfo = table.rowsTransferred ? ` (${formatCount(table.rowsTransferred)} rows)` : '';
                    return `<div class="table-item created">${escapeHtml(table.tableName)}${rowInfo} ✓</div>`;
                }
            },
            {
                title: 'Skipped Tables',
                items: toSortedArray(summary.skippedTables, 'tableName'),
                cssClass: 'skipped', nameKey: 'tableName', suffix: ' (already synced or empty)'
            },
            {
                title: 'Transfer Errors',
                items: toSortedArray(summary.errors, 'tableName'),
                cssClass: 'error', nameKey: 'tableName', showError: true
            }
        ], { jobId: result.jobId, label: 'tables' })
    });
}

function toggleDataTransferResults() {
    toggleResultsPanel('postgres-data-transfer');
}
