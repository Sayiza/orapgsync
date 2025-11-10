/**
 * Type Method Service Module
 *
 * This module handles all type method-related operations for the Oracle-to-PostgreSQL migration tool.
 * It provides functionality for:
 * - Extracting type method metadata from Oracle databases
 * - Creating type method stubs in PostgreSQL
 * - Polling job status for extraction and creation operations
 * - Displaying type method lists and creation results in the UI
 * - Managing UI interactions for type method-related components
 *
 * Functions included:
 * - extractOracleTypeMethods(): Initiates Oracle type method extraction job
 * - createPostgresTypeMethodStubs(): Initiates PostgreSQL type method stub creation job
 * - pollTypeMethodJobStatus(): Monitors type method extraction job progress
 * - pollTypeMethodStubCreationJobStatus(): Monitors type method stub creation job progress
 * - getTypeMethodJobResults(): Retrieves and displays extraction results
 * - getTypeMethodStubCreationResults(): Retrieves and displays creation results
 * - populateTypeMethodList(): Populates UI with extracted type methods grouped by schema
 * - toggleTypeMethodSchemaGroup(): Toggles visibility of type method groups in UI
 * - displayTypeMethodStubCreationResults(): Displays detailed creation results
 * - toggleTypeMethodList(): Toggles visibility of type method list panels
 * - toggleTypeMethodCreationResults(): Toggles visibility of creation results panels
 */

// ===== TYPE METHOD FUNCTIONS =====

async function extractOracleTypeMethods() {
    console.log('Starting Oracle type method extraction job...');

    const button = document.querySelector('#oracle-type-methods .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle type method extraction...');
    updateProgress(0, 'Starting Oracle type method extraction');

    try {
        const response = await fetch('/api/type-methods/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle type method extraction job started:', result.jobId);
            updateMessage('Oracle type method extraction job started successfully');

            // Start polling for progress
            await pollTypeMethodJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle type method extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle type method extraction job:', error);
        updateMessage('Failed to start Oracle type method extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle type method extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function extractPostgresTypeMethods() {
    console.log('Starting PostgreSQL type method extraction job...');

    const button = document.querySelector('#postgres-type-methods .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL type method extraction...');
    updateProgress(0, 'Starting PostgreSQL type method extraction');

    try {
        const response = await fetch('/api/type-methods/postgres/stubs/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL type method extraction job started:', result.jobId);
            updateMessage('PostgreSQL type method extraction job started successfully');

            // Start polling for progress
            await pollTypeMethodJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL type method extraction job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL type method extraction job:', error);
        updateMessage('Failed to start PostgreSQL type method extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL type method extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function createPostgresTypeMethodStubs() {
    console.log('Starting PostgreSQL type method stub creation job...');

    updateComponentCount("postgres-type-methods", "-");

    const button = document.querySelector('#postgres-type-methods .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL type method stub creation...');
    updateProgress(0, 'Starting PostgreSQL type method stub creation');

    try {
        const response = await fetch('/api/type-methods/postgres/stubs/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL type method stub creation job started:', result.jobId);
            updateMessage('PostgreSQL type method stub creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollTypeMethodStubCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL type method stub creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL type method stub creation job:', error);
        updateMessage('Failed to start PostgreSQL type method stub creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL type method stub creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Type Method Stubs';
        }
    }
}

async function pollTypeMethodJobStatus(jobId, database) {
    console.log(`Polling type method job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Type method job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Type method extraction completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} type method extraction completed`);

                    // Get job results and update the UI
                    await getTypeMethodJobResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-type-methods .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} type method extraction failed`);
                    updateMessage(`${database.toUpperCase()} type method extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-type-methods .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    reject(new Error(status.error || 'Type method extraction failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling type method job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollTypeMethodStubCreationJobStatus(jobId, database) {
    console.log(`Polling type method stub creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Type method stub creation job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Type method stub creation completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} type method stub creation completed`);

                    // Get job results and display
                    await getTypeMethodStubCreationResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-type-methods .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Type Method Stubs';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} type method stub creation failed`);
                    updateMessage(`${database.toUpperCase()} type method stub creation failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-type-methods .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Type Method Stubs';
                    }

                    reject(new Error(status.error || 'Type method stub creation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling type method stub creation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getTypeMethodJobResults(jobId, database) {
    console.log('Getting type method job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Type method job results:', result);

            // Update badge count
            const typeMethodCount = result.typeMethodCount || 0;
            updateComponentCount(`${database}-type-methods`, typeMethodCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            if (result.summary && result.summary.message) {
                updateMessage(`${databaseName}: ${result.summary.message}`);
            } else {
                updateMessage(`Extracted ${typeMethodCount} ${databaseName} type methods`);
            }

            // Populate type method list UI
            populateTypeMethodList(result, database);

            // Show type method list if there are type methods
            if (typeMethodCount > 0) {
                document.getElementById(`${database}-type-method-list`).style.display = 'block';
            }

        } else {
            throw new Error(result.message || 'Failed to get type method job results');
        }

    } catch (error) {
        console.error('Error getting type method job results:', error);
        updateMessage('Error getting type method results: ' + error.message);
        updateComponentCount(`${database}-type-methods`, '-', 'error');
    }
}

async function getTypeMethodStubCreationResults(jobId, database) {
    console.log('Getting type method stub creation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Type method stub creation job results:', result);

            // Display the creation results
            displayTypeMethodStubCreationResults(result, database);

            // Update badge count
            const typeMethodCount = result.createdCount || 0;
            updateComponentCount(`${database}-type-methods`, typeMethodCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            updateMessage(`${databaseName}: Created ${result.createdCount} type method stubs, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get type method stub creation results');
        }

    } catch (error) {
        console.error('Error getting type method stub creation results:', error);
        updateMessage('Error getting type method stub creation results: ' + error.message);
    }
}

function populateTypeMethodList(result, database) {
    const typeMethodItemsElement = document.getElementById(`${database}-type-method-items`);

    if (!typeMethodItemsElement) {
        console.warn('Type method items element not found');
        return;
    }

    // Clear existing items
    typeMethodItemsElement.innerHTML = '';

    // Get type methods from result
    const typeMethods = result.result || [];

    if (typeMethods && typeMethods.length > 0) {
        // Group type methods by schema
        const schemaGroups = {};
        typeMethods.forEach(method => {
            const schema = method.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(method);
        });

        // Create schema groups
        Object.entries(schemaGroups).forEach(([schemaName, schemaMethods]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaMethods.length} type methods)`;
            schemaHeader.onclick = () => toggleTypeMethodSchemaGroup(database, schemaName);

            const typeMethodItems = document.createElement('div');
            typeMethodItems.className = 'table-items-list';
            typeMethodItems.id = `${database}-${schemaName}-type-methods`;

            // Add individual type method entries for this schema
            schemaMethods.forEach(method => {
                const methodItem = document.createElement('div');
                methodItem.className = 'table-item';

                // Display method name with type name
                const displayName = `${method.typeName}.${method.methodName}`;

                // Add method type indicators
                const memberIndicator = method.instantiable === 'YES' ? 'M' : 'S'; // Member or Static
                const typeIndicator = method.methodType === 'FUNCTION' ? '𝑓' : 'ₚ';
                methodItem.innerHTML = `<span class="type-method-indicator">${memberIndicator}${typeIndicator}</span> ${displayName}`;

                typeMethodItems.appendChild(methodItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(typeMethodItems);
            typeMethodItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noMethodsItem = document.createElement('div');
        noMethodsItem.className = 'table-item';
        noMethodsItem.textContent = 'No type methods found';
        noMethodsItem.style.fontStyle = 'italic';
        noMethodsItem.style.color = '#999';
        typeMethodItemsElement.appendChild(noMethodsItem);
    }
}

function toggleTypeMethodSchemaGroup(database, schemaName) {
    const typeMethodItems = document.getElementById(`${database}-${schemaName}-type-methods`);
    const header = typeMethodItems.previousElementSibling;
    const indicator = header.querySelector('.toggle-indicator');

    if (typeMethodItems.style.display === 'none') {
        typeMethodItems.style.display = 'block';
        indicator.textContent = '▼';
    } else {
        typeMethodItems.style.display = 'none';
        indicator.textContent = '▶';
    }
}

function displayTypeMethodStubCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-type-method-creation-results`);
    const detailsDiv = document.getElementById(`${database}-type-method-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Type method stub creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-type-methods", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created type methods
        if (summary.createdCount > 0 && summary.createdMethods) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created Type Method Stubs:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdMethods).forEach(method => {
                html += `<div class="table-item created">${method.methodName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped type methods
        if (summary.skippedCount > 0 && summary.skippedMethods) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Type Methods (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedMethods).forEach(method => {
                html += `<div class="table-item skipped">${method.methodName} (already exists)</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Type Methods:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.methodName}</strong>: ${error.error}`;
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

function toggleTypeMethodList(database) {
    const typeMethodItems = document.getElementById(`${database}-type-method-items`);
    const header = document.querySelector(`#${database}-type-method-list .table-list-header`);

    if (!typeMethodItems || !header) {
        console.warn(`Type method list elements not found for database: ${database}`);
        return;
    }

    if (typeMethodItems.style.display === 'none') {
        typeMethodItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        typeMethodItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

function toggleTypeMethodCreationResults() {
    const resultsDiv = document.getElementById('postgres-type-method-creation-results');
    const detailsDiv = document.getElementById('postgres-type-method-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== TYPE METHOD IMPLEMENTATION FUNCTIONS =====

async function createPostgresTypeMethodImplementation() {
    console.log('Starting PostgreSQL type method implementation job...');

    updateComponentCount("postgres-type-method-implementation", "-");

    const button = document.querySelector('#postgres-type-method-implementation .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL type method implementation...');
    updateProgress(0, 'Starting PostgreSQL type method implementation');

    try {
        const response = await fetch('/api/type-methods/postgres/implementation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL type method implementation job started:', result.jobId);
            updateMessage('PostgreSQL type method implementation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollTypeMethodImplementationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL type method implementation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL type method implementation job:', error);
        updateMessage('Failed to start PostgreSQL type method implementation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL type method implementation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Type Methods';
        }
    }
}

async function verifyAllPostgresTypeMethods() {
    console.log('Starting PostgreSQL type method verification (unified - stubs + implementations)...');

    const button = document.querySelector('#postgres-type-method-implementation .verify-all-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL type method verification...');
    updateProgress(0, 'Starting PostgreSQL type method verification');

    try {
        const response = await fetch('/api/type-methods/postgres/stubs/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL type method verification job started:', result.jobId);
            updateMessage('PostgreSQL type method verification job started successfully');
            // Start polling for progress
            await pollTypeMethodVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL type method verification job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL type method verification job:', error);
        updateMessage('Failed to start PostgreSQL type method verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL type method verification');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function pollTypeMethodImplementationJobStatus(jobId) {
    console.log('Polling type method implementation job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log('Type method implementation job status:', status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log('Type method implementation completed:', status);
                    updateProgress(100, 'PostgreSQL type method implementation completed');

                    // Get job results and display
                    await getTypeMethodImplementationResults(jobId);

                    // Re-enable button
                    const button = document.querySelector('#postgres-type-method-implementation .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Type Methods';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, 'PostgreSQL type method implementation failed');
                    updateMessage('PostgreSQL type method implementation failed: ' + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector('#postgres-type-method-implementation .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Type Methods';
                    }

                    reject(new Error(status.error || 'Type method implementation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling type method implementation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollTypeMethodVerificationJobStatus(jobId) {
    console.log('Polling type method verification job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log('Type method verification job status:', status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log('Type method verification completed:', status);
                    updateProgress(100, 'PostgreSQL type method verification completed');

                    // Get job results and display
                    await getTypeMethodVerificationResults(jobId);

                    // Re-enable button
                    const button = document.querySelector('#postgres-type-method-implementation .verify-all-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, 'PostgreSQL type method verification failed');
                    updateMessage('PostgreSQL type method verification failed: ' + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector('#postgres-type-method-implementation .verify-all-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    reject(new Error(status.error || 'Type method verification failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling type method verification job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getTypeMethodImplementationResults(jobId) {
    console.log('Getting type method implementation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Type method implementation job results:', result);

            // Display the implementation results
            displayTypeMethodImplementationResults(result);

            // Update badge count
            const implementedCount = result.implementedCount || 0;
            updateComponentCount("postgres-type-method-implementation", implementedCount);

            // Show success message
            updateMessage(`PostgreSQL: Implemented ${result.implementedCount} type methods, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get type method implementation results');
        }

    } catch (error) {
        console.error('Error getting type method implementation results:', error);
        updateMessage('Error getting type method implementation results: ' + error.message);
    }
}

async function getTypeMethodVerificationResults(jobId) {
    console.log('Getting type method verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Type method verification job results:', result);

            // Display the verification results
            displayTypeMethodVerificationResults(result);

            // Update badge count
            const verifiedCount = result.typeMethodCount || 0;
            updateComponentCount("postgres-type-method-implementation", verifiedCount);

            // Show success message
            updateMessage(`PostgreSQL: Verified ${verifiedCount} type methods`);

        } else {
            throw new Error(result.message || 'Failed to get type method verification results');
        }

    } catch (error) {
        console.error('Error getting type method verification results:', error);
        updateMessage('Error getting type method verification results: ' + error.message);
    }
}

function displayTypeMethodImplementationResults(result) {
    const resultsDiv = document.getElementById('postgres-type-method-implementation-results');
    const detailsDiv = document.getElementById('postgres-type-method-implementation-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Type method implementation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Implemented: ${summary.implementedCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show implemented type methods
        if (summary.implementedCount > 0 && summary.implementedMethods) {
            html += '<div class="created-tables-section">';
            html += '<h4>Implemented Type Methods:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.implementedMethods).forEach(method => {
                html += `<div class="table-item created">${method.methodName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped type methods
        if (summary.skippedCount > 0 && summary.skippedMethods) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Type Methods:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedMethods).forEach(method => {
                html += `<div class="table-item skipped">${method.methodName} (skipped)</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Type Methods:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.typeMethodName}</strong>: ${error.error}`;
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

function displayTypeMethodVerificationResults(result) {
    const resultsDiv = document.getElementById('postgres-unified-type-method-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-type-method-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Type method verification results container not found');
        return;
    }

    let html = '';

    // Get type methods from result
    const typeMethods = result.result || [];
    const typeMethodCount = typeMethods.length;

    html += '<div class="table-creation-summary">';
    html += `<div class="summary-stats">`;
    html += `<span class="stat-item">Total Type Methods: ${typeMethodCount}</span>`;
    html += `</div>`;
    html += '</div>';

    if (typeMethodCount > 0) {
        // Group by schema
        const schemaGroups = {};
        typeMethods.forEach(method => {
            const schema = method.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(method);
        });

        html += '<div class="created-tables-section">';
        html += '<h4>Verified Type Methods:</h4>';

        Object.entries(schemaGroups).forEach(([schemaName, schemaMethods]) => {
            html += `<div class="table-schema-group">`;
            html += `<div class="table-schema-header"><strong>${schemaName}</strong> (${schemaMethods.length} type methods)</div>`;
            html += '<div class="table-items">';
            schemaMethods.forEach(method => {
                const displayName = `${method.typeName}.${method.methodName}`;
                const memberIndicator = method.instantiable === 'YES' ? 'M' : 'S';
                const typeIndicator = method.methodType === 'FUNCTION' ? '𝑓' : 'ₚ';
                html += `<div class="table-item created"><span class="type-method-indicator">${memberIndicator}${typeIndicator}</span> ${displayName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        });

        html += '</div>';
    } else {
        html += '<div class="table-items"><div class="table-item">No type methods found in PostgreSQL</div></div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleTypeMethodImplementationResults(database) {
    const resultsDiv = document.getElementById(`${database}-type-method-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-type-method-implementation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.warn('Type method implementation results elements not found for database:', database);
        return;
    }

    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (toggleIndicator) toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        if (toggleIndicator) toggleIndicator.textContent = '▼';
    }
}

function toggleUnifiedTypeMethodVerificationResults() {
    const resultsDiv = document.getElementById('postgres-unified-type-method-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-type-method-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.warn('Unified type method verification results elements not found');
        return;
    }

    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (toggleIndicator) toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        if (toggleIndicator) toggleIndicator.textContent = '▼';
    }
}

// ===== END TYPE METHOD FUNCTIONS =====
