/**
 * Constraint Service Module
 *
 * This module handles all constraint-related operations for the Oracle-to-PostgreSQL migration tool.
 * It provides functionality for:
 * - Extracting constraint metadata from Oracle table state
 * - Verifying constraint metadata from PostgreSQL database
 * - Creating constraints in PostgreSQL based on Oracle constraint definitions
 * - Polling job status for extraction, verification, and creation operations
 * - Displaying constraint lists and creation results in the UI
 * - Managing UI interactions for constraint-related components
 *
 * Functions included:
 * - extractOracleConstraints(): Initiates Oracle constraint extraction job (from state)
 * - verifyPostgresConstraints(): Initiates PostgreSQL constraint verification job (from database)
 * - createPostgresConstraints(): Initiates PostgreSQL constraint creation job
 * - pollConstraintJobStatus(): Monitors constraint extraction/verification job progress
 * - pollConstraintCreationJobStatus(): Monitors constraint creation job progress
 * - getConstraintJobResults(): Retrieves and displays extraction/verification results
 * - getConstraintCreationResults(): Retrieves and displays creation results
 * - populateConstraintList(): Populates UI with extracted constraints grouped by type and table
 * - toggleConstraintTypeGroup(): Toggles visibility of constraint type groups in UI
 * - displayConstraintCreationResults(): Displays detailed creation results
 * - toggleConstraintList(): Toggles visibility of constraint list panels
 * - toggleConstraintCreationResults(): Toggles visibility of creation results panels
 */

// ===== CONSTRAINT FUNCTIONS =====

async function extractOracleConstraints() {
    console.log('Starting Oracle constraint extraction job...');

    const button = document.querySelector('#oracle-constraints .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle constraint extraction...');
    updateProgress(0, 'Starting Oracle constraint extraction');

    try {
        const response = await fetch('/api/jobs/oracle/constraint-source-state/read', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle constraint extraction job started:', result.jobId);
            updateMessage('Oracle constraint extraction job started successfully');

            // Start polling for progress
            await pollConstraintJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle constraint extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle constraint extraction job:', error);
        updateMessage('Failed to start Oracle constraint extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle constraint extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

async function verifyPostgresConstraints() {
    console.log('Starting PostgreSQL constraint verification job...');

    const button = document.querySelector('#postgres-constraints .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL constraint verification...');
    updateProgress(0, 'Starting PostgreSQL constraint verification');

    try {
        const response = await fetch('/api/jobs/postgres/constraint-verification/read', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL constraint verification job started:', result.jobId);
            updateMessage('PostgreSQL constraint verification job started successfully');

            // Start polling for progress
            await pollConstraintJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL constraint verification job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL constraint verification job:', error);
        updateMessage('Failed to start PostgreSQL constraint verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL constraint verification');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

async function createPostgresConstraints() {
    console.log('Starting PostgreSQL constraint creation job...');

    updateComponentCount("postgres-constraints", "-");

    const button = document.querySelector('#postgres-constraints .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL constraint creation...');
    updateProgress(0, 'Starting PostgreSQL constraint creation');

    try {
        const response = await fetch('/api/jobs/postgres/constraint-creation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL constraint creation job started:', result.jobId);
            updateMessage('PostgreSQL constraint creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollConstraintCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL constraint creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL constraint creation job:', error);
        updateMessage('Failed to start PostgreSQL constraint creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL constraint creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Constraints';
        }
    }
}

async function pollConstraintJobStatus(jobId, database) {
    console.log(`Polling constraint job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Constraint job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Constraint extraction/verification completed for ${database}:`, status);
                    const operationType = database === 'oracle' ? 'extraction' : 'verification';
                    updateProgress(100, `${database.toUpperCase()} constraint ${operationType} completed`);

                    // Get job results and update the UI
                    await getConstraintJobResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-constraints .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⚙';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    const operationType = database === 'oracle' ? 'extraction' : 'verification';
                    updateProgress(-1, `${database.toUpperCase()} constraint ${operationType} failed`);
                    updateMessage(`${database.toUpperCase()} constraint ${operationType} failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-constraints .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⚙';
                    }

                    reject(new Error(status.error || 'Constraint extraction/verification failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling constraint job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollConstraintCreationJobStatus(jobId, database) {
    console.log(`Polling constraint creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Constraint creation job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Constraint creation completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} constraint creation completed`);

                    // Get job results and display
                    await getConstraintCreationResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-constraints .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Constraints';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} constraint creation failed`);
                    updateMessage(`${database.toUpperCase()} constraint creation failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-constraints .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Constraints';
                    }

                    reject(new Error(status.error || 'Constraint creation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling constraint creation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getConstraintJobResults(jobId, database) {
    console.log('Getting constraint job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Constraint job results:', result);

            // Update badge count
            const constraints = result.result || [];
            const constraintCount = constraints.length || 0;
            updateComponentCount(`${database}-constraints`, constraintCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            const operationType = database === 'oracle' ? 'extracted' : 'verified';
            if (result.summary && result.summary.message) {
                updateMessage(`${databaseName}: ${result.summary.message}`);
            } else {
                updateMessage(`${operationType} ${constraintCount} ${databaseName} constraints`);
            }

            // Populate constraint list UI
            populateConstraintList(result, database);

            // Show constraint list if there are constraints
            if (constraintCount > 0) {
                document.getElementById(`${database}-constraint-list`).style.display = 'block';
            }

        } else {
            throw new Error(result.message || 'Failed to get constraint job results');
        }

    } catch (error) {
        console.error('Error getting constraint job results:', error);
        updateMessage('Error getting constraint results: ' + error.message);
        updateComponentCount(`${database}-constraints`, '?', 'error');
    }
}

async function getConstraintCreationResults(jobId, database) {
    console.log('Getting constraint creation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Constraint creation job results:', result);

            // Display the creation results
            displayConstraintCreationResults(result, database);

            // Update badge count
            const constraintCount = result.createdCount || 0;
            updateComponentCount(`${database}-constraints`, constraintCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            updateMessage(`${databaseName}: Created ${result.createdCount} constraints, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get constraint creation results');
        }

    } catch (error) {
        console.error('Error getting constraint creation results:', error);
        updateMessage('Error getting constraint creation results: ' + error.message);
    }
}

function populateConstraintList(result, database) {
    const constraintItemsElement = document.getElementById(`${database}-constraint-items`);

    if (!constraintItemsElement) {
        console.warn('Constraint items element not found');
        return;
    }

    // Clear existing items
    constraintItemsElement.innerHTML = '';

    // Get constraints from result
    const constraints = result.result || [];

    if (constraints && constraints.length > 0) {
        // Group constraints by type
        const typeGroups = {
            'P': { name: 'Primary Keys', constraints: [] },
            'U': { name: 'Unique Constraints', constraints: [] },
            'R': { name: 'Foreign Keys', constraints: [] },
            'C': { name: 'Check Constraints', constraints: [] }
        };

        constraints.forEach(constraint => {
            const type = constraint.constraintType || 'C';
            if (typeGroups[type]) {
                typeGroups[type].constraints.push(constraint);
            }
        });

        // Create type groups
        Object.entries(typeGroups).forEach(([typeCode, typeData]) => {
            if (typeData.constraints.length > 0) {
                const typeGroup = document.createElement('div');
                typeGroup.className = 'table-schema-group';

                const typeHeader = document.createElement('div');
                typeHeader.className = 'table-schema-header';
                typeHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${typeData.name} (${typeData.constraints.length})`;
                typeHeader.onclick = () => toggleConstraintTypeGroup(database, typeCode);

                const constraintItems = document.createElement('div');
                constraintItems.className = 'table-items-list';
                constraintItems.id = `${database}-${typeCode}-constraints`;

                // Add individual constraint entries for this type
                typeData.constraints.forEach(constraint => {
                    const constraintItem = document.createElement('div');
                    constraintItem.className = 'table-item';
                    const tableName = constraint.schema + '.' + constraint.tableName;
                    constraintItem.textContent = `${constraint.constraintName} (${tableName})`;
                    constraintItems.appendChild(constraintItem);
                });

                typeGroup.appendChild(typeHeader);
                typeGroup.appendChild(constraintItems);
                constraintItemsElement.appendChild(typeGroup);
            }
        });
    } else {
        const noConstraintsItem = document.createElement('div');
        noConstraintsItem.className = 'table-item';
        noConstraintsItem.textContent = 'No constraints found';
        noConstraintsItem.style.fontStyle = 'italic';
        noConstraintsItem.style.color = '#999';
        constraintItemsElement.appendChild(noConstraintsItem);
    }
}

function toggleConstraintTypeGroup(database, typeCode) {
    const constraintItems = document.getElementById(`${database}-${typeCode}-constraints`);
    const header = constraintItems.previousElementSibling;
    const indicator = header.querySelector('.toggle-indicator');

    if (constraintItems.style.display === 'none') {
        constraintItems.style.display = 'block';
        indicator.textContent = '▼';
    } else {
        constraintItems.style.display = 'none';
        indicator.textContent = '▶';
    }
}

function displayConstraintCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-constraint-creation-results`);
    const detailsDiv = document.getElementById(`${database}-constraint-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Constraint creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-constraints", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created constraints - convert to Array
        if (summary.createdCount > 0 && summary.createdConstraints) {
            const createdConstraints = Array.isArray(summary.createdConstraints)
                ? summary.createdConstraints
                : Object.values(summary.createdConstraints);

            html += '<div class="created-tables-section">';
            html += '<h4>Created Constraints:</h4>';
            html += '<div class="table-items">';
            createdConstraints.forEach(constraint => {
                html += `<div class="table-item created">${constraint.constraintName} (${constraint.constraintType}) on ${constraint.tableName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped constraints - convert to Array
        if (summary.skippedCount > 0 && summary.skippedConstraints) {
            const skippedConstraints = Array.isArray(summary.skippedConstraints)
                ? summary.skippedConstraints
                : Object.values(summary.skippedConstraints);

            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Constraints (already exist):</h4>';
            html += '<div class="table-items">';
            skippedConstraints.forEach(constraint => {
                const reason = constraint.reason || 'already exists';
                html += `<div class="table-item skipped">${constraint.constraintName} (${constraint.constraintType}) on ${constraint.tableName} (${reason})</div>`;
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
            html += '<h4>Failed Constraints:</h4>';
            html += '<div class="table-items">';
            errors.forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.constraintName}</strong> (${error.constraintType}) on ${error.tableName}: ${error.errorMessage}`;
                if (error.sqlStatement) {
                    html += `<div class="sql-statement"><pre>${error.sqlStatement}</pre></div>`;
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

function toggleConstraintList(database) {
    const listDiv = document.getElementById(`${database}-constraint-list`);
    const toggleIndicator = listDiv.querySelector('.toggle-indicator');

    if (listDiv.style.display === 'none' || !listDiv.style.display) {
        listDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        listDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

function toggleConstraintCreationResults() {
    const resultsDiv = document.getElementById('postgres-constraint-creation-results');
    const detailsDiv = document.getElementById('postgres-constraint-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== END CONSTRAINT FUNCTIONS =====
