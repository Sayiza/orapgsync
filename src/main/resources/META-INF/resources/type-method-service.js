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
    const typeMethods = (result.summary && result.summary.typeMethods) || [];

    setDeferredList(`${database}-type-method-list`, `${database}-type-method-items`,
        () => renderSchemaGroups(typeMethods, method => {
            const displayName = `${method.typeName}.${method.methodName}`;
            const memberIndicator = method.instantiable === 'YES' ? 'M' : 'S';
            const typeIndicator = method.methodType === 'FUNCTION' ? '𝑓' : 'ₚ';
            return `<div class="table-item"><span class="type-method-indicator">${memberIndicator}${typeIndicator}</span> ${escapeHtml(displayName)}</div>`;
        }, { label: 'type methods', groupIdPrefix: `${database}-type-method-group` }),
        `Type Methods (${formatCount(typeMethods.length)})`);
}

function toggleTypeMethodList(database) {
    toggleDeferredList(`${database}-type-method-list`);
}

function displayTypeMethodStubCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-type-methods", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-type-method-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'type method stubs',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Type Method Stubs',
                items: toSortedArray(summary.createdMethods, 'schema', 'methodName'),
                cssClass: 'created', nameKey: 'methodName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Type Methods (already exist)',
                items: toSortedArray(summary.skippedMethods, 'schema', 'methodName'),
                cssClass: 'skipped', nameKey: 'methodName', suffix: ' (already exists)'
            },
            {
                title: 'Failed Type Methods',
                items: toSortedArray(summary.errors, 'typeMethodName'),
                cssClass: 'error', nameKey: 'typeMethodName', showError: true
            }
        ], { jobId: result.jobId, label: 'type methods' })
    });
}


function toggleTypeMethodCreationResults() {
    toggleResultsPanel('postgres-type-method-creation');
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
    const summary = result.summary;
    if (!summary) return;

    setResultsPanel('postgres-type-method-implementation', {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: summary.implementedCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'implemented type methods',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Implemented Type Methods',
                items: toSortedArray(summary.implementedMethods, 'schema', 'methodName'),
                cssClass: 'created', nameKey: 'methodName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Type Methods',
                items: toSortedArray(summary.skippedMethods, 'schema', 'methodName'),
                cssClass: 'skipped', nameKey: 'methodName', suffix: ' (skipped)'
            },
            {
                title: 'Failed Type Methods',
                items: toSortedArray(summary.errors, 'typeMethodName'),
                cssClass: 'error', nameKey: 'typeMethodName', showError: true
            }
        ], { jobId: result.jobId, label: 'type methods' })
    });
}

function displayTypeMethodVerificationResults(result) {
    const typeMethods = (result.summary && result.summary.typeMethods) || [];

    setResultsPanel('postgres-unified-type-method-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Total Type Methods', value: typeMethods.length }
        ]),
        detailLabel: 'type methods by schema',
        renderDetail: () => {
            if (typeMethods.length === 0) {
                return '<div class="table-items"><div class="table-item">No type methods found in PostgreSQL</div></div>';
            }
            const sorted = typeMethods.slice().sort((a, b) => {
                const typeCompare = (a.typeName || '').localeCompare(b.typeName || '');
                if (typeCompare !== 0) return typeCompare;
                return (a.methodName || '').localeCompare(b.methodName || '');
            });
            return renderSchemaGroups(sorted, renderVerifiedTypeMethodRow,
                { label: 'type methods', groupIdPrefix: 'type-method-verification-schema' });
        }
    });
}

// One type method row. The indicator is MEMBER/STATIC followed by FUNCTION/PROCEDURE.
function renderVerifiedTypeMethodRow(method) {
    const displayName = `${method.typeName}.${method.methodName}`;
    const memberIndicator = method.instantiable === 'YES' ? 'M' : 'S';
    const typeIndicator = method.methodType === 'FUNCTION' ? '𝑓' : 'ₚ';
    return `<div class="table-item created"><span class="type-method-indicator">${memberIndicator}${typeIndicator}</span> `
         + `${escapeHtml(displayName)} \u2713</div>`;
}

function toggleTypeMethodImplementationResults(database) {
    toggleResultsPanel(`${database}-type-method-implementation`);
}

function toggleUnifiedTypeMethodVerificationResults() {
    toggleResultsPanel('postgres-unified-type-method-verification');
}

// ===== END TYPE METHOD FUNCTIONS =====
