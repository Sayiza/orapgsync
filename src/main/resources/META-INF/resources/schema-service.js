/**
 * Schema Service Module
 *
 * This module handles all schema-related operations for both Oracle and PostgreSQL databases:
 * - Schema extraction from Oracle and PostgreSQL
 * - Schema list population and display management
 * - Schema creation in PostgreSQL
 * - Job status polling for schema operations
 * - Schema creation results display and management
 *
 * Dependencies: Requires utility functions from app.js:
 * - updateMessage(message)
 * - updateComponentCount(componentId, count, status)
 * - updateProgress(percentage, currentTask)
 * - pollJobUntilComplete(jobId, database, type)
 */

// ============================================================================
// Schema Extraction Functions
// ============================================================================

/**
 * Load Oracle schemas using job-based approach
 * Initiates an extraction job and polls until completion
 */
async function loadOracleSchemas() {
    console.log('Starting Oracle schema extraction job...');
    updateMessage('Starting Oracle schema extraction...');

    updateComponentCount("oracle-schemas", "-");

    try {
        // Start the job
        const startResponse = await fetch('/api/schemas/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('Oracle schema extraction job started:', startResult.jobId);
            updateMessage('Oracle schema extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#oracle-schemas .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'oracle', 'schemas');

        } else {
            updateComponentCount('oracle-schemas', '!', 'error');
            updateMessage('Failed to start Oracle schema extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting Oracle schema extraction:', error);
        updateComponentCount('oracle-schemas', '!', 'error');
        updateMessage('Error starting Oracle schema extraction: ' + error.message);
    }
}

/**
 * Load PostgreSQL schemas using job-based approach
 * Initiates an extraction job and polls until completion
 */
async function loadPostgresSchemas() {
    console.log('Starting PostgreSQL schema extraction job...');
    updateMessage('Starting PostgreSQL schema extraction...');

    try {
        // Start the job
        const startResponse = await fetch('/api/schemas/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('PostgreSQL schema extraction job started:', startResult.jobId);
            updateMessage('PostgreSQL schema extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#postgres-schemas .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'postgres', 'schemas');

        } else {
            updateComponentCount('postgres-schemas', '!', 'error');
            updateMessage('Failed to start PostgreSQL schema extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting PostgreSQL schema extraction:', error);
        updateComponentCount('postgres-schemas', '!', 'error');
        updateMessage('Error starting PostgreSQL schema extraction: ' + error.message);
    }
}

/**
 * Get schema job results and display them
 * Called after a schema extraction job completes
 *
 * @param {string} jobId - The job ID to fetch results for
 * @param {string} database - The database type ('oracle' or 'postgres')
 */
async function getSchemaJobResults(jobId, database) {
    console.log('Getting schema job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Schema job results:', result);

            const schemas = result.schemas || [];

            // Update component count
            updateComponentCount(`${database}-schemas`, schemas.length);

            // Populate the schema list
            populateSchemaList(database, schemas);

            // Show the schema list if we have schemas
            if (schemas.length > 0) {
                document.getElementById(`${database}-schema-list`).style.display = 'block';
            }

            updateMessage(`Loaded ${schemas.length} ${database} schemas`);
        } else {
            throw new Error(result.message || 'Failed to get schema job results');
        }

    } catch (error) {
        console.error('Error getting schema job results:', error);
        updateComponentCount(`${database}-schemas`, '!', 'error');
        updateMessage(`Error getting schema results: ${error.message}`);
    }
}

// ============================================================================
// Schema Display Functions
// ============================================================================

/**
 * Populate schema list with schema names
 * Creates DOM elements to display schemas in the UI
 *
 * @param {string} database - The database type ('oracle' or 'postgres')
 * @param {Array<string>} schemas - Array of schema names to display
 */
function populateSchemaList(database, schemas) {
    const schemaItemsElement = document.getElementById(`${database}-schema-items`);

    if (!schemaItemsElement) {
        console.warn(`Schema items element not found for database: ${database}`);
        return;
    }

    // Clear existing items
    schemaItemsElement.innerHTML = '';

    if (schemas && schemas.length > 0) {
        schemas.forEach(schema => {
            const schemaItem = document.createElement('div');
            schemaItem.className = 'schema-item';
            schemaItem.textContent = schema;
            schemaItemsElement.appendChild(schemaItem);
        });
    } else {
        const noSchemasItem = document.createElement('div');
        noSchemasItem.className = 'schema-item';
        noSchemasItem.textContent = 'No schemas found';
        noSchemasItem.style.fontStyle = 'italic';
        noSchemasItem.style.color = '#999';
        schemaItemsElement.appendChild(noSchemasItem);
    }
}

/**
 * Toggle schema list visibility
 * Expands or collapses the schema list UI element
 *
 * @param {string} database - The database type ('oracle' or 'postgres')
 */
function toggleSchemaList(database) {
    const schemaItems = document.getElementById(`${database}-schema-items`);
    const header = document.querySelector(`#${database}-schema-list .schema-list-header`);

    if (!schemaItems || !header) {
        console.warn(`Schema list elements not found for database: ${database}`);
        return;
    }

    if (schemaItems.style.display === 'none') {
        schemaItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        schemaItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// ============================================================================
// Schema Creation Functions
// ============================================================================

/**
 * Create PostgreSQL schemas from Oracle schemas
 * Initiates a schema creation job and polls until completion
 */
async function createPostgresSchemas() {
    console.log('Starting PostgreSQL schema creation job...');
    const button = document.querySelector('#postgres-schemas .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateComponentCount("postgres-schemas", "-");

    updateMessage('Starting PostgreSQL schema creation...');
    updateProgress(0, 'Starting PostgreSQL schema creation');

    try {
        const response = await fetch('/api/schemas/postgres/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL schema creation job started:', result.jobId);
            updateMessage('PostgreSQL schema creation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollSchemaCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL schema creation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL schema creation job:', error);
        updateMessage('Failed to start PostgreSQL schema creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL schema creation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Schemas';
        }
    }
}

/**
 * Poll schema creation job status until completion
 * Continuously checks job status and updates progress UI
 *
 * @param {string} jobId - The job ID to poll
 * @param {string} database - The database type (typically 'postgres')
 * @returns {Promise} Resolves when job completes, rejects on error
 */
async function pollSchemaCreationJobStatus(jobId, database) {
    console.log(`Polling schema creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Schema creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Schema creation job completed for ${database}`);

                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleSchemaCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-schemas .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Schemas';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }

            } catch (error) {
                console.error('Error polling schema creation job status:', error);
                updateMessage('Error checking schema creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');

                // Re-enable button
                const button = document.querySelector(`#${database}-schemas .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Schemas';
                }

                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

/**
 * Handle schema creation job completion
 * Processes final results and updates UI with summary
 *
 * @param {Object} result - The job result object
 * @param {string} database - The database type (typically 'postgres')
 */
function handleSchemaCreationJobComplete(result, database) {
    console.log(`Schema creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Schema creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Schema creation completed successfully: ${createdCount} schemas created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Schema creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update schema creation results section
    displaySchemaCreationResults(result, database);

    // Refresh PostgreSQL schemas to show newly created ones ... not really needed
    //setTimeout(() => {
    //    loadPostgresSchemas();
    //}, 1000);
}

// ============================================================================
// Schema Creation Results Display Functions
// ============================================================================

/**
 * Display schema creation results in the UI
 * Creates detailed result sections for created, skipped, and failed schemas
 *
 * @param {Object} result - The job result object containing summary data
 * @param {string} database - The database type (typically 'postgres')
 */
function displaySchemaCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-schema-creation-results`);
    const detailsDiv = document.getElementById(`${database}-schema-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Schema creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-schemas", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="schema-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created schemas
        if (summary.createdCount > 0) {
            html += '<div class="created-schemas-section">';
            html += '<h4>Created Schemas:</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.createdSchemas).forEach(schema => {
                html += `<div class="schema-item created">${schema.schema} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped schemas
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-schemas-section">';
            html += '<h4>Skipped Schemas (already exist):</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.skippedSchemas).forEach(schema => {
                html += `<div class="schema-item skipped">${schema.schema} (${schema.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-schemas-section">';
            html += '<h4>Failed Schemas:</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="schema-item error">${error.schema}: ${error.error}</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

/**
 * Toggle schema creation results visibility
 * Expands or collapses the detailed schema creation results
 *
 * @param {string} database - The database type (typically 'postgres')
 */
function toggleSchemaCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-schema-creation-results`);
    const detailsDiv = document.getElementById(`${database}-schema-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}
