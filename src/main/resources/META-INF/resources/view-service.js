/**
 * View Service Module
 *
 * This module handles all view-related operations for the Oracle to PostgreSQL migration tool.
 *
 * Key Responsibilities:
 * - View Definition Extraction: Extract view metadata from Oracle and PostgreSQL databases
 * - View Stub Creation: Create PostgreSQL view stubs (views with correct structure but empty result sets)
 * - Display Operations: Render view lists and creation results in the UI
 * - Job Management: Poll job status and handle completion for view-related jobs
 *
 * Functions included:
 * - extractOracleViews(): Extract Oracle view definitions
 * - extractPostgresViews(): Extract PostgreSQL view definitions
 * - createPostgresViewStubs(): Create PostgreSQL view stubs
 * - pollViewJobStatus(): Poll view extraction job status
 * - getViewJobResults(): Retrieve view job results
 * - displayViewResults(): Display view extraction results
 * - populateViewList(): Populate UI with extracted view metadata
 * - toggleViewList(): Toggle view list visibility
 * - toggleViewSchemaGroup(): Toggle schema group visibility in view lists
 * - displayViewStubCreationResults(): Display view stub creation results
 * - toggleViewStubCreationResults(): Toggle view stub creation results visibility
 */

// View Definition Extraction Job Management Functions

// Extract Oracle view definitions (starts the job)
async function extractOracleViews() {
    console.log('Starting Oracle view definition extraction job...');

    const button = document.querySelector('#oracle-views .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle view definition extraction...');
    updateProgress(0, 'Starting Oracle view definition extraction');

    try {
        const response = await fetch('/api/views/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle view extraction job started:', result.jobId);
            updateMessage('Oracle view extraction job started successfully');

            // Start polling for progress
            pollViewJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle view extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle view extraction job:', error);
        updateMessage('Failed to start Oracle view extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle view extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Extract PostgreSQL view definitions (starts the job)
async function extractPostgresViews() {
    console.log('Starting PostgreSQL view definition extraction job...');

    const button = document.querySelector('#postgres-views .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view definition extraction...');
    updateProgress(0, 'Starting PostgreSQL view definition extraction');

    try {
        const response = await fetch('/api/views/postgres/stubs/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view extraction job started:', result.jobId);
            updateMessage('PostgreSQL view extraction job started successfully');

            // Start polling for progress
            pollViewJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view extraction job:', error);
        updateMessage('Failed to start PostgreSQL view extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Poll view job status until completion
async function pollViewJobStatus(jobId, database = 'oracle') {
    console.log('Polling view job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('View job status:', result);

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
                console.log('View job completed successfully');
                updateProgress(100, 'View job completed successfully');
                updateMessage('View definition extraction completed');

                // Get job results
                await getViewJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('View job failed:', result.error);
                updateProgress(0, 'View job failed');
                updateMessage('View extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-views .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollViewJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling view job status:', error);
        updateMessage('Error checking view job status: ' + error.message);
        updateProgress(0, 'Error checking view job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-views .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Get view job results and display them
async function getViewJobResults(jobId, database = 'oracle') {
    console.log('Getting view job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('View job results:', result);
            displayViewResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get view job results');
        }

    } catch (error) {
        console.error('Error getting view job results:', error);
        updateMessage('Error getting view results: ' + error.message);
    }
}

// Display view extraction results
function displayViewResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Extract view count from summary
        const viewCount = result.result ? result.result.length : 0;

        // Update view count badge
        updateComponentCount(`${database}-views`, viewCount);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted ${viewCount} ${databaseName} views`);

        // Populate view list
        if (result.result && result.result.length > 0) {
            populateViewList(result.result, database);

            // Show view list
            document.getElementById(`${database}-view-list`).style.display = 'block';
        }
    }
}

// Populate view list with extracted view metadata
function populateViewList(views, database = 'oracle') {
    const viewItemsElement = document.getElementById(`${database}-view-items`);

    if (!viewItemsElement) {
        console.warn('View items element not found');
        return;
    }

    // Clear existing items
    viewItemsElement.innerHTML = '';

    if (views && views.length > 0) {
        // Group views by schema
        const viewsBySchema = {};
        views.forEach(view => {
            if (!viewsBySchema[view.schema]) {
                viewsBySchema[view.schema] = [];
            }
            viewsBySchema[view.schema].push(view);
        });

        Object.entries(viewsBySchema).forEach(([schemaName, schemaViews]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaViews.length} views)`;
            schemaHeader.onclick = () => toggleViewSchemaGroup(database, schemaName);

            const viewItems = document.createElement('div');
            viewItems.className = 'table-items-list';
            viewItems.id = `${database}-${schemaName}-views`;

            // Add individual view entries for this schema
            schemaViews.forEach(view => {
                const viewItem = document.createElement('div');
                viewItem.className = 'table-item';
                const columnCount = view.columns ? view.columns.length : 0;
                viewItem.innerHTML = `${view.viewName} (${columnCount} cols)`;
                viewItems.appendChild(viewItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(viewItems);
            viewItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noViewsItem = document.createElement('div');
        noViewsItem.className = 'table-item';
        noViewsItem.textContent = 'No views found';
        noViewsItem.style.fontStyle = 'italic';
        noViewsItem.style.color = '#999';
        viewItemsElement.appendChild(noViewsItem);
    }
}

// Toggle view list visibility
function toggleViewList(database) {
    const viewItems = document.getElementById(`${database}-view-items`);
    const header = document.querySelector(`#${database}-view-list .table-list-header`);

    if (!viewItems || !header) {
        console.warn(`View list elements not found for database: ${database}`);
        return;
    }

    if (viewItems.style.display === 'none') {
        viewItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        viewItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle view schema group visibility
function toggleViewSchemaGroup(database, schemaName) {
    const viewItems = document.getElementById(`${database}-${schemaName}-views`);
    const header = event.target;

    if (!viewItems || !header) {
        console.warn(`View schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (viewItems.style.display === 'none') {
        viewItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        viewItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// View Stub Creation Functions

// Create PostgreSQL view stubs (starts the job)
async function createPostgresViewStubs() {
    console.log('Starting PostgreSQL view stub creation job...');

    updateComponentCount("postgres-views", "-");

    const button = document.querySelector('#postgres-views .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view stub creation...');
    updateProgress(0, 'Starting PostgreSQL view stub creation');

    try {
        const response = await fetch('/api/views/postgres/stubs/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view stub creation job started:', result.jobId);
            updateMessage('PostgreSQL view stub creation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollViewStubCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view stub creation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view stub creation job:', error);
        updateMessage('Failed to start PostgreSQL view stub creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view stub creation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create View Stubs';
        }
    }
}

async function pollViewStubCreationJobStatus(jobId, database) {
    console.log(`Polling view stub creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`View stub creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`View stub creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleViewStubCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-views .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create View Stubs';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling view stub creation job status:', error);
                updateMessage('Error checking view stub creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-views .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create View Stubs';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleViewStubCreationJobComplete(result, database) {
    console.log(`View stub creation job results for ${database}:`, result);

    // Access counts from top-level result (these are provided by JobResource)
    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `View stub creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`View stub creation completed successfully: ${createdCount} view stubs created, ${skippedCount} already existed`);
    } else {
        updateMessage(`View stub creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update view stub creation results section
    displayViewStubCreationResults(result, database);
}

function displayViewStubCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-view-stub-creation-results`);
    const detailsDiv = document.getElementById(`${database}-view-stub-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('View stub creation results container not found');
        return;
    }

    let html = '';

    // Access result properties from summary object (matches function-service.js pattern)
    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-views", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created views - convert Map to Array using Object.values()
        if (summary.createdCount > 0 && summary.createdViews) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created View Stubs:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdViews).forEach(view => {
                html += `<div class="table-item created">${view.viewName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped views - convert Map to Array using Object.values()
        if (summary.skippedCount > 0 && summary.skippedViews) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Views (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedViews).forEach(view => {
                html += `<div class="table-item skipped">${view.viewName}</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert Map to Array using Object.values()
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Views:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.viewName}</strong>: ${error.error}`;
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

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleViewStubCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-view-stub-creation-results`);
    const detailsDiv = document.getElementById(`${database}-view-stub-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// View Implementation Functions (Phase 2)

// Create PostgreSQL view implementations (replaces stubs with actual SQL)
async function createPostgresViewImplementation() {
    console.log('Starting PostgreSQL view implementation job...');

    updateComponentCount("postgres-view-implementation", "-");

    const button = document.querySelector('#postgres-view-implementation .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view implementation...');
    updateProgress(0, 'Starting PostgreSQL view implementation');

    try {
        const response = await fetch('/api/views/postgres/implementation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view implementation job started:', result.jobId);
            updateMessage('PostgreSQL view implementation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollViewImplementationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view implementation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view implementation job:', error);
        updateMessage('Failed to start PostgreSQL view implementation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view implementation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Views';
        }
    }
}

async function pollViewImplementationJobStatus(jobId, database) {
    console.log(`Polling view implementation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`View implementation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`View implementation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleViewImplementationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-view-implementation .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Views';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling view implementation job status:', error);
                updateMessage('Error checking view implementation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-view-implementation .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Views';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleViewImplementationJobComplete(result, database) {
    console.log(`View implementation job results for ${database}:`, result);

    // Access counts from top-level result (these are provided by JobResource)
    const implementedCount = result.implementedCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `View implementation completed: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`View implementation completed successfully: ${implementedCount} views implemented, ${skippedCount} skipped`);
    } else {
        updateMessage(`View implementation completed with errors: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update view implementation results section
    displayViewImplementationResults(result, database);
}

function displayViewImplementationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-view-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-view-implementation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('View implementation results container not found');
        return;
    }

    let html = '';

    // Access result properties from summary object (matches function-service.js pattern)
    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-view-implementation", summary.implementedCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Implemented: ${summary.implementedCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show implemented views - convert Map to Array using Object.values()
        if (summary.implementedCount > 0 && summary.implementedViews) {
            html += '<div class="created-tables-section">';
            html += '<h4>Implemented Views:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.implementedViews).forEach(view => {
                html += `<div class="table-item created">${view.viewName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped views - convert Map to Array using Object.values()
        if (summary.skippedCount > 0 && summary.skippedViews) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Views:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedViews).forEach(view => {
                html += `<div class="table-item skipped">${view.viewName}</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert Map to Array using Object.values()
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Views:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.viewName}</strong>: ${error.error}`;
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

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleViewImplementationResults(database) {
    const resultsDiv = document.getElementById(`${database}-view-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-view-implementation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

function toggleViewImplementationVerificationResults(database) {
    const resultsDiv = document.getElementById(`${database}-view-implementation-verification-results`);
    const detailsDiv = document.getElementById(`${database}-view-implementation-verification-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// Verify PostgreSQL view implementations
async function verifyPostgresViewImplementation() {
    console.log('Starting PostgreSQL view implementation verification job...');

    const button = document.querySelector('#postgres-view-implementation .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view implementation verification...');
    updateProgress(0, 'Starting PostgreSQL view implementation verification');

    try {
        const response = await fetch('/api/views/postgres/implementation/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view implementation verification job started:', result.jobId);
            updateMessage('PostgreSQL view implementation verification job started successfully');

            // Start polling for progress
            pollViewImplementationVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view implementation verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view implementation verification job:', error);
        updateMessage('Failed to start PostgreSQL view implementation verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view implementation verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function pollViewImplementationVerificationJobStatus(jobId) {
    console.log('Polling view implementation verification job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('View implementation verification job status:', result);

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
                console.log('View implementation verification job completed successfully');
                updateProgress(100, 'View implementation verification completed successfully');
                updateMessage('View implementation verification completed');

                // Get job results
                await getViewImplementationVerificationJobResults(jobId);
            } else if (result.status === 'FAILED') {
                console.error('View implementation verification job failed:', result.error);
                updateProgress(0, 'View implementation verification failed');
                updateMessage('View implementation verification failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable button
            const button = document.querySelector('#postgres-view-implementation .refresh-btn');
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollViewImplementationVerificationJobStatus(jobId), 1000);
        }

    } catch (error) {
        console.error('Error polling view implementation verification job status:', error);
        updateMessage('Error checking view implementation verification job status: ' + error.message);
        updateProgress(0, 'Error checking view implementation verification job status');

        // Re-enable button
        const button = document.querySelector('#postgres-view-implementation .refresh-btn');
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function getViewImplementationVerificationJobResults(jobId) {
    console.log('Getting view implementation verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('View implementation verification job results:', result);

            // The result is now the unwrapped ViewImplementationVerificationResult object
            const verificationResult = result.result;

            if (verificationResult) {
                // Counts are also at top level for convenience
                const verifiedCount = result.verifiedCount || 0;
                const failedCount = result.failedCount || 0;
                const warningCount = result.warningCount || 0;

                if (result.isSuccessful) {
                    updateMessage(`View implementation verification completed: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                } else {
                    updateMessage(`View implementation verification found issues: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                }

                updateComponentCount("postgres-view-implementation", verifiedCount);

                // Display the detailed verification results
                displayViewImplementationVerificationResults(verificationResult);
            } else {
                updateMessage('View implementation verification completed but returned no results');
            }
        } else {
            throw new Error(result.message || 'Failed to get view implementation verification job results');
        }

    } catch (error) {
        console.error('Error getting view implementation verification job results:', error);
        updateMessage('Error getting view implementation verification results: ' + error.message);
    }
}

// Display view implementation verification results
function displayViewImplementationVerificationResults(verificationResult) {
    const resultsDiv = document.getElementById('postgres-view-implementation-verification-results');
    const detailsDiv = document.getElementById('postgres-view-implementation-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('View implementation verification results container not found');
        return;
    }

    let html = '';

    // Summary statistics
    html += '<div class="table-creation-summary">';
    html += '<div class="summary-stats">';
    html += `<span class="stat-item created">Verified: ${verificationResult.verifiedCount || 0}</span>`;
    html += `<span class="stat-item errors">Failed: ${verificationResult.failedCount || 0}</span>`;
    html += `<span class="stat-item skipped">Warnings: ${verificationResult.warningCount || 0}</span>`;
    html += '</div>';
    html += '</div>';

    // Show verified views with row counts - GROUPED BY SCHEMA
    if (verificationResult.verifiedViews && verificationResult.verifiedViews.length > 0) {
        html += '<div class="created-tables-section">';
        html += '<h4>Verified Views (Implemented):</h4>';
        html += generateSchemaGroupedViewList(verificationResult.verifiedViews, 'verified', verificationResult.rowCounts);
        html += '</div>';
    }

    // Show failed views with failure reasons - GROUPED BY SCHEMA
    if (verificationResult.failedViews && verificationResult.failedViews.length > 0) {
        html += '<div class="error-tables-section">';
        html += '<h4>Failed Views (Not Implemented or Errors):</h4>';
        html += generateSchemaGroupedFailedViewList(verificationResult.failedViews, verificationResult.failureReasons);
        html += '</div>';
    }

    // Show warnings - GROUPED BY SCHEMA
    if (verificationResult.warnings && verificationResult.warnings.length > 0) {
        html += '<div class="skipped-tables-section">';
        html += '<h4>Warnings:</h4>';
        html += generateSchemaGroupedWarningList(verificationResult.warnings);
        html += '</div>';
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

// Helper function to generate schema-grouped view list for verified views
function generateSchemaGroupedViewList(verifiedViews, statusClass, rowCounts) {
    // Group by schema
    const viewsBySchema = {};
    verifiedViews.forEach(view => {
        const qualifiedName = view.viewName;
        const parts = qualifiedName.split('.');
        const schema = parts.length > 1 ? parts[0] : 'unknown';
        const viewName = parts.length > 1 ? parts[1] : qualifiedName;

        if (!viewsBySchema[schema]) {
            viewsBySchema[schema] = [];
        }
        viewsBySchema[schema].push({
            qualifiedName: qualifiedName,
            viewName: viewName,
            rowCount: view.rowCount
        });
    });

    let html = '<div class="table-items">';

    Object.entries(viewsBySchema).sort(([a], [b]) => a.localeCompare(b)).forEach(([schemaName, schemaViews]) => {
        const schemaId = `view-verification-${statusClass}-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▼</span> ${schemaName} (${schemaViews.length} views)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}">`;

        schemaViews.forEach(view => {
            const rowCountText = view.rowCount !== undefined ? ` (${view.rowCount} rows)` : '';
            html += `<div class="table-item ${statusClass}">${view.viewName}${rowCountText} ✓</div>`;
        });

        html += '</div>';
        html += '</div>';
    });

    html += '</div>';
    return html;
}

// Helper function to generate schema-grouped view list for failed views
function generateSchemaGroupedFailedViewList(failedViews, failureReasons) {
    // Group by schema
    const viewsBySchema = {};
    failedViews.forEach(qualifiedName => {
        const parts = qualifiedName.split('.');
        const schema = parts.length > 1 ? parts[0] : 'unknown';
        const viewName = parts.length > 1 ? parts[1] : qualifiedName;

        if (!viewsBySchema[schema]) {
            viewsBySchema[schema] = [];
        }
        viewsBySchema[schema].push({
            qualifiedName: qualifiedName,
            viewName: viewName,
            reason: failureReasons && failureReasons[qualifiedName] ? failureReasons[qualifiedName] : 'Unknown reason'
        });
    });

    let html = '<div class="table-items">';

    Object.entries(viewsBySchema).sort(([a], [b]) => a.localeCompare(b)).forEach(([schemaName, schemaViews]) => {
        const schemaId = `view-verification-failed-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▼</span> ${schemaName} (${schemaViews.length} views)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}">`;

        schemaViews.forEach(view => {
            html += `<div class="table-item error">`;
            html += `<strong>${view.viewName}</strong>: ${view.reason}`;
            html += `</div>`;
        });

        html += '</div>';
        html += '</div>';
    });

    html += '</div>';
    return html;
}

// Helper function to generate schema-grouped warning list
function generateSchemaGroupedWarningList(warnings) {
    // Group by schema - warnings are in format "schema.viewname: warning message"
    const warningsBySchema = {};
    warnings.forEach(warning => {
        const colonIndex = warning.indexOf(':');
        const qualifiedName = colonIndex > 0 ? warning.substring(0, colonIndex).trim() : warning;
        const message = colonIndex > 0 ? warning.substring(colonIndex + 1).trim() : 'Warning';

        const parts = qualifiedName.split('.');
        const schema = parts.length > 1 ? parts[0] : 'unknown';
        const viewName = parts.length > 1 ? parts[1] : qualifiedName;

        if (!warningsBySchema[schema]) {
            warningsBySchema[schema] = [];
        }
        warningsBySchema[schema].push({
            viewName: viewName,
            message: message
        });
    });

    let html = '<div class="table-items">';

    Object.entries(warningsBySchema).sort(([a], [b]) => a.localeCompare(b)).forEach(([schemaName, schemaWarnings]) => {
        const schemaId = `view-verification-warnings-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▼</span> ${schemaName} (${schemaWarnings.length} warnings)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}">`;

        schemaWarnings.forEach(warning => {
            html += `<div class="table-item skipped">${warning.viewName}: ${warning.message}</div>`;
        });

        html += '</div>';
        html += '</div>';
    });

    html += '</div>';
    return html;
}

// Generic toggle function for schema groups
function toggleSchemaGroup(schemaId) {
    const viewItems = document.getElementById(schemaId);
    const indicator = document.getElementById(`${schemaId}-indicator`);

    if (!viewItems) {
        console.warn(`Schema group not found: ${schemaId}`);
        return;
    }

    if (viewItems.style.display === 'none') {
        viewItems.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        viewItems.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
    }
}

// ==========================================
// Unified View Verification (NEW)
// ==========================================

/**
 * Verify all PostgreSQL views (unified verification job).
 * Replaces separate stub and implementation verification jobs.
 * Returns DDL for manual inspection instead of row counts.
 */
async function verifyAllPostgresViews() {
    console.log('Starting unified PostgreSQL view verification job...');

    const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view verification...');
    updateProgress(0, 'Starting PostgreSQL view verification');

    try {
        const response = await fetch('/api/views/postgres/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view verification job started:', result.jobId);
            updateMessage('PostgreSQL view verification job started successfully');

            // Start polling for progress
            await pollUnifiedViewVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view verification job:', error);
        updateMessage('Failed to start PostgreSQL view verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳ Verify All Views';
        }
    }
}

async function pollUnifiedViewVerificationJobStatus(jobId) {
    console.log('Polling unified view verification job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Unified view verification job status:', result);

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
                console.log('Unified view verification job completed successfully');
                updateProgress(100, 'View verification completed successfully');
                updateMessage('View verification completed');

                // Get job results
                await getUnifiedViewVerificationJobResults(jobId);
            } else if (result.status === 'FAILED') {
                console.error('Unified view verification job failed:', result.error);
                updateProgress(0, 'View verification failed');
                updateMessage('View verification failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable button
            const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳ Verify All Views';
            }
        } else {
            // Continue polling
            setTimeout(() => pollUnifiedViewVerificationJobStatus(jobId), 1000);
        }

    } catch (error) {
        console.error('Error polling unified view verification job status:', error);
        updateMessage('Error checking view verification job status: ' + error.message);
        updateProgress(0, 'Error checking view verification job status');

        // Re-enable button
        const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳ Verify All Views';
        }
    }
}

async function getUnifiedViewVerificationJobResults(jobId) {
    console.log('Getting unified view verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Unified view verification job results:', result);

            // The result contains the ViewVerificationResult object
            const verificationResult = result.result;

            if (verificationResult) {
                const totalViews = result.totalViews || 0;
                const implementedCount = result.implementedCount || 0;
                const stubCount = result.stubCount || 0;
                const errorCount = result.errorCount || 0;

                updateMessage(`View verification completed: ${totalViews} views (${implementedCount} implemented, ${stubCount} stubs, ${errorCount} errors)`);
                updateComponentCount("postgres-view-implementation", implementedCount);

                // Display the detailed verification results
                displayUnifiedViewVerificationResults(verificationResult);
            } else {
                updateMessage('View verification completed but returned no results');
            }
        } else {
            throw new Error(result.message || 'Failed to get view verification job results');
        }

    } catch (error) {
        console.error('Error getting unified view verification job results:', error);
        updateMessage('Error getting view verification results: ' + error.message);
    }
}

/**
 * Display unified view verification results with DDL inspection.
 * Groups views by schema with collapsible DDL sections.
 */
function displayUnifiedViewVerificationResults(verificationResult) {
    const resultsDiv = document.getElementById('postgres-unified-view-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-view-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Unified view verification results container not found');
        return;
    }

    let html = '';

    // Summary statistics
    const totalViews = verificationResult.totalViews || 0;
    const implementedCount = verificationResult.implementedCount || 0;
    const stubCount = verificationResult.stubCount || 0;
    const errorCount = verificationResult.errorCount || 0;

    html += '<div class="table-creation-summary">';
    html += '<div class="summary-stats">';
    html += `<span class="stat-item created">Implemented: ${implementedCount}</span>`;
    html += `<span class="stat-item skipped">Stubs: ${stubCount}</span>`;
    html += `<span class="stat-item errors">Errors: ${errorCount}</span>`;
    html += `<span class="stat-item">Total: ${totalViews}</span>`;
    html += '</div>';
    html += '</div>';

    // Generate schema-grouped views with DDL
    const viewsBySchema = verificationResult.viewsBySchema || {};

    Object.keys(viewsBySchema).sort().forEach(schemaName => {
        const schemaViews = viewsBySchema[schemaName] || [];
        const schemaId = `view-verification-schema-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        // Count by status for this schema
        const schemaImplemented = schemaViews.filter(v => v.status === 'IMPLEMENTED').length;
        const schemaStubs = schemaViews.filter(v => v.status === 'STUB').length;
        const schemaErrors = schemaViews.filter(v => v.status === 'ERROR').length;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▶</span> `;
        html += `${schemaName} (${schemaViews.length} views - `;
        html += `${schemaImplemented} implemented, ${schemaStubs} stubs, ${schemaErrors} errors)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}" style="display: none;">`;

        // Sort views by name within schema
        schemaViews.sort((a, b) => a.viewName.localeCompare(b.viewName));

        schemaViews.forEach(view => {
            const viewId = `view-ddl-${schemaName}-${view.viewName}`.replace(/[^a-z0-9]/gi, '_');
            const statusClass = view.status === 'IMPLEMENTED' ? 'created' :
                               view.status === 'STUB' ? 'skipped' : 'error';
            const statusBadge = view.status === 'IMPLEMENTED' ? '✓ IMPLEMENTED' :
                               view.status === 'STUB' ? '⚠ STUB' : '✗ ERROR';

            html += `<div class="table-item ${statusClass}">`;
            html += `<div class="view-header" onclick="toggleViewDdl('${viewId}')">`;
            html += `<span class="toggle-indicator" id="${viewId}-indicator">▶</span> `;
            html += `<strong>${view.viewName}</strong> <span class="status-badge">[${statusBadge}]</span>`;
            html += '</div>';

            // DDL section (collapsible, starts collapsed)
            html += `<div class="view-ddl-section" id="${viewId}" style="display: none;">`;
            if (view.viewDdl) {
                html += '<pre class="sql-statement">';
                html += escapeHtml(view.viewDdl);
                html += '</pre>';
            } else if (view.errorMessage) {
                html += `<div class="error-message">Error: ${escapeHtml(view.errorMessage)}</div>`;
            } else {
                html += '<div class="error-message">No DDL available</div>';
            }
            html += '</div>';
            html += '</div>';
        });

        html += '</div>';
        html += '</div>';
    });

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

/**
 * Toggle view DDL visibility.
 */
function toggleViewDdl(viewId) {
    const ddlSection = document.getElementById(viewId);
    const indicator = document.getElementById(`${viewId}-indicator`);

    if (!ddlSection) {
        console.warn(`View DDL section not found: ${viewId}`);
        return;
    }

    if (ddlSection.style.display === 'none') {
        ddlSection.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        ddlSection.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
    }
}

/**
 * Toggle unified view verification results visibility.
 */
function toggleUnifiedViewVerificationResults() {
    const resultsDiv = document.getElementById('postgres-unified-view-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-view-verification-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (toggleIndicator) toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        if (toggleIndicator) toggleIndicator.textContent = '▼';
    }
}

/**
 * Escape HTML to prevent XSS attacks in DDL display.
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
