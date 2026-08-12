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
        const startResponse = await fetch('/api/objects/oracle/extract', {
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
        const startResponse = await fetch('/api/objects/postgres/extract', {
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
    // Arrives already grouped by schema; flatten so the shared grouping helper can own the
    // collapse behaviour.
    const objectTypes = [];
    Object.entries(objectTypesBySchema || {}).forEach(([schemaName, types]) => {
        (types || []).forEach(type => objectTypes.push(Object.assign({ schema: schemaName }, type)));
    });

    setDeferredList(`${database}-object-list`, `${database}-object-items`,
        () => renderSchemaGroups(objectTypes, renderObjectTypeRow,
            { label: 'object types', groupIdPrefix: `${database}-object-type-group` }),
        `Object Data Types (${formatCount(objectTypes.length)})`);
}

// One object type row. Its variable list is built on click, from the registry rather than
// from a captured closure - the rows are HTML strings now, not DOM nodes.
function renderObjectTypeRow(objectType) {
    const itemId = `object-type-${++objectTypeRowSeq}`;
    objectTypeDetails.set(itemId, objectType);

    const varCount = objectType.variables ? objectType.variables.length : 0;
    return `<div class="object-type-item" id="${itemId}" onclick="toggleObjectTypeDetails('${itemId}')">`
         + `${escapeHtml(objectType.name)} (${varCount} vars)</div>`;
}

// itemId -> object type, so the detail view survives the row being re-rendered.
const objectTypeDetails = new Map();
let objectTypeRowSeq = 0;

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

// Toggle object type details visibility
function toggleObjectTypeDetails(itemId) {
    const objectTypeItem = document.getElementById(itemId);
    const objectType = objectTypeDetails.get(itemId);

    if (!objectTypeItem || !objectType) {
        return;
    }

    const existing = objectTypeItem.querySelector('.object-type-details');
    if (existing) {
        existing.remove();
        return;
    }

    const details = document.createElement('div');
    details.className = 'object-type-details';

    if (objectType.variables && objectType.variables.length > 0) {
        details.innerHTML = '<div><strong>Variables:</strong></div>'
            + objectType.variables.map(variable =>
                `<div class="object-type-variable">`
                + `<span class="object-type-variable-name">${escapeHtml(variable.name)}</span>: `
                + `<span class="object-type-variable-type">${escapeHtml(variable.dataType)}</span>`
                + `</div>`).join('');
    } else {
        details.innerHTML = '<div style="font-style: italic; color: #999;">No variables defined</div>';
    }

    objectTypeItem.appendChild(details);
}

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
        const response = await fetch('/api/objects/postgres/create', {
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

    displayObjectTypeCreationResults(result, database);
}

function displayObjectTypeCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-objects", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-object-type-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'created object types',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Object Types',
                items: toSortedArray(summary.createdTypes, 'typeName'),
                cssClass: 'created', nameKey: 'typeName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Object Types (already exist)',
                items: toSortedArray(summary.skippedTypes, 'typeName'),
                cssClass: 'skipped', nameKey: 'typeName'
            },
            {
                title: 'Failed Object Types',
                items: toSortedArray(summary.errors, 'typeName'),
                cssClass: 'error', nameKey: 'typeName', showError: true
            }
        ], { jobId: result.jobId, label: 'object types' })
    });
}

function toggleObjectTypeCreationResults(database) {
    toggleResultsPanel(`${database}-object-type-creation`);
}
