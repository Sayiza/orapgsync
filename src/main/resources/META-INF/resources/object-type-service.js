/**
 * Object Type Service Module
 *
 * This module handles object type extraction, creation, and display operations
 * for both Oracle and PostgreSQL databases. It provides functionality for:
 * - Extracting object type metadata from Oracle and PostgreSQL
 * - Creating PostgreSQL composite types from Oracle object types
 * - Displaying object type information in the UI with schema grouping
 * - Managing object type creation job status and results
 */

// Object Type Extraction Functions

// Extract Oracle object data types using job-based approach
async function loadOracleObjectTypes() {
    console.log('Starting Oracle object data type extraction job...');
    updateMessage('Starting Oracle object data type extraction...');

    updateComponentCount('oracle-objects', '-');

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/objects/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('Oracle object data type extraction job started:', startResult.jobId);
            updateMessage('Oracle object data type extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#oracle-objects .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'oracle', 'objects');

        } else {
            updateComponentCount('oracle-objects', '!', 'error');
            updateMessage('Failed to start Oracle object data type extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting Oracle object data type extraction:', error);
        updateComponentCount('oracle-objects', '!', 'error');
        updateMessage('Error starting Oracle object data type extraction: ' + error.message);
    }
}

// Extract PostgreSQL object data types using job-based approach
async function loadPostgresObjectTypes() {
    console.log('Starting PostgreSQL object data type extraction job...');
    updateMessage('Starting PostgreSQL object data type extraction...');

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/objects/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('PostgreSQL object data type extraction job started:', startResult.jobId);
            updateMessage('PostgreSQL object data type extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#postgres-objects .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'postgres', 'objects');

        } else {
            updateComponentCount('postgres-objects', '!', 'error');
            updateMessage('Failed to start PostgreSQL object data type extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting PostgreSQL object data type extraction:', error);
        updateComponentCount('postgres-objects', '!', 'error');
        updateMessage('Error starting PostgreSQL object data type extraction: ' + error.message);
    }
}

// Get object data type job results and display them
async function getObjectDataTypeJobResults(jobId, database) {
    console.log('Getting object data type job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Object data type job results:', result);

            // For object data types, we need to process the result differently
            // The job returns a list of ObjectDataTypeMetaData, we need to group by schema
            const objectDataTypes = result.result || [];
            const objectDataTypesBySchema = {};

            objectDataTypes.forEach(objectType => {
                if (!objectDataTypesBySchema[objectType.schema]) {
                    objectDataTypesBySchema[objectType.schema] = [];
                }
                objectDataTypesBySchema[objectType.schema].push(objectType);
            });

            // Update component count
            updateComponentCount(`${database}-objects`, objectDataTypes.length);

            // Populate the object type list
            populateObjectTypeList(database, objectDataTypesBySchema);

            // Show the object list if we have object data types
            if (objectDataTypes.length > 0) {
                document.getElementById(`${database}-object-list`).style.display = 'block';
            }

            updateMessage(`Loaded ${objectDataTypes.length} ${database} object data types`);
        } else {
            throw new Error(result.message || 'Failed to get object data type job results');
        }

    } catch (error) {
        console.error('Error getting object data type job results:', error);
        updateComponentCount(`${database}-objects`, '!', 'error');
        updateMessage(`Error getting object data type results: ${error.message}`);
    }
}

// Object Type Display Functions

// Populate object type list with object types grouped by schema
function populateObjectTypeList(database, objectTypesBySchema) {
    const objectItemsElement = document.getElementById(`${database}-object-items`);

    if (!objectItemsElement) {
        console.warn(`Object items element not found for database: ${database}`);
        return;
    }

    // Clear existing items
    objectItemsElement.innerHTML = '';

    if (objectTypesBySchema && Object.keys(objectTypesBySchema).length > 0) {
        Object.entries(objectTypesBySchema).forEach(([schemaName, objectTypes]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'object-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'object-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${objectTypes.length})`;
            schemaHeader.onclick = () => toggleObjectSchemaGroup(database, schemaName);

            const objectTypeItems = document.createElement('div');
            objectTypeItems.className = 'object-type-items';
            objectTypeItems.id = `${database}-${schemaName}-object-types`;

            if (objectTypes && objectTypes.length > 0) {
                objectTypes.forEach(objectType => {
                    const objectTypeItem = document.createElement('div');
                    objectTypeItem.className = 'object-type-item';
                    objectTypeItem.innerHTML = `${objectType.name} (${objectType.variables?.length || 0} vars)`;

                    // Add click handler to show/hide details
                    objectTypeItem.onclick = () => toggleObjectTypeDetails(objectTypeItem, objectType);

                    objectTypeItems.appendChild(objectTypeItem);
                });
            } else {
                const noObjectTypesItem = document.createElement('div');
                noObjectTypesItem.className = 'object-type-item';
                noObjectTypesItem.textContent = 'No object types found';
                noObjectTypesItem.style.fontStyle = 'italic';
                noObjectTypesItem.style.color = '#999';
                objectTypeItems.appendChild(noObjectTypesItem);
            }

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(objectTypeItems);
            objectItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noObjectTypesGroup = document.createElement('div');
        noObjectTypesGroup.className = 'object-schema-group';
        noObjectTypesGroup.innerHTML = `
            <div class="object-type-item" style="font-style: italic; color: #999;">
                No object types found
            </div>
        `;
        objectItemsElement.appendChild(noObjectTypesGroup);
    }
}

// Toggle object list visibility
function toggleObjectList(database) {
    const objectItems = document.getElementById(`${database}-object-items`);
    const header = document.querySelector(`#${database}-object-list .object-list-header`);

    if (!objectItems || !header) {
        console.warn(`Object list elements not found for database: ${database}`);
        return;
    }

    if (objectItems.style.display === 'none') {
        objectItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        objectItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle object schema group visibility
function toggleObjectSchemaGroup(database, schemaName) {
    const objectTypeItems = document.getElementById(`${database}-${schemaName}-object-types`);
    const header = event.target;

    if (!objectTypeItems || !header) {
        console.warn(`Object schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (objectTypeItems.style.display === 'none') {
        objectTypeItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        objectTypeItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle object type details visibility
function toggleObjectTypeDetails(objectTypeItem, objectType) {
    let detailsElement = objectTypeItem.querySelector('.object-type-details');

    if (detailsElement) {
        // Toggle existing details
        detailsElement.style.display = detailsElement.style.display === 'none' ? 'block' : 'none';
    } else {
        // Create and show details
        detailsElement = document.createElement('div');
        detailsElement.className = 'object-type-details';

        if (objectType.variables && objectType.variables.length > 0) {
            detailsElement.innerHTML = `
                <div><strong>Variables:</strong></div>
                ${objectType.variables.map(variable => `
                    <div class="object-type-variable">
                        <span class="object-type-variable-name">${variable.name}</span>:
                        <span class="object-type-variable-type">${variable.dataType}</span>
                    </div>
                `).join('')}
            `;
        } else {
            detailsElement.innerHTML = '<div style="font-style: italic; color: #999;">No variables defined</div>';
        }

        objectTypeItem.appendChild(detailsElement);
        detailsElement.style.display = 'block';
    }
}

// Object Type Creation Functions

// Create PostgreSQL object types from Oracle object types
async function createPostgresObjectTypes() {
    console.log('Starting PostgreSQL object type creation job...');

    updateComponentCount("postgres-objects", "-");

    const button = document.querySelector('#postgres-objects .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }
    updateMessage('Starting PostgreSQL object type creation...');
    updateProgress(0, 'Starting PostgreSQL object type creation');

    try {
        const response = await fetch('/api/jobs/postgres/object-type/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL object type creation job started:', result.jobId);
            updateMessage('PostgreSQL object type creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollObjectTypeCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL object type creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL object type creation job:', error);
        updateMessage('Failed to start PostgreSQL object type creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL object type creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Types';
        }
    }
}

// Poll object type creation job status until completion
async function pollObjectTypeCreationJobStatus(jobId, database) {
    console.log(`Polling object type creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Object type creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Object type creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleObjectTypeCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-objects .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Types';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling object type creation job status:', error);
                updateMessage('Error checking object type creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-objects .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Types';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

// Handle object type creation job completion
function handleObjectTypeCreationJobComplete(result, database) {
    console.log(`Object type creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Object type creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Object type creation completed successfully: ${createdCount} types created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Object type creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Display object type creation results
    displayObjectTypeCreationResults(result, database);

    // Refresh PostgreSQL object types to show newly created ones ... no need
    //setTimeout(() => {
    //    loadPostgresObjectTypes();
    //}, 1000);
}

// Display object type creation results
function displayObjectTypeCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-object-type-creation-results`);
    const detailsDiv = document.getElementById(`${database}-object-type-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Object type creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-objects", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="object-type-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created object types
        if (summary.createdCount > 0) {
            html += '<div class="created-types-section">';
            html += '<h4>Created Object Types:</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.createdTypes).forEach(type => {
                html += `<div class="object-type-item created">${type.typeName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped object types
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-types-section">';
            html += '<h4>Skipped Object Types (already exist):</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.skippedTypes).forEach(type => {
                html += `<div class="object-type-item skipped">${type.typeName} (${type.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-types-section">';
            html += '<h4>Failed Object Types:</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="object-type-item error">`;
                html += `<strong>${error.typeName}</strong>: ${error.error}`;
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

// Toggle object type creation results visibility
function toggleObjectTypeCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-object-type-creation-results`);
    const detailsDiv = document.getElementById(`${database}-object-type-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}
