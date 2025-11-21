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
        const response = await fetch('/api/functions/oracle/extract', {
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
            button.innerHTML = '⟳';
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
        const response = await fetch('/api/functions/postgres/stubs/verify', {
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
            button.innerHTML = '⟳';
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
        const response = await fetch('/api/functions/postgres/stubs/create', {
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
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} function extraction failed`);
                    updateMessage(`${database.toUpperCase()} function extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-functions .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
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
        updateComponentCount(`${database}-functions`, '-', 'error');
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

// ===== STANDALONE FUNCTION IMPLEMENTATION FUNCTIONS (Phase 2) =====

// Create PostgreSQL standalone function implementations (replaces stubs with actual PL/pgSQL logic)
async function createPostgresStandaloneFunctionImplementation() {
    console.log('Starting PostgreSQL standalone function implementation job...');

    updateComponentCount("postgres-standalone-function-implementation", "-");

    const button = document.querySelector('#postgres-standalone-function-implementation .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL standalone function implementation...');
    updateProgress(0, 'Starting PostgreSQL standalone function implementation');

    try {
        const response = await fetch('/api/functions/postgres/standalone-implementation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL standalone function implementation job started:', result.jobId);
            updateMessage('PostgreSQL standalone function implementation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollStandaloneFunctionImplementationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL standalone function implementation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL standalone function implementation job:', error);
        updateMessage('Failed to start PostgreSQL standalone function implementation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL standalone function implementation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Functions';
        }
    }
}

async function pollStandaloneFunctionImplementationJobStatus(jobId, database) {
    console.log(`Polling standalone function implementation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Standalone function implementation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Standalone function implementation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleStandaloneFunctionImplementationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-standalone-function-implementation .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Functions';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling standalone function implementation job status:', error);
                updateMessage('Error checking standalone function implementation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-standalone-function-implementation .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Functions';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleStandaloneFunctionImplementationJobComplete(result, database) {
    console.log(`Standalone function implementation job results for ${database}:`, result);

    // Access counts from top-level result (these are provided by JobResource)
    const implementedCount = result.implementedCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Standalone function implementation completed: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Standalone function implementation completed successfully: ${implementedCount} functions/procedures implemented, ${skippedCount} skipped`);
    } else {
        updateMessage(`Standalone function implementation completed with errors: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update standalone function implementation results section
    displayStandaloneFunctionImplementationResults(result, database);
}

function displayStandaloneFunctionImplementationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-standalone-function-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-standalone-function-implementation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Standalone function implementation results container not found');
        return;
    }

    let html = '';

    // Access result properties from summary object
    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-standalone-function-implementation", summary.implementedCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Implemented: ${summary.implementedCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show implemented functions - convert Map to Array using Object.values()
        if (summary.implementedCount > 0 && summary.implementedFunctions) {
            html += '<div class="created-tables-section">';
            html += '<h4>Implemented Functions/Procedures:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.implementedFunctions)
                .sort((a, b) => {
                    // Sort by schema first, then by function name
                    const schemaCompare = (a.schema || '').localeCompare(b.schema || '');
                    if (schemaCompare !== 0) return schemaCompare;
                    return (a.functionName || '').localeCompare(b.functionName || '');
                })
                .forEach(func => {
                    html += `<div class="table-item created">${func.functionName} ✓</div>`;
                });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped functions - convert Map to Array using Object.values()
        if (summary.skippedCount > 0 && summary.skippedFunctions) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Functions/Procedures:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedFunctions)
                .sort((a, b) => {
                    // Sort by schema first, then by function name
                    const schemaCompare = (a.schema || '').localeCompare(b.schema || '');
                    if (schemaCompare !== 0) return schemaCompare;
                    return (a.functionName || '').localeCompare(b.functionName || '');
                })
                .forEach(func => {
                    html += `<div class="table-item skipped">${func.functionName}</div>`;
                });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert Map to Array using Object.values()
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Functions/Procedures:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors)
                .sort((a, b) => {
                    // Sort by function name (errors don't have schema property, parse from functionName)
                    return (a.functionName || '').localeCompare(b.functionName || '');
                })
                .forEach(error => {
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

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleStandaloneFunctionImplementationResults(database) {
    const resultsDiv = document.getElementById(`${database}-standalone-function-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-standalone-function-implementation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// Verify PostgreSQL standalone function implementations
async function verifyPostgresStandaloneFunctionImplementation() {
    console.log('Starting PostgreSQL standalone function implementation verification job...');

    const button = document.querySelector('#postgres-standalone-function-implementation .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL standalone function implementation verification...');
    updateProgress(0, 'Starting PostgreSQL standalone function implementation verification');

    try {
        const response = await fetch('/api/functions/postgres/standalone-implementation/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL standalone function implementation verification job started:', result.jobId);
            updateMessage('PostgreSQL standalone function implementation verification job started successfully');

            // Start polling for progress - use verification-specific handler
            await pollStandaloneFunctionVerificationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL standalone function implementation verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL standalone function implementation verification job:', error);
        updateMessage('Failed to start PostgreSQL standalone function implementation verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL standalone function implementation verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Polling handler for standalone function verification (returns List<FunctionMetadata>)
async function pollStandaloneFunctionVerificationJobStatus(jobId, database) {
    console.log(`Polling standalone function verification job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Standalone function verification job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                }

                if (status.isComplete) {
                    console.log(`Standalone function verification job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        // Verification returns FunctionImplementationVerificationResult
                        const verificationResult = result.result; // Now unwrapped by backend

                        if (verificationResult) {
                            const verifiedCount = result.verifiedCount || 0;
                            const failedCount = result.failedCount || 0;
                            const warningCount = result.warningCount || 0;

                            if (result.isSuccessful) {
                                updateMessage(`Function implementation verification completed: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                            } else {
                                updateMessage(`Function implementation verification found issues: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                            }

                            updateComponentCount("postgres-standalone-function-implementation", verifiedCount);
                            displayStandaloneFunctionImplementationVerificationResults(verificationResult);
                        }

                        updateProgress(100, 'Verification complete');
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-standalone-function-implementation .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling standalone function verification job status:', error);
                updateMessage('Error checking standalone function verification progress: ' + error.message);
                updateProgress(0, 'Error checking progress');

                // Re-enable button
                const button = document.querySelector(`#${database}-standalone-function-implementation .refresh-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = '⟳';
                }

                reject(error);
            }
        };

        pollOnce();
    });
}

// Display standalone function implementation verification results
function displayStandaloneFunctionImplementationVerificationResults(verificationResult) {
    const resultsDiv = document.getElementById('postgres-standalone-function-implementation-verification-results');
    const detailsDiv = document.getElementById('postgres-standalone-function-implementation-verification-details');

    let html = '';

    // Summary statistics
    html += '<div class="table-creation-summary">';
    html += '<div class="summary-stats">';
    html += `<span class="stat-item created">Verified: ${verificationResult.verifiedCount || 0}</span>`;
    html += `<span class="stat-item errors">Failed: ${verificationResult.failedCount || 0}</span>`;
    html += `<span class="stat-item skipped">Warnings: ${verificationResult.warningCount || 0}</span>`;
    html += '</div>';
    html += '</div>';

    // Show verified functions - GROUPED BY SCHEMA
    if (verificationResult.verifiedFunctions && verificationResult.verifiedFunctions.length > 0) {
        html += '<div class="created-tables-section">';
        html += '<h4>Verified Functions (Implemented):</h4>';
        html += generateSchemaGroupedFunctionList(verificationResult.verifiedFunctions, 'verified');
        html += '</div>';
    }

    // Show failed functions - GROUPED BY SCHEMA
    if (verificationResult.failedFunctions && verificationResult.failedFunctions.length > 0) {
        html += '<div class="error-tables-section">';
        html += '<h4>Failed Functions (Stubs or Errors):</h4>';
        html += generateSchemaGroupedFunctionList(verificationResult.failedFunctions, 'failed', verificationResult.failureReasons);
        html += '</div>';
    }

    // Show warnings - GROUPED BY SCHEMA
    if (verificationResult.warnings && verificationResult.warnings.length > 0) {
        html += '<div class="skipped-tables-section">';
        html += '<h4>Warnings:</h4>';
        html += '<div class="table-items">';
        verificationResult.warnings.forEach(warning => {
            html += `<div class="table-item warning">${escapeHtml(warning)}</div>`;
        });
        html += '</div>';
        html += '</div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

// Helper function to generate schema-grouped function list with expandable DDL
function generateSchemaGroupedFunctionList(functions, statusClass, failureReasons) {
    const functionsBySchema = {};
    functions.forEach(func => {
        const qualifiedName = func.qualifiedName || `${func.schema}.${func.functionName}`;
        const parts = qualifiedName.split('.');
        const schema = parts.length > 1 ? parts[0] : 'unknown';
        const functionName = parts.length > 1 ? parts[1] : qualifiedName;

        if (!functionsBySchema[schema]) {
            functionsBySchema[schema] = [];
        }
        functionsBySchema[schema].push({
            qualifiedName: qualifiedName,
            functionName: functionName,
            signature: func.signature,
            objectType: func.objectType,
            returnType: func.returnType,
            ddl: func.ddl,
            lineCount: func.lineCount,
            isStub: func.isStub
        });
    });

    let html = '<div class="table-items">';

    Object.entries(functionsBySchema).sort(([a], [b]) => a.localeCompare(b)).forEach(([schemaName, schemaFunctions]) => {
        const schemaId = `function-verification-${statusClass}-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▼</span> ${schemaName} (${schemaFunctions.length} functions)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}">`;

        schemaFunctions.forEach((func, index) => {
            const funcId = `${schemaId}-func-${index}`;
            const statusIcon = statusClass === 'verified' ? '✓' : '✗';
            const stubLabel = func.isStub ? ' [STUB]' : '';

            // Compact metadata line (collapsed)
            html += `<div class="function-item ${statusClass}">`;
            html += `<div class="function-metadata" onclick="toggleFunctionDDL('${funcId}')">`;
            html += `<span class="toggle-indicator" id="${funcId}-indicator">▶</span> `;
            html += `<strong>${func.functionName}</strong>${stubLabel} ${statusIcon}`;
            html += ` <span class="function-signature">${escapeHtml(func.signature || '')}</span>`;
            html += ` <span class="function-lines">(${func.lineCount || 0} lines)</span>`;

            // Add failure reason if present
            if (failureReasons && failureReasons[func.qualifiedName]) {
                html += `<br/><span class="failure-reason">${escapeHtml(failureReasons[func.qualifiedName])}</span>`;
            }

            html += `</div>`;

            // Expandable DDL section (hidden by default)
            html += `<div class="function-ddl" id="${funcId}" style="display: none;">`;
            html += `<button class="copy-ddl-btn" onclick="copyFunctionDDL('${funcId}-ddl')">Copy DDL</button>`;
            html += `<pre id="${funcId}-ddl" class="ddl-content">${escapeHtml(func.ddl || 'No DDL available')}</pre>`;
            html += `</div>`;
            html += `</div>`;
        });

        html += '</div>';
        html += '</div>';
    });

    html += '</div>';
    return html;
}

// Toggle schema group (reuse from view-service.js pattern)
function toggleSchemaGroup(schemaId) {
    const itemsList = document.getElementById(schemaId);
    const indicator = document.getElementById(`${schemaId}-indicator`);

    if (!itemsList) {
        console.warn(`Schema group not found: ${schemaId}`);
        return;
    }

    if (itemsList.style.display === 'none') {
        itemsList.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        itemsList.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
    }
}

// Toggle function DDL display
function toggleFunctionDDL(funcId) {
    const ddlDiv = document.getElementById(funcId);
    const indicator = document.getElementById(`${funcId}-indicator`);

    if (!ddlDiv) {
        console.warn(`Function DDL not found: ${funcId}`);
        return;
    }

    if (ddlDiv.style.display === 'none') {
        ddlDiv.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        ddlDiv.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
    }
}

// Copy function DDL to clipboard
function copyFunctionDDL(ddlId) {
    const ddlElement = document.getElementById(ddlId);
    if (!ddlElement) {
        console.warn(`DDL element not found: ${ddlId}`);
        return;
    }

    const ddl = ddlElement.textContent;
    navigator.clipboard.writeText(ddl).then(() => {
        console.log('DDL copied to clipboard');
        // Optional: Show temporary "Copied!" feedback
        const copyBtn = event.target;
        const originalText = copyBtn.textContent;
        copyBtn.textContent = 'Copied!';
        setTimeout(() => {
            copyBtn.textContent = originalText;
        }, 2000);
    }).catch(err => {
        console.error('Failed to copy DDL:', err);
        alert('Failed to copy DDL to clipboard');
    });
}

// Toggle function implementation verification results container
function toggleStandaloneFunctionImplementationVerificationResults(database) {
    const resultsDiv = document.getElementById(`${database}-standalone-function-implementation-verification-results`);
    const detailsDiv = document.getElementById(`${database}-standalone-function-implementation-verification-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// Utility function to escape HTML (prevent XSS)
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ===== END STANDALONE FUNCTION IMPLEMENTATION FUNCTIONS =====

// ==========================================
// Unified Function Verification (NEW)
// ==========================================

/**
 * Verify all PostgreSQL functions (unified verification job).
 * Replaces separate stub and implementation verification jobs.
 * Returns DDL for manual inspection instead of execution.
 */
async function verifyAllPostgresFunctions() {
    console.log('Starting unified PostgreSQL function verification job...');

    const button = document.querySelector('#postgres-standalone-function-implementation .verify-all-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL function verification...');
    updateProgress(0, 'Starting PostgreSQL function verification');

    try {
        const response = await fetch('/api/functions/postgres/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL function verification job started:', result.jobId);
            updateMessage('PostgreSQL function verification job started successfully');

            // Start polling for progress
            await pollUnifiedFunctionVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL function verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL function verification job:', error);
        updateMessage('Failed to start PostgreSQL function verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL function verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function pollUnifiedFunctionVerificationJobStatus(jobId) {
    console.log('Polling unified function verification job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Unified function verification job status:', result);

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
                console.log('Unified function verification job completed successfully');
                updateProgress(100, 'Function verification completed successfully');
                updateMessage('Function verification completed');

                // Get job results
                await getUnifiedFunctionVerificationJobResults(jobId);
            } else if (result.status === 'FAILED') {
                console.error('Unified function verification job failed:', result.error);
                updateProgress(0, 'Function verification failed');
                updateMessage('Function verification failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable button
            const button = document.querySelector('#postgres-standalone-function-implementation .verify-all-btn');
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollUnifiedFunctionVerificationJobStatus(jobId), 1000);
        }

    } catch (error) {
        console.error('Error polling unified function verification job status:', error);
        updateMessage('Error checking function verification job status: ' + error.message);
        updateProgress(0, 'Error checking function verification job status');

        // Re-enable button
        const button = document.querySelector('#postgres-standalone-function-implementation .verify-all-btn');
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function getUnifiedFunctionVerificationJobResults(jobId) {
    console.log('Getting unified function verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Unified function verification job results:', result);

            // The result contains the FunctionVerificationResult object
            const verificationResult = result.result;

            if (verificationResult) {
                const totalFunctions = result.totalFunctions || 0;
                const implementedCount = result.implementedCount || 0;
                const stubCount = result.stubCount || 0;
                const errorCount = result.errorCount || 0;

                updateMessage(`Function verification completed: ${totalFunctions} functions (${implementedCount} implemented, ${stubCount} stubs, ${errorCount} errors)`);
                updateComponentCount("postgres-standalone-function-implementation", implementedCount);

                // Display the detailed verification results
                displayUnifiedFunctionVerificationResults(verificationResult);
            } else {
                updateMessage('Function verification completed but returned no results');
            }
        } else {
            throw new Error(result.message || 'Failed to get function verification job results');
        }

    } catch (error) {
        console.error('Error getting unified function verification job results:', error);
        updateMessage('Error getting function verification results: ' + error.message);
    }
}

/**
 * Display unified function verification results with DDL inspection.
 * Groups functions by schema with collapsible DDL sections.
 * Includes function type (FUNCTION/PROCEDURE) and package member indicator.
 */
function displayUnifiedFunctionVerificationResults(verificationResult) {
    const resultsDiv = document.getElementById('postgres-unified-function-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-function-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Unified function verification results container not found');
        return;
    }

    let html = '';

    // Summary statistics
    const totalFunctions = verificationResult.totalFunctions || 0;
    const implementedCount = verificationResult.implementedCount || 0;
    const stubCount = verificationResult.stubCount || 0;
    const errorCount = verificationResult.errorCount || 0;

    html += '<div class="table-creation-summary">';
    html += '<div class="summary-stats">';
    html += `<span class="stat-item created">Implemented: ${implementedCount}</span>`;
    html += `<span class="stat-item skipped">Stubs: ${stubCount}</span>`;
    html += `<span class="stat-item errors">Errors: ${errorCount}</span>`;
    html += `<span class="stat-item">Total: ${totalFunctions}</span>`;
    html += '</div>';
    html += '</div>';

    // Generate schema-grouped functions with DDL
    const functionsBySchema = verificationResult.functionsBySchema || {};

    Object.keys(functionsBySchema).sort().forEach(schemaName => {
        const schemaFunctions = functionsBySchema[schemaName] || [];
        const schemaId = `function-verification-schema-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        // Count by status for this schema
        const schemaImplemented = schemaFunctions.filter(f => f.status === 'IMPLEMENTED').length;
        const schemaStubs = schemaFunctions.filter(f => f.status === 'STUB').length;
        const schemaErrors = schemaFunctions.filter(f => f.status === 'ERROR').length;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
        html += `<span class="toggle-indicator" id="${schemaId}-indicator">▶</span> `;
        html += `${schemaName} (${schemaFunctions.length} functions - `;
        html += `${schemaImplemented} implemented, ${schemaStubs} stubs, ${schemaErrors} errors)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${schemaId}" style="display: none;">`;

        // Sort functions by name within schema
        schemaFunctions.sort((a, b) => a.functionName.localeCompare(b.functionName));

        schemaFunctions.forEach(func => {
            const funcId = `function-ddl-${schemaName}-${func.functionName}`.replace(/[^a-z0-9]/gi, '_');
            const statusClass = func.status === 'IMPLEMENTED' ? 'created' :
                               func.status === 'STUB' ? 'skipped' : 'error';
            const statusBadge = func.status === 'IMPLEMENTED' ? '✓ IMPLEMENTED' :
                               func.status === 'STUB' ? '⚠ STUB' : '✗ ERROR';

            // Add type badge (FUNCTION/PROCEDURE) and package indicator
            const typeBadge = func.functionType || 'FUNCTION';
            const packageIndicator = func.isPackageMember ? '📦 Package' : 'Standalone';

            html += `<div class="table-item ${statusClass}">`;
            html += `<div class="view-header" onclick="toggleFunctionDdlLazy('${funcId}', '${schemaName}', '${func.functionName}')">`;
            html += `<span class="toggle-indicator" id="${funcId}-indicator">▶</span> `;
            html += `<strong>${func.functionName}</strong> `;
            html += `<span class="status-badge">[${typeBadge}]</span> `;
            html += `<span class="status-badge">[${packageIndicator}]</span> `;
            html += `<span class="status-badge">[${statusBadge}]</span>`;
            html += '</div>';

            // DDL section (collapsible, starts collapsed, will be loaded on demand)
            html += `<div class="view-ddl-section" id="${funcId}" style="display: none;" data-schema="${schemaName}" data-function-name="${func.functionName}" data-loaded="false">`;
            if (func.errorMessage) {
                // Show error immediately if function has an error
                html += `<div class="error-message">Error: ${escapeHtml(func.errorMessage)}</div>`;
            } else {
                // Placeholder for lazy-loaded content
                html += '<div class="loading-message">Loading...</div>';
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
 * Toggle function DDL visibility with lazy loading.
 */
async function toggleFunctionDdlLazy(funcId, schema, functionName) {
    const ddlSection = document.getElementById(funcId);
    const indicator = document.getElementById(`${funcId}-indicator`);

    if (!ddlSection) {
        console.warn(`Function DDL section not found: ${funcId}`);
        return;
    }

    // If already visible, just collapse it
    if (ddlSection.style.display === 'block') {
        ddlSection.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
        return;
    }

    // Show the section
    ddlSection.style.display = 'block';
    if (indicator) indicator.textContent = '▼';

    // Check if content is already loaded
    const isLoaded = ddlSection.getAttribute('data-loaded') === 'true';
    if (isLoaded) {
        // Already loaded, just show it
        return;
    }

    // Fetch DDL from backend
    try {
        const response = await fetch(`/api/functions/postgres/source/${encodeURIComponent(schema)}/${encodeURIComponent(functionName)}`);
        const result = await response.json();

        if (result.status === 'success' && result.postgresSql) {
            // Replace loading message with actual DDL
            ddlSection.innerHTML = '<pre class="sql-statement">' + escapeHtml(result.postgresSql) + '</pre>';
            ddlSection.setAttribute('data-loaded', 'true');
        } else {
            // Show error
            ddlSection.innerHTML = '<div class="error-message">Failed to load DDL: ' + escapeHtml(result.message || 'Unknown error') + '</div>';
        }
    } catch (error) {
        console.error('Error fetching function DDL:', error);
        ddlSection.innerHTML = '<div class="error-message">Failed to load DDL: ' + escapeHtml(error.message) + '</div>';
    }
}

/**
 * Toggle function DDL visibility (legacy function for backward compatibility).
 */
function toggleFunctionDdl(funcId) {
    const ddlSection = document.getElementById(funcId);
    const indicator = document.getElementById(`${funcId}-indicator`);

    if (!ddlSection) {
        console.warn(`Function DDL section not found: ${funcId}`);
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
 * Toggle unified function verification results visibility.
 */
function toggleUnifiedFunctionVerificationResults() {
    const resultsDiv = document.getElementById('postgres-unified-function-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-function-verification-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (toggleIndicator) toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        if (toggleIndicator) toggleIndicator.textContent = '▼';
    }
}

// ===== END UNIFIED FUNCTION VERIFICATION =====

// ===== END FUNCTION FUNCTIONS =====
