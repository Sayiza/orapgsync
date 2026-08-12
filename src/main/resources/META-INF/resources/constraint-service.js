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
        const response = await fetch('/api/constraints/oracle/source-state', {
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
            button.innerHTML = '⟳';
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
        const response = await fetch('/api/constraints/postgres/verify', {
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
            button.innerHTML = '⟳';
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
        const response = await fetch('/api/constraints/postgres/create', {
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
                        button.innerHTML = '⟳';
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
                        button.innerHTML = '⟳';
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
        updateComponentCount(`${database}-constraints`, '-', 'error');
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
    const constraints = result.result || [];

    // Constraints group by kind rather than by schema - which kind a constraint is says more
    // about it than which schema it lives in.
    const typeNames = {
        'P': 'Primary Keys',
        'U': 'Unique Constraints',
        'R': 'Foreign Keys',
        'C': 'Check Constraints'
    };
    const grouped = constraints.map(c => Object.assign({}, c, {
        constraintGroup: typeNames[c.constraintType || 'C'] || 'Other Constraints'
    }));

    setDeferredList(`${database}-constraint-list`, `${database}-constraint-items`,
        () => renderSchemaGroups(grouped,
            c => `<div class="table-item">${escapeHtml(c.constraintName)} (${escapeHtml(c.schema + '.' + c.tableName)})</div>`,
            {
                schemaKey: 'constraintGroup',
                label: 'constraints',
                groupIdPrefix: `${database}-constraint-group`
            }),
        `Constraints (${formatCount(constraints.length)})`);
}

function toggleConstraintList(database) {
    toggleDeferredList(`${database}-constraint-list`);
}

function displayConstraintCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-constraints", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-constraint-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'created constraints',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Constraints',
                items: toSortedArray(summary.createdConstraints, 'tableName', 'constraintName'),
                renderItem: c => `<div class="table-item created">${describeConstraint(c)} \u2713</div>`
            },
            {
                title: 'Skipped Constraints (already exist)',
                items: toSortedArray(summary.skippedConstraints, 'tableName', 'constraintName'),
                renderItem: c => `<div class="table-item skipped">${describeConstraint(c)} (${escapeHtml(c.reason || 'already exists')})</div>`
            },
            {
                title: 'Failed Constraints',
                items: toSortedArray(summary.errors, 'tableName', 'constraintName'),
                renderItem: c => `<div class="table-item error">${describeConstraint(c)}: ${escapeHtml(c.errorMessage || '')}`
                                 + renderDeferredCode(c.sqlStatement) + `</div>`
            }
        ], { jobId: result.jobId, label: 'constraints' })
    });
}

// "PK_EMP (P) on HR.EMP" - the type code matters because constraint names repeat across tables.
function describeConstraint(constraint) {
    return `<strong>${escapeHtml(constraint.constraintName)}</strong> `
         + `(${escapeHtml(constraint.constraintType)}) on ${escapeHtml(constraint.tableName)}`;
}

function toggleConstraintCreationResults() {
    toggleResultsPanel('postgres-constraint-creation');
}

// ===== END CONSTRAINT FUNCTIONS =====
