/**
 * Foreign Key Index Service Module
 *
 * This module handles all FK index-related operations for the Oracle-to-PostgreSQL migration tool.
 * It provides functionality for:
 * - Creating foreign key indexes in PostgreSQL based on FK constraint definitions
 * - Polling job status for FK index creation operations
 * - Displaying FK index creation results in the UI
 * - Managing UI interactions for FK index-related components
 *
 * Functions included:
 * - createPostgresFKIndexes(): Initiates PostgreSQL FK index creation job
 * - pollFKIndexCreationJobStatus(): Monitors FK index creation job progress
 * - getFKIndexCreationResults(): Retrieves and displays creation results
 * - displayFKIndexCreationResults(): Displays detailed creation results
 * - toggleFKIndexCreationResults(): Toggles visibility of creation results panels
 */

// ===== FK INDEX FUNCTIONS =====

async function createPostgresFKIndexes() {
    console.log('Starting PostgreSQL FK index creation job...');

    updateComponentCount("postgres-fk-indexes", "-");

    const button = document.querySelector('#postgres-fk-indexes .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL FK index creation...');
    updateProgress(0, 'Starting PostgreSQL FK index creation');

    try {
        const response = await fetch('/api/constraints/postgres/fk-indexes/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL FK index creation job started:', result.jobId);
            updateMessage('PostgreSQL FK index creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollFKIndexCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL FK index creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL FK index creation job:', error);
        updateMessage('Failed to start PostgreSQL FK index creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL FK index creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create FK Indexes';
        }
    }
}

async function pollFKIndexCreationJobStatus(jobId, database) {
    console.log(`Polling FK index creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`FK index creation job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`FK index creation completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} FK index creation completed`);

                    // Get job results and display
                    await getFKIndexCreationResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-fk-indexes .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create FK Indexes';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} FK index creation failed`);
                    updateMessage(`${database.toUpperCase()} FK index creation failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-fk-indexes .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create FK Indexes';
                    }

                    reject(new Error(status.error || 'FK index creation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling FK index creation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getFKIndexCreationResults(jobId, database) {
    console.log('Getting FK index creation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('FK index creation job results:', result);

            // Display the creation results
            displayFKIndexCreationResults(result, database);

            // Update badge count
            const indexCount = result.createdCount || 0;
            updateComponentCount(`${database}-fk-indexes`, indexCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            updateMessage(`${databaseName}: Created ${result.createdCount} FK indexes, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get FK index creation results');
        }

    } catch (error) {
        console.error('Error getting FK index creation results:', error);
        updateMessage('Error getting FK index creation results: ' + error.message);
    }
}

function displayFKIndexCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-fk-indexes", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-fk-index-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'FK index outcomes',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created FK Indexes',
                items: toSortedArray(summary.createdIndexes, 'tableName', 'indexName'),
                renderItem: i => `<div class="table-item created">${describeFKIndex(i)} \u2713</div>`
            },
            {
                title: 'Skipped FK Indexes (already exist)',
                items: toSortedArray(summary.skippedIndexes, 'tableName', 'indexName'),
                renderItem: i => `<div class="table-item skipped">${describeFKIndex(i)} (${escapeHtml(i.reason || 'already exists')})</div>`
            },
            {
                title: 'Failed FK Indexes',
                items: toSortedArray(summary.errors, 'tableName', 'indexName'),
                renderItem: i => `<div class="table-item error">${describeFKIndex(i)}: ${escapeHtml(i.error || '')}`
                                 + renderDeferredCode(i.sql) + `</div>`
            }
        ], { jobId: result.jobId, label: 'indexes' })
    });
}

// "IDX_FK_EMP_DEPT on HR.EMP (DEPT_ID)"
function describeFKIndex(index) {
    return `<strong>${escapeHtml(index.indexName)}</strong> on ${escapeHtml(index.tableName)} (${escapeHtml(index.columns)})`;
}

function toggleFKIndexCreationResults() {
    toggleResultsPanel('postgres-fk-index-creation');
}

// ===== END FK INDEX FUNCTIONS =====
