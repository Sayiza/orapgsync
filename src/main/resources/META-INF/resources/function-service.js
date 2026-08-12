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
    const functions = (result.summary && result.summary.functions) || [];

    setDeferredList(`${database}-function-list`, `${database}-function-items`,
        () => renderSchemaGroups(functions, func => {
            const displayName = func.packageName ? `${func.packageName}.${func.objectName}` : func.objectName;
            const typeIndicator = func.objectType === 'FUNCTION' ? '𝑓' : 'ₚ';
            return `<div class="table-item"><span class="function-type-indicator">${typeIndicator}</span> ${escapeHtml(displayName)}</div>`;
        }, { label: 'functions/procedures', groupIdPrefix: `${database}-function-group` }),
        `Functions/Procedures (${formatCount(functions.length)})`);
}

function toggleFunctionList(database) {
    toggleDeferredList(`${database}-function-list`);
}

function displayFunctionStubCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-functions", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-function-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'function stubs',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Function Stubs',
                items: toSortedArray(summary.createdFunctions, 'functionName'),
                cssClass: 'created', nameKey: 'functionName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Functions (already exist)',
                items: toSortedArray(summary.skippedFunctions, 'functionName'),
                cssClass: 'skipped', nameKey: 'functionName', suffix: ' (already exists)'
            },
            {
                title: 'Failed Functions',
                items: toSortedArray(summary.errors, 'functionName'),
                cssClass: 'error', nameKey: 'functionName', showError: true
            }
        ], { jobId: result.jobId, label: 'functions' })
    });
}


function toggleFunctionCreationResults() {
    toggleResultsPanel('postgres-function-creation');
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
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-standalone-function-implementation",
        summary.implementedCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-standalone-function-implementation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: summary.implementedCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'implemented functions',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Implemented Functions/Procedures',
                items: toSortedArray(summary.implementedFunctions, 'schema', 'functionName'),
                cssClass: 'created', nameKey: 'functionName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Functions/Procedures',
                items: toSortedArray(summary.skippedFunctions, 'schema', 'functionName'),
                cssClass: 'skipped', nameKey: 'functionName'
            },
            {
                title: 'Failed Functions/Procedures',
                items: toSortedArray(summary.errors, 'functionName'),
                cssClass: 'error', nameKey: 'functionName', showError: true
            }
        ], { jobId: result.jobId, label: 'functions' })
    });
}

function toggleStandaloneFunctionImplementationResults(database) {
    toggleResultsPanel(`${database}-standalone-function-implementation`);
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
    setResultsPanel('postgres-standalone-function-implementation-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Verified', value: verificationResult.verifiedCount || 0, cssClass: 'created' },
            { label: 'Failed', value: verificationResult.failedCount || 0, cssClass: 'errors' },
            { label: 'Warnings', value: verificationResult.warningCount || 0, cssClass: 'skipped' }
        ]),
        detailLabel: 'functions by schema',
        renderDetail: () => {
            let html = '';

            if (verificationResult.verifiedFunctions && verificationResult.verifiedFunctions.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Verified Functions (Implemented)</h4>';
                html += generateSchemaGroupedFunctionList(verificationResult.verifiedFunctions, 'verified');
                html += '</div>';
            }

            if (verificationResult.failedFunctions && verificationResult.failedFunctions.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Failed Functions (Stubs or Errors)</h4>';
                html += generateSchemaGroupedFunctionList(verificationResult.failedFunctions, 'failed', verificationResult.failureReasons);
                html += '</div>';
            }

            if (verificationResult.warnings && verificationResult.warnings.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Warnings</h4><div class="table-items">';
                html += renderCappedList(verificationResult.warnings,
                    warning => `<div class="table-item warning">${escapeHtml(warning)}</div>`,
                    { label: 'warnings' });
                html += '</div></div>';
            }

            return html;
        }
    });
}

// Schema-grouped function list with expandable DDL.
// The DDL is the expensive part - a few thousand functions is a few thousand <pre> blocks -
// so it is held in the deferred-code registry and materialized one function at a time.
function generateSchemaGroupedFunctionList(functions, statusClass, failureReasons) {
    const normalized = functions.map(func => {
        const qualifiedName = func.qualifiedName || `${func.schema}.${func.functionName}`;
        const parts = qualifiedName.split('.');
        return {
            qualifiedName: qualifiedName,
            schema: parts.length > 1 ? parts[0] : 'unknown',
            functionName: parts.length > 1 ? parts.slice(1).join('.') : qualifiedName,
            signature: func.signature,
            ddl: func.ddl,
            lineCount: func.lineCount,
            isStub: func.isStub
        };
    });

    return renderSchemaGroups(normalized,
        func => renderVerifiedFunctionRow(func, statusClass, failureReasons),
        { label: 'functions', groupIdPrefix: `function-verification-${statusClass}` });
}

// One function row: metadata line always, DDL only once asked for.
function renderVerifiedFunctionRow(func, statusClass, failureReasons) {
    const funcId = `func-ddl-${++functionRowSeq}`;
    const statusIcon = statusClass === 'verified' ? '✓' : '✗';
    const stubLabel = func.isStub ? ' [STUB]' : '';

    functionDdlSources.set(funcId, func.ddl || 'No DDL available');

    let html = `<div class="function-item ${statusClass}">`;
    html += `<div class="function-metadata" onclick="toggleFunctionDDL('${funcId}')">`;
    html += `<span class="toggle-indicator" id="${funcId}-indicator">▶</span> `;
    html += `<strong>${escapeHtml(func.functionName)}</strong>${stubLabel} ${statusIcon}`;
    html += ` <span class="function-signature">${escapeHtml(func.signature || '')}</span>`;
    html += ` <span class="function-lines">(${func.lineCount || 0} lines)</span>`;

    if (failureReasons && failureReasons[func.qualifiedName]) {
        html += `<br/><span class="failure-reason">${escapeHtml(failureReasons[func.qualifiedName])}</span>`;
    }

    html += `</div>`;
    html += `<div class="function-ddl" id="${funcId}" style="display: none;"></div>`;
    html += `</div>`;
    return html;
}

// funcId -> DDL source, kept out of the DOM until the row is expanded.
const functionDdlSources = new Map();
let functionRowSeq = 0;

// Toggle function DDL display
function toggleFunctionDDL(funcId) {
    const ddlDiv = document.getElementById(funcId);
    const indicator = document.getElementById(`${funcId}-indicator`);

    if (!ddlDiv) {
        console.warn(`Function DDL not found: ${funcId}`);
        return;
    }

    if (ddlDiv.style.display === 'none') {
        // Build the DDL block on first open; discard it again on close.
        const ddl = functionDdlSources.get(funcId) || 'No DDL available';
        ddlDiv.innerHTML = `<button class="copy-ddl-btn" onclick="copyFunctionDDL('${funcId}-ddl')">Copy DDL</button>`
                         + `<pre id="${funcId}-ddl" class="ddl-content">${escapeHtml(ddl)}</pre>`;
        ddlDiv.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        ddlDiv.innerHTML = '';
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
    toggleResultsPanel(`${database}-standalone-function-implementation-verification`);
}

// Utility function to escape HTML (prevent XSS)
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
    const functionsBySchema = verificationResult.functionsBySchema || {};

    setResultsPanel('postgres-unified-function-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: verificationResult.implementedCount || 0, cssClass: 'created' },
            { label: 'Stubs', value: verificationResult.stubCount || 0, cssClass: 'skipped' },
            { label: 'Errors', value: verificationResult.errorCount || 0, cssClass: 'errors' },
            { label: 'Total', value: verificationResult.totalFunctions || 0 }
        ]),
        detailLabel: 'functions by schema',
        renderDetail: () => renderFunctionVerificationSchemaGroups(functionsBySchema)
    });
}

// Schema groups for the unified function verification panel, each with its own
// implemented/stub/error breakdown in the header.
function renderFunctionVerificationSchemaGroups(functionsBySchema) {
    let html = '';

    Object.keys(functionsBySchema).sort().forEach(schemaName => {
        const schemaFunctions = (functionsBySchema[schemaName] || [])
            .slice()
            .sort((a, b) => a.functionName.localeCompare(b.functionName));
        const groupId = `function-verification-schema-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        const implemented = schemaFunctions.filter(f => f.status === 'IMPLEMENTED').length;
        const stubs = schemaFunctions.filter(f => f.status === 'STUB').length;
        const errors = schemaFunctions.filter(f => f.status === 'ERROR').length;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header collapsed" onclick="toggleSchemaGroupRows('${groupId}')">`;
        html += `<span class="toggle-indicator">▶</span> `;
        html += `${escapeHtml(schemaName)} (${formatCount(schemaFunctions.length)} functions - `;
        html += `${implemented} implemented, ${stubs} stubs, ${errors} errors)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${groupId}" style="display: none;"></div>`;
        html += '</div>';

        schemaGroupRenderers.set(groupId, {
            expanded: false,
            render: () => renderCappedList(schemaFunctions,
                func => renderUnifiedFunctionRow(schemaName, func),
                { label: 'functions' })
        });
    });

    return html;
}

// One function row; its DDL is fetched from the backend on click.
function renderUnifiedFunctionRow(schemaName, func) {
    const funcId = `function-ddl-${schemaName}-${func.functionName}`.replace(/[^a-z0-9]/gi, '_');
    const statusClass = func.status === 'IMPLEMENTED' ? 'created' :
                        func.status === 'STUB' ? 'skipped' : 'error';
    const statusBadge = func.status === 'IMPLEMENTED' ? '✓ IMPLEMENTED' :
                        func.status === 'STUB' ? '⚠ STUB' : '✗ ERROR';
    const typeBadge = func.functionType || 'FUNCTION';
    const packageIndicator = func.isPackageMember ? '📦 Package' : 'Standalone';

    let html = `<div class="table-item ${statusClass}">`;
    html += `<div class="view-header" onclick="toggleFunctionDdlLazy('${funcId}', '${escapeHtml(schemaName)}', '${escapeHtml(func.functionName)}')">`;
    html += `<span class="toggle-indicator" id="${funcId}-indicator">▶</span> `;
    html += `<strong>${escapeHtml(func.functionName)}</strong> `;
    html += `<span class="status-badge">[${escapeHtml(typeBadge)}]</span> `;
    html += `<span class="status-badge">[${packageIndicator}]</span> `;
    html += `<span class="status-badge">[${statusBadge}]</span>`;
    html += '</div>';
    html += `<div class="view-ddl-section" id="${funcId}" style="display: none;" data-schema="${escapeHtml(schemaName)}" data-function-name="${escapeHtml(func.functionName)}" data-loaded="false">`;
    if (func.errorMessage) {
        html += `<div class="error-message">Error: ${escapeHtml(func.errorMessage)}</div>`;
    } else {
        html += '<div class="loading-message">Loading...</div>';
    }
    html += '</div></div>';
    return html;
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
    toggleResultsPanel('postgres-unified-function-verification');
}

// ===== END UNIFIED FUNCTION VERIFICATION =====

// ===== END FUNCTION FUNCTIONS =====
