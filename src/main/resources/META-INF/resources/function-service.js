/**
 * Function Service Module
 *
 * This module handles all function/procedure-related operations for the Oracle-to-PostgreSQL migration tool.
 * It provides functionality for:
 * - Extracting function/procedure metadata from Oracle and PostgreSQL databases
 * - Creating function/procedure stubs in PostgreSQL
 * - Polling job status for extraction and creation operations
 * - Displaying function lists and creation results in the UI
 * - Managing UI interactions for function-related components
 *
 * Functions included:
 * - extractOracleFunctions(): Initiates Oracle function/procedure extraction job
 * - extractPostgresFunctions(): Initiates PostgreSQL function/procedure extraction job
 * - createPostgresFunctionStubs(): Initiates PostgreSQL function stub creation job
 * - pollFunctionJobStatus(): Monitors function extraction job progress
 * - pollFunctionStubCreationJobStatus(): Monitors function stub creation job progress
 * - getFunctionJobResults(): Retrieves and displays extraction results
 * - getFunctionStubCreationResults(): Retrieves and displays creation results
 * - populateFunctionList(): Populates UI with extracted functions grouped by schema
 * - toggleFunctionSchemaGroup(): Toggles visibility of function groups in UI
 * - displayFunctionStubCreationResults(): Displays detailed creation results
 * - toggleFunctionList(): Toggles visibility of function list panels
 * - toggleFunctionCreationResults(): Toggles visibility of creation results panels
 */

// ===== FUNCTION FUNCTIONS =====

async function extractOracleFunctions() {
    console.log('Starting Oracle function/procedure extraction job...');

    const button = document.querySelector('#oracle-functions .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle function/procedure extraction...');
    updateProgress(0, 'Starting Oracle function/procedure extraction');

    try {
        const response = await fetch('/api/jobs/oracle/function/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle function extraction job started:', result.jobId);
            updateMessage('Oracle function extraction job started successfully');

            // Start polling for progress
            await pollFunctionJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle function extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle function extraction job:', error);
        updateMessage('Failed to start Oracle function extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle function extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

async function extractPostgresFunctions() {
    console.log('Starting PostgreSQL function/procedure extraction job...');

    const button = document.querySelector('#postgres-functions .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL function/procedure extraction...');
    updateProgress(0, 'Starting PostgreSQL function/procedure extraction');

    try {
        const response = await fetch('/api/jobs/postgres/function-stub-verification/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL function extraction job started:', result.jobId);
            updateMessage('PostgreSQL function extraction job started successfully');

            // Start polling for progress
            await pollFunctionJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL function extraction job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL function extraction job:', error);
        updateMessage('Failed to start PostgreSQL function extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL function extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

async function createPostgresFunctionStubs() {
    console.log('Starting PostgreSQL function stub creation job...');

    updateComponentCount("postgres-functions", "-");

    const button = document.querySelector('#postgres-functions .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL function stub creation...');
    updateProgress(0, 'Starting PostgreSQL function stub creation');

    try {
        const response = await fetch('/api/jobs/postgres/function-stub/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL function stub creation job started:', result.jobId);
            updateMessage('PostgreSQL function stub creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollFunctionStubCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL function stub creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL function stub creation job:', error);
        updateMessage('Failed to start PostgreSQL function stub creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL function stub creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Function Stubs';
        }
    }
}

async function pollFunctionJobStatus(jobId, database) {
    console.log(`Polling function job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Function job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Function extraction completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} function extraction completed`);

                    // Get job results and update the UI
                    await getFunctionJobResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-functions .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⚙';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} function extraction failed`);
                    updateMessage(`${database.toUpperCase()} function extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-functions .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⚙';
                    }

                    reject(new Error(status.error || 'Function extraction failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling function job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollFunctionStubCreationJobStatus(jobId, database) {
    console.log(`Polling function stub creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Function stub creation job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Function stub creation completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} function stub creation completed`);

                    // Get job results and display
                    await getFunctionStubCreationResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-functions .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Function Stubs';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} function stub creation failed`);
                    updateMessage(`${database.toUpperCase()} function stub creation failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-functions .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Function Stubs';
                    }

                    reject(new Error(status.error || 'Function stub creation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling function stub creation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getFunctionJobResults(jobId, database) {
    console.log('Getting function job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Function job results:', result);

            // Update badge count
            const functionCount = result.functionCount || 0;
            updateComponentCount(`${database}-functions`, functionCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            if (result.summary && result.summary.message) {
                updateMessage(`${databaseName}: ${result.summary.message}`);
            } else {
                updateMessage(`Extracted ${functionCount} ${databaseName} functions/procedures`);
            }

            // Populate function list UI
            populateFunctionList(result, database);

            // Show function list if there are functions
            if (functionCount > 0) {
                document.getElementById(`${database}-function-list`).style.display = 'block';
            }

        } else {
            throw new Error(result.message || 'Failed to get function job results');
        }

    } catch (error) {
        console.error('Error getting function job results:', error);
        updateMessage('Error getting function results: ' + error.message);
        updateComponentCount(`${database}-functions`, '?', 'error');
    }
}

async function getFunctionStubCreationResults(jobId, database) {
    console.log('Getting function stub creation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Function stub creation job results:', result);

            // Display the creation results
            displayFunctionStubCreationResults(result, database);

            // Update badge count
            const functionCount = result.createdCount || 0;
            updateComponentCount(`${database}-functions`, functionCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            updateMessage(`${databaseName}: Created ${result.createdCount} function stubs, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get function stub creation results');
        }

    } catch (error) {
        console.error('Error getting function stub creation results:', error);
        updateMessage('Error getting function stub creation results: ' + error.message);
    }
}

function populateFunctionList(result, database) {
    const functionItemsElement = document.getElementById(`${database}-function-items`);

    if (!functionItemsElement) {
        console.warn('Function items element not found');
        return;
    }

    // Clear existing items
    functionItemsElement.innerHTML = '';

    // Get functions from result
    const functions = result.result || [];

    if (functions && functions.length > 0) {
        // Group functions by schema
        const schemaGroups = {};
        functions.forEach(func => {
            const schema = func.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(func);
        });

        // Create schema groups
        Object.entries(schemaGroups).forEach(([schemaName, schemaFunctions]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaFunctions.length} functions/procedures)`;
            schemaHeader.onclick = () => toggleFunctionSchemaGroup(database, schemaName);

            const functionItems = document.createElement('div');
            functionItems.className = 'table-items-list';
            functionItems.id = `${database}-${schemaName}-functions`;

            // Add individual function entries for this schema
            schemaFunctions.forEach(func => {
                const functionItem = document.createElement('div');
                functionItem.className = 'table-item';

                // Display function name with package indicator if applicable
                let displayName = func.objectName;
                if (func.packageName) {
                    displayName = `${func.packageName}.${func.objectName}`;
                }

                // Add function/procedure indicator
                const typeIndicator = func.objectType === 'FUNCTION' ? '𝑓' : 'ₚ';
                functionItem.innerHTML = `<span class="function-type-indicator">${typeIndicator}</span> ${displayName}`;

                functionItems.appendChild(functionItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(functionItems);
            functionItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noFunctionsItem = document.createElement('div');
        noFunctionsItem.className = 'table-item';
        noFunctionsItem.textContent = 'No functions/procedures found';
        noFunctionsItem.style.fontStyle = 'italic';
        noFunctionsItem.style.color = '#999';
        functionItemsElement.appendChild(noFunctionsItem);
    }
}

function toggleFunctionSchemaGroup(database, schemaName) {
    const functionItems = document.getElementById(`${database}-${schemaName}-functions`);
    const header = functionItems.previousElementSibling;
    const indicator = header.querySelector('.toggle-indicator');

    if (functionItems.style.display === 'none') {
        functionItems.style.display = 'block';
        indicator.textContent = '▼';
    } else {
        functionItems.style.display = 'none';
        indicator.textContent = '▶';
    }
}

function displayFunctionStubCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-function-creation-results`);
    const detailsDiv = document.getElementById(`${database}-function-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Function stub creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-functions", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created functions - convert Map to Array using Object.values()
        if (summary.createdCount > 0 && summary.createdFunctions) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created Function Stubs:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdFunctions).forEach(func => {
                html += `<div class="table-item created">${func.functionName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped functions - convert Map to Array using Object.values()
        if (summary.skippedCount > 0 && summary.skippedFunctions) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Functions (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedFunctions).forEach(func => {
                html += `<div class="table-item skipped">${func.functionName} (already exists)</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert Map to Array using Object.values()
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Functions:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.functionName}</strong>: ${error.error}`;
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

function toggleFunctionList(database) {
    const functionItems = document.getElementById(`${database}-function-items`);
    const header = document.querySelector(`#${database}-function-list .table-list-header`);

    if (!functionItems || !header) {
        console.warn(`Function list elements not found for database: ${database}`);
        return;
    }

    if (functionItems.style.display === 'none') {
        functionItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        functionItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

function toggleFunctionCreationResults() {
    const resultsDiv = document.getElementById('postgres-function-creation-results');
    const detailsDiv = document.getElementById('postgres-function-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== END FUNCTION FUNCTIONS =====
