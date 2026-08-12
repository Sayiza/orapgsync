/**
 * View Service Module
 *
 * This module handles all view-related operations for the Oracle to PostgreSQL migration tool.
 *
 * Key Responsibilities:
 * - View Definition Extraction: Extract view metadata from Oracle and PostgreSQL databases
 * - View Stub Creation: Create PostgreSQL view stubs (views with correct structure but empty result sets)
 * - Display Operations: Render view lists and creation results in the UI
 * - Job Management: Poll job status and handle completion for view-related jobs
 *
 * Functions included:
 * - extractOracleViews(): Extract Oracle view definitions
 * - extractPostgresViews(): Extract PostgreSQL view definitions
 * - createPostgresViewStubs(): Create PostgreSQL view stubs
 * - pollViewJobStatus(): Poll view extraction job status
 * - getViewJobResults(): Retrieve view job results
 * - displayViewResults(): Display view extraction results
 * - populateViewList(): Populate UI with extracted view metadata
 * - toggleViewList(): Toggle view list visibility
 * - displayViewStubCreationResults(): Display view stub creation results
 * - toggleViewStubCreationResults(): Toggle view stub creation results visibility
 */

// View Definition Extraction Job Management Functions

// Extract Oracle view definitions (starts the job)
async function extractOracleViews() {
    console.log('Starting Oracle view definition extraction job...');

    const button = document.querySelector('#oracle-views .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle view definition extraction...');
    updateProgress(0, 'Starting Oracle view definition extraction');

    try {
        const response = await fetch('/api/views/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle view extraction job started:', result.jobId);
            updateMessage('Oracle view extraction job started successfully');

            // Start polling for progress
            pollViewJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle view extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle view extraction job:', error);
        updateMessage('Failed to start Oracle view extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle view extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Extract PostgreSQL view definitions (starts the job)
async function extractPostgresViews() {
    console.log('Starting PostgreSQL view definition extraction job...');

    const button = document.querySelector('#postgres-views .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view definition extraction...');
    updateProgress(0, 'Starting PostgreSQL view definition extraction');

    try {
        const response = await fetch('/api/views/postgres/stubs/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view extraction job started:', result.jobId);
            updateMessage('PostgreSQL view extraction job started successfully');

            // Start polling for progress
            pollViewJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view extraction job:', error);
        updateMessage('Failed to start PostgreSQL view extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Poll view job status until completion
async function pollViewJobStatus(jobId, database = 'oracle') {
    console.log('Polling view job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('View job status:', result);

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
                console.log('View job completed successfully');
                updateProgress(100, 'View job completed successfully');
                updateMessage('View definition extraction completed');

                // Get job results
                await getViewJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('View job failed:', result.error);
                updateProgress(0, 'View job failed');
                updateMessage('View extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-views .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollViewJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling view job status:', error);
        updateMessage('Error checking view job status: ' + error.message);
        updateProgress(0, 'Error checking view job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-views .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

// Get view job results and display them
async function getViewJobResults(jobId, database = 'oracle') {
    console.log('Getting view job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('View job results:', result);
            displayViewResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get view job results');
        }

    } catch (error) {
        console.error('Error getting view job results:', error);
        updateMessage('Error getting view results: ' + error.message);
    }
}

// Display view extraction results
function displayViewResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Extract view count from summary
        const viewCount = result.result ? result.result.length : 0;

        // Update view count badge
        updateComponentCount(`${database}-views`, viewCount);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted ${viewCount} ${databaseName} views`);

        // Populate view list
        if (result.result && result.result.length > 0) {
            populateViewList(result.result, database);

            // Show view list
            document.getElementById(`${database}-view-list`).style.display = 'block';
        }
    }
}

// Populate view list with extracted view metadata.
// The rows are not built here - only the header summary is. Expanding the list builds the
// schema groups, and expanding a group builds that group's rows.
function populateViewList(views, database = 'oracle') {
    setDeferredList(`${database}-view-list`, `${database}-view-items`,
        () => renderSchemaGroups(views, view => {
            const columnCount = view.columns ? view.columns.length : 0;
            return `<div class="table-item">${escapeHtml(view.viewName)} (${columnCount} cols)</div>`;
        }, { label: 'views', groupIdPrefix: `${database}-view-group` }),
        `View Names (${formatCount(views ? views.length : 0)})`);
}

// Toggle view list visibility
function toggleViewList(database) {
    toggleDeferredList(`${database}-view-list`);
}

// View Stub Creation Functions

// Create PostgreSQL view stubs (starts the job)
async function createPostgresViewStubs() {
    console.log('Starting PostgreSQL view stub creation job...');

    updateComponentCount("postgres-views", "-");

    const button = document.querySelector('#postgres-views .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view stub creation...');
    updateProgress(0, 'Starting PostgreSQL view stub creation');

    try {
        const response = await fetch('/api/views/postgres/stubs/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view stub creation job started:', result.jobId);
            updateMessage('PostgreSQL view stub creation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollViewStubCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view stub creation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view stub creation job:', error);
        updateMessage('Failed to start PostgreSQL view stub creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view stub creation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create View Stubs';
        }
    }
}

async function pollViewStubCreationJobStatus(jobId, database) {
    console.log(`Polling view stub creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`View stub creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`View stub creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleViewStubCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-views .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create View Stubs';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling view stub creation job status:', error);
                updateMessage('Error checking view stub creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-views .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create View Stubs';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleViewStubCreationJobComplete(result, database) {
    console.log(`View stub creation job results for ${database}:`, result);

    // Access counts from top-level result (these are provided by JobResource)
    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `View stub creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`View stub creation completed successfully: ${createdCount} view stubs created, ${skippedCount} already existed`);
    } else {
        updateMessage(`View stub creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update view stub creation results section
    displayViewStubCreationResults(result, database);
}

function displayViewStubCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-views", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-view-stub-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'view stubs',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created View Stubs',
                items: toSortedArray(summary.createdViews, 'schema', 'viewName'),
                cssClass: 'created', nameKey: 'viewName', suffix: ' ✓'
            },
            {
                title: 'Skipped Views (already exist)',
                items: toSortedArray(summary.skippedViews, 'schema', 'viewName'),
                cssClass: 'skipped', nameKey: 'viewName'
            },
            {
                title: 'Failed Views',
                items: toSortedArray(summary.errors, 'viewName'),
                cssClass: 'error', nameKey: 'viewName', showError: true
            }
        ], { jobId: result.jobId, label: 'views' })
    });
}

function toggleViewStubCreationResults(database) {
    toggleResultsPanel(`${database}-view-stub-creation`);
}

// View Implementation Functions (Phase 2)

// Create PostgreSQL view implementations (replaces stubs with actual SQL)
async function createPostgresViewImplementation() {
    console.log('Starting PostgreSQL view implementation job...');

    updateComponentCount("postgres-view-implementation", "-");

    const button = document.querySelector('#postgres-view-implementation .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view implementation...');
    updateProgress(0, 'Starting PostgreSQL view implementation');

    try {
        const response = await fetch('/api/views/postgres/implementation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view implementation job started:', result.jobId);
            updateMessage('PostgreSQL view implementation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollViewImplementationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view implementation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view implementation job:', error);
        updateMessage('Failed to start PostgreSQL view implementation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view implementation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Views';
        }
    }
}

async function pollViewImplementationJobStatus(jobId, database) {
    console.log(`Polling view implementation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`View implementation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`View implementation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleViewImplementationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-view-implementation .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Views';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling view implementation job status:', error);
                updateMessage('Error checking view implementation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-view-implementation .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Views';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleViewImplementationJobComplete(result, database) {
    console.log(`View implementation job results for ${database}:`, result);

    // Access counts from top-level result (these are provided by JobResource)
    const implementedCount = result.implementedCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `View implementation completed: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`View implementation completed successfully: ${implementedCount} views implemented, ${skippedCount} skipped`);
    } else {
        updateMessage(`View implementation completed with errors: ${implementedCount} implemented, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update view implementation results section
    displayViewImplementationResults(result, database);
}

function displayViewImplementationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-view-implementation", summary.implementedCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-view-implementation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: summary.implementedCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'implemented views',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Implemented Views',
                items: toSortedArray(summary.implementedViews, 'schema', 'viewName'),
                cssClass: 'created', nameKey: 'viewName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Views',
                items: toSortedArray(summary.skippedViews, 'schema', 'viewName'),
                cssClass: 'skipped', nameKey: 'viewName'
            },
            {
                title: 'Failed Views',
                items: toSortedArray(summary.errors, 'viewName'),
                cssClass: 'error', nameKey: 'viewName', showError: true
            }
        ], { jobId: result.jobId, label: 'views' })
    });
}

function toggleViewImplementationResults(database) {
    toggleResultsPanel(`${database}-view-implementation`);
}

function toggleViewImplementationVerificationResults(database) {
    toggleResultsPanel(`${database}-view-implementation-verification`);
}

// Verify PostgreSQL view implementations
async function verifyPostgresViewImplementation() {
    console.log('Starting PostgreSQL view implementation verification job...');

    const button = document.querySelector('#postgres-view-implementation .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view implementation verification...');
    updateProgress(0, 'Starting PostgreSQL view implementation verification');

    try {
        const response = await fetch('/api/views/postgres/implementation/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view implementation verification job started:', result.jobId);
            updateMessage('PostgreSQL view implementation verification job started successfully');

            // Start polling for progress
            pollViewImplementationVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view implementation verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view implementation verification job:', error);
        updateMessage('Failed to start PostgreSQL view implementation verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view implementation verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function pollViewImplementationVerificationJobStatus(jobId) {
    console.log('Polling view implementation verification job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('View implementation verification job status:', result);

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
                console.log('View implementation verification job completed successfully');
                updateProgress(100, 'View implementation verification completed successfully');
                updateMessage('View implementation verification completed');

                // Get job results
                await getViewImplementationVerificationJobResults(jobId);
            } else if (result.status === 'FAILED') {
                console.error('View implementation verification job failed:', result.error);
                updateProgress(0, 'View implementation verification failed');
                updateMessage('View implementation verification failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable button
            const button = document.querySelector('#postgres-view-implementation .refresh-btn');
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollViewImplementationVerificationJobStatus(jobId), 1000);
        }

    } catch (error) {
        console.error('Error polling view implementation verification job status:', error);
        updateMessage('Error checking view implementation verification job status: ' + error.message);
        updateProgress(0, 'Error checking view implementation verification job status');

        // Re-enable button
        const button = document.querySelector('#postgres-view-implementation .refresh-btn');
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function getViewImplementationVerificationJobResults(jobId) {
    console.log('Getting view implementation verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('View implementation verification job results:', result);

            // The result is now the unwrapped ViewImplementationVerificationResult object
            const verificationResult = result.result;

            if (verificationResult) {
                // Counts are also at top level for convenience
                const verifiedCount = result.verifiedCount || 0;
                const failedCount = result.failedCount || 0;
                const warningCount = result.warningCount || 0;

                if (result.isSuccessful) {
                    updateMessage(`View implementation verification completed: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                } else {
                    updateMessage(`View implementation verification found issues: ${verifiedCount} verified, ${failedCount} failed, ${warningCount} warnings`);
                }

                updateComponentCount("postgres-view-implementation", verifiedCount);

                // Display the detailed verification results
                displayViewImplementationVerificationResults(verificationResult);
            } else {
                updateMessage('View implementation verification completed but returned no results');
            }
        } else {
            throw new Error(result.message || 'Failed to get view implementation verification job results');
        }

    } catch (error) {
        console.error('Error getting view implementation verification job results:', error);
        updateMessage('Error getting view implementation verification results: ' + error.message);
    }
}

// Display view implementation verification results
function displayViewImplementationVerificationResults(verificationResult) {
    setResultsPanel('postgres-view-implementation-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Verified', value: verificationResult.verifiedCount || 0, cssClass: 'created' },
            { label: 'Failed', value: verificationResult.failedCount || 0, cssClass: 'errors' },
            { label: 'Warnings', value: verificationResult.warningCount || 0, cssClass: 'skipped' }
        ]),
        detailLabel: 'verified views by schema',
        renderDetail: () => {
            let html = '';

            if (verificationResult.verifiedViews && verificationResult.verifiedViews.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Verified Views (Implemented)</h4>';
                html += generateSchemaGroupedViewList(verificationResult.verifiedViews, 'verified', verificationResult.rowCounts);
                html += '</div>';
            }

            if (verificationResult.failedViews && verificationResult.failedViews.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Failed Views (Not Implemented or Errors)</h4>';
                html += generateSchemaGroupedFailedViewList(verificationResult.failedViews, verificationResult.failureReasons);
                html += '</div>';
            }

            if (verificationResult.warnings && verificationResult.warnings.length > 0) {
                html += '<div class="outcome-section">';
                html += '<h4>Warnings</h4>';
                html += generateSchemaGroupedWarningList(verificationResult.warnings);
                html += '</div>';
            }

            return html;
        }
    });
}

// Split "SCHEMA.OBJECT" into its parts; unqualified names fall back to schema 'unknown'.
function splitQualifiedName(qualifiedName) {
    const parts = String(qualifiedName || '').split('.');
    return parts.length > 1
        ? { schema: parts[0], name: parts.slice(1).join('.') }
        : { schema: 'unknown', name: qualifiedName };
}

// Schema-grouped list of verified views, with row counts where known
function generateSchemaGroupedViewList(verifiedViews, statusClass, rowCounts) {
    const views = verifiedViews.map(view => {
        const split = splitQualifiedName(view.viewName);
        return { schema: split.schema, viewName: split.name, rowCount: view.rowCount };
    });

    return renderSchemaGroups(views, view => {
        const rowCountText = view.rowCount !== undefined ? ` (${formatCount(view.rowCount)} rows)` : '';
        return `<div class="table-item ${statusClass}">${escapeHtml(view.viewName)}${rowCountText} \u2713</div>`;
    }, { label: 'views', groupIdPrefix: `view-verification-${statusClass}` });
}

// Schema-grouped list of failed views with their failure reasons
function generateSchemaGroupedFailedViewList(failedViews, failureReasons) {
    const views = failedViews.map(qualifiedName => {
        const split = splitQualifiedName(qualifiedName);
        return {
            schema: split.schema,
            viewName: split.name,
            reason: failureReasons && failureReasons[qualifiedName] ? failureReasons[qualifiedName] : 'Unknown reason'
        };
    });

    return renderSchemaGroups(views, view =>
        `<div class="table-item error"><strong>${escapeHtml(view.viewName)}</strong>: ${escapeHtml(view.reason)}</div>`,
        { label: 'views', groupIdPrefix: 'view-verification-failed' });
}

// Schema-grouped warning list; warnings arrive as "schema.viewname: message"
function generateSchemaGroupedWarningList(warnings) {
    const entries = warnings.map(warning => {
        const colonIndex = warning.indexOf(':');
        const qualifiedName = colonIndex > 0 ? warning.substring(0, colonIndex).trim() : warning;
        const message = colonIndex > 0 ? warning.substring(colonIndex + 1).trim() : 'Warning';
        const split = splitQualifiedName(qualifiedName);
        return { schema: split.schema, viewName: split.name, message: message };
    });

    return renderSchemaGroups(entries, entry =>
        `<div class="table-item skipped">${escapeHtml(entry.viewName)}: ${escapeHtml(entry.message)}</div>`,
        { label: 'warnings', groupIdPrefix: 'view-verification-warnings' });
}

// ==========================================
// Unified View Verification (NEW)
// ==========================================

/**
 * Verify all PostgreSQL views (unified verification job).
 * Replaces separate stub and implementation verification jobs.
 * Returns DDL for manual inspection instead of row counts.
 */
async function verifyAllPostgresViews() {
    console.log('Starting unified PostgreSQL view verification job...');

    const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL view verification...');
    updateProgress(0, 'Starting PostgreSQL view verification');

    try {
        const response = await fetch('/api/views/postgres/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL view verification job started:', result.jobId);
            updateMessage('PostgreSQL view verification job started successfully');

            // Start polling for progress
            await pollUnifiedViewVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL view verification job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL view verification job:', error);
        updateMessage('Failed to start PostgreSQL view verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL view verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳ Verify All Views';
        }
    }
}

async function pollUnifiedViewVerificationJobStatus(jobId) {
    console.log('Polling unified view verification job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Unified view verification job status:', result);

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
                console.log('Unified view verification job completed successfully');
                updateProgress(100, 'View verification completed successfully');
                updateMessage('View verification completed');

                // Get job results
                await getUnifiedViewVerificationJobResults(jobId);
            } else if (result.status === 'FAILED') {
                console.error('Unified view verification job failed:', result.error);
                updateProgress(0, 'View verification failed');
                updateMessage('View verification failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable button
            const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳ Verify All Views';
            }
        } else {
            // Continue polling
            setTimeout(() => pollUnifiedViewVerificationJobStatus(jobId), 1000);
        }

    } catch (error) {
        console.error('Error polling unified view verification job status:', error);
        updateMessage('Error checking view verification job status: ' + error.message);
        updateProgress(0, 'Error checking view verification job status');

        // Re-enable button
        const button = document.querySelector('#postgres-view-implementation .verify-all-btn');
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳ Verify All Views';
        }
    }
}

async function getUnifiedViewVerificationJobResults(jobId) {
    console.log('Getting unified view verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Unified view verification job results:', result);

            // The result contains the ViewVerificationResult object
            const verificationResult = result.result;

            if (verificationResult) {
                const totalViews = result.totalViews || 0;
                const implementedCount = result.implementedCount || 0;
                const stubCount = result.stubCount || 0;
                const errorCount = result.errorCount || 0;

                updateMessage(`View verification completed: ${totalViews} views (${implementedCount} implemented, ${stubCount} stubs, ${errorCount} errors)`);
                updateComponentCount("postgres-view-implementation", implementedCount);

                // Display the detailed verification results
                displayUnifiedViewVerificationResults(verificationResult);
            } else {
                updateMessage('View verification completed but returned no results');
            }
        } else {
            throw new Error(result.message || 'Failed to get view verification job results');
        }

    } catch (error) {
        console.error('Error getting unified view verification job results:', error);
        updateMessage('Error getting view verification results: ' + error.message);
    }
}

/**
 * Display unified view verification results with DDL inspection.
 * Groups views by schema with collapsible DDL sections.
 */
function displayUnifiedViewVerificationResults(verificationResult) {
    const viewsBySchema = verificationResult.viewsBySchema || {};

    setResultsPanel('postgres-unified-view-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: verificationResult.implementedCount || 0, cssClass: 'created' },
            { label: 'Stubs', value: verificationResult.stubCount || 0, cssClass: 'skipped' },
            { label: 'Errors', value: verificationResult.errorCount || 0, cssClass: 'errors' },
            { label: 'Total', value: verificationResult.totalViews || 0 }
        ]),
        detailLabel: 'views by schema',
        renderDetail: () => renderVerificationSchemaGroups(viewsBySchema)
    });
}

// Schema groups for the unified verification panel. Each group reports its own
// implemented/stub/error breakdown in the header, so the counts are readable without
// expanding anything.
function renderVerificationSchemaGroups(viewsBySchema) {
    let html = '';

    Object.keys(viewsBySchema).sort().forEach(schemaName => {
        const schemaViews = (viewsBySchema[schemaName] || [])
            .slice()
            .sort((a, b) => a.viewName.localeCompare(b.viewName));
        const groupId = `view-verification-schema-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

        const implemented = schemaViews.filter(v => v.status === 'IMPLEMENTED').length;
        const stubs = schemaViews.filter(v => v.status === 'STUB').length;
        const errors = schemaViews.filter(v => v.status === 'ERROR').length;

        html += '<div class="table-schema-group">';
        html += `<div class="table-schema-header collapsed" onclick="toggleSchemaGroupRows('${groupId}')">`;
        html += `<span class="toggle-indicator">\u25b6</span> `;
        html += `${escapeHtml(schemaName)} (${formatCount(schemaViews.length)} views - `;
        html += `${implemented} implemented, ${stubs} stubs, ${errors} errors)`;
        html += '</div>';
        html += `<div class="table-items-list" id="${groupId}" style="display: none;"></div>`;
        html += '</div>';

        schemaGroupRenderers.set(groupId, {
            expanded: false,
            render: () => renderCappedList(schemaViews,
                view => renderVerificationViewRow(schemaName, view),
                { label: 'views' })
        });
    });

    return html;
}

// One view row, with its DDL still unloaded - the DDL is fetched per view on click.
function renderVerificationViewRow(schemaName, view) {
    const viewId = `view-ddl-${schemaName}-${view.viewName}`.replace(/[^a-z0-9]/gi, '_');
    const statusClass = view.status === 'IMPLEMENTED' ? 'created' :
                        view.status === 'STUB' ? 'skipped' : 'error';
    const statusBadge = view.status === 'IMPLEMENTED' ? '\u2713 IMPLEMENTED' :
                        view.status === 'STUB' ? '\u26a0 STUB' : '\u2717 ERROR';

    let html = `<div class="table-item ${statusClass}">`;
    html += `<div class="view-header" onclick="toggleViewDdlLazy('${viewId}', '${escapeHtml(schemaName)}', '${escapeHtml(view.viewName)}')">`;
    html += `<span class="toggle-indicator" id="${viewId}-indicator">\u25b6</span> `;
    html += `<strong>${escapeHtml(view.viewName)}</strong> <span class="status-badge">[${statusBadge}]</span>`;
    html += '</div>';
    html += `<div class="view-ddl-section" id="${viewId}" style="display: none;" data-schema="${escapeHtml(schemaName)}" data-view-name="${escapeHtml(view.viewName)}" data-loaded="false">`;
    if (view.errorMessage) {
        html += `<div class="error-message">Error: ${escapeHtml(view.errorMessage)}</div>`;
    } else {
        html += '<div class="loading-message">Loading...</div>';
    }
    html += '</div></div>';
    return html;
}

/**
 * Toggle view DDL visibility with lazy loading.
 */
async function toggleViewDdlLazy(viewId, schema, viewName) {
    const ddlSection = document.getElementById(viewId);
    const indicator = document.getElementById(`${viewId}-indicator`);

    if (!ddlSection) {
        console.warn(`View DDL section not found: ${viewId}`);
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
        const response = await fetch(`/api/views/postgres/source/${encodeURIComponent(schema)}/${encodeURIComponent(viewName)}`);
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
        console.error('Error fetching view DDL:', error);
        ddlSection.innerHTML = '<div class="error-message">Failed to load DDL: ' + escapeHtml(error.message) + '</div>';
    }
}

/**
 * Toggle view DDL visibility (legacy function for backward compatibility).
 */
function toggleViewDdl(viewId) {
    const ddlSection = document.getElementById(viewId);
    const indicator = document.getElementById(`${viewId}-indicator`);

    if (!ddlSection) {
        console.warn(`View DDL section not found: ${viewId}`);
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
 * Toggle unified view verification results visibility.
 */
function toggleUnifiedViewVerificationResults() {
    toggleResultsPanel('postgres-unified-view-verification');
}
