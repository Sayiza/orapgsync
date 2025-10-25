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
    const resultsDiv = document.getElementById(`${database}-fk-index-creation-results`);
    const detailsDiv = document.getElementById(`${database}-fk-index-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('FK index creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-fk-indexes", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created indexes - convert to Array
        if (summary.createdCount > 0 && summary.createdIndexes) {
            const createdIndexes = Array.isArray(summary.createdIndexes)
                ? summary.createdIndexes
                : Object.values(summary.createdIndexes);

            html += '<div class="created-tables-section">';
            html += '<h4>Created FK Indexes:</h4>';
            html += '<div class="table-items">';
            createdIndexes.forEach(index => {
                html += `<div class="table-item created">${index.indexName} on ${index.tableName} (${index.columns}) ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped indexes - convert to Array
        if (summary.skippedCount > 0 && summary.skippedIndexes) {
            const skippedIndexes = Array.isArray(summary.skippedIndexes)
                ? summary.skippedIndexes
                : Object.values(summary.skippedIndexes);

            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped FK Indexes (already exist):</h4>';
            html += '<div class="table-items">';
            skippedIndexes.forEach(index => {
                const reason = index.reason || 'already exists';
                html += `<div class="table-item skipped">${index.indexName} on ${index.tableName} (${index.columns}) (${reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert to Array
        if (summary.errorCount > 0 && summary.errors) {
            const errors = Array.isArray(summary.errors)
                ? summary.errors
                : Object.values(summary.errors);

            html += '<div class="error-tables-section">';
            html += '<h4>Failed FK Indexes:</h4>';
            html += '<div class="table-items">';
            errors.forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.indexName}</strong> on ${error.tableName} (${error.columns}): ${error.error}`;
                if (error.sql) {
                    html += `<div class="sql-statement"><pre>${error.sql}</pre></div>`;
                }
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleFKIndexCreationResults() {
    const resultsDiv = document.getElementById('postgres-fk-index-creation-results');
    const detailsDiv = document.getElementById('postgres-fk-index-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== END FK INDEX FUNCTIONS =====
