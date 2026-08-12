/**
 * Trigger Service Module
 *
 * This module handles all trigger-related operations for the Oracle-to-PostgreSQL migration tool.
 */

// ===== TRIGGER FUNCTIONS =====

async function extractOracleTriggers() {
    console.log('Starting Oracle trigger extraction job...');

    const button = document.querySelector('#oracle-triggers .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle trigger extraction...');
    updateProgress(0, 'Starting Oracle trigger extraction');

    try {
        const response = await fetch('/api/triggers/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle trigger extraction job started:', result.jobId);
            updateMessage('Oracle trigger extraction job started successfully');

            // Start polling for progress
            await pollTriggerJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle trigger extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle trigger extraction job:', error);
        updateMessage('Failed to start Oracle trigger extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle trigger extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function createPostgresTriggers() {
    console.log('Starting PostgreSQL trigger implementation job...');

    updateComponentCount("postgres-triggers", "-");

    const button = document.querySelector('#postgres-triggers .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL trigger implementation...');
    updateProgress(0, 'Starting PostgreSQL trigger implementation');

    try {
        const response = await fetch('/api/triggers/postgres/implementation/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL trigger implementation job started:', result.jobId);
            updateMessage('PostgreSQL trigger implementation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollTriggerImplementationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL trigger implementation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL trigger implementation job:', error);
        updateMessage('Failed to start PostgreSQL trigger implementation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL trigger implementation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Triggers';
        }
    }
}

async function verifyAllPostgresTriggers() {
    console.log('Starting PostgreSQL trigger verification (unified)...');

    const button = document.querySelector('#postgres-triggers .verify-all-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL trigger verification...');
    updateProgress(0, 'Starting PostgreSQL trigger verification');

    try {
        const response = await fetch('/api/triggers/postgres/implementation/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL trigger verification job started:', result.jobId);
            updateMessage('PostgreSQL trigger verification job started successfully');
            // Start polling for progress
            await pollTriggerVerificationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL trigger verification job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL trigger verification job:', error);
        updateMessage('Failed to start PostgreSQL trigger verification: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL trigger verification');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function pollTriggerJobStatus(jobId, database) {
    console.log(`Polling trigger job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Trigger job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Trigger extraction completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} trigger extraction completed`);

                    // Get job results and update the UI
                    await getTriggerJobResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-triggers .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} trigger extraction failed`);
                    updateMessage(`${database.toUpperCase()} trigger extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-triggers .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    reject(new Error(status.error || 'Trigger extraction failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling trigger job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollTriggerImplementationJobStatus(jobId) {
    console.log('Polling trigger implementation job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log('Trigger implementation job status:', status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log('Trigger implementation completed:', status);
                    updateProgress(100, 'PostgreSQL trigger implementation completed');

                    // Get job results and display
                    await getTriggerImplementationResults(jobId);

                    // Re-enable button
                    const button = document.querySelector('#postgres-triggers .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Triggers';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, 'PostgreSQL trigger implementation failed');
                    updateMessage('PostgreSQL trigger implementation failed: ' + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector('#postgres-triggers .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Triggers';
                    }

                    reject(new Error(status.error || 'Trigger implementation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling trigger implementation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollTriggerVerificationJobStatus(jobId) {
    console.log('Polling trigger verification job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log('Trigger verification job status:', status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log('Trigger verification completed:', status);
                    updateProgress(100, 'PostgreSQL trigger verification completed');

                    // Get job results and display
                    await getTriggerVerificationResults(jobId);

                    // Re-enable button
                    const button = document.querySelector('#postgres-triggers .verify-all-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, 'PostgreSQL trigger verification failed');
                    updateMessage('PostgreSQL trigger verification failed: ' + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector('#postgres-triggers .verify-all-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    reject(new Error(status.error || 'Trigger verification failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling trigger verification job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getTriggerJobResults(jobId, database) {
    console.log('Getting trigger job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Trigger job results:', result);

            // Update badge count
            const triggerCount = result.triggerCount || 0;
            updateComponentCount(`${database}-triggers`, triggerCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            if (result.summary && result.summary.message) {
                updateMessage(`${databaseName}: ${result.summary.message}`);
            } else {
                updateMessage(`Extracted ${triggerCount} ${databaseName} triggers`);
            }

            // Populate trigger list UI
            populateTriggerList(result, database);

            // Show trigger list if there are triggers
            if (triggerCount > 0) {
                document.getElementById(`${database}-trigger-list`).style.display = 'block';
            }

        } else {
            throw new Error(result.message || 'Failed to get trigger job results');
        }

    } catch (error) {
        console.error('Error getting trigger job results:', error);
        updateMessage('Error getting trigger results: ' + error.message);
        updateComponentCount(`${database}-triggers`, '-', 'error');
    }
}

async function getTriggerImplementationResults(jobId) {
    console.log('Getting trigger implementation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Trigger implementation job results:', result);

            // Display the implementation results
            displayTriggerImplementationResults(result);

            // Update badge count
            const implementedCount = result.implementedCount || 0;
            updateComponentCount("postgres-triggers", implementedCount);

            // Show success message
            updateMessage(`PostgreSQL: Implemented ${result.implementedCount} triggers, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get trigger implementation results');
        }

    } catch (error) {
        console.error('Error getting trigger implementation results:', error);
        updateMessage('Error getting trigger implementation results: ' + error.message);
    }
}

async function getTriggerVerificationResults(jobId) {
    console.log('Getting trigger verification job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Trigger verification job results:', result);

            // Display the verification results
            displayTriggerVerificationResults(result);

            // Update badge count
            const verifiedCount = result.triggerCount || 0;
            updateComponentCount("postgres-triggers", verifiedCount);

            // Show success message
            updateMessage(`PostgreSQL: Verified ${verifiedCount} triggers`);

        } else {
            throw new Error(result.message || 'Failed to get trigger verification results');
        }

    } catch (error) {
        console.error('Error getting trigger verification results:', error);
        updateMessage('Error getting trigger verification results: ' + error.message);
    }
}

function populateTriggerList(result, database) {
    const triggers = result.result || [];

    setDeferredList(`${database}-trigger-list`, `${database}-trigger-items`,
        () => renderSchemaGroups(triggers, renderTriggerListRow,
            { label: 'triggers', groupIdPrefix: `${database}-trigger-group` }),
        `Triggers (${formatCount(triggers.length)})`);
}

// "BR MY_TRG ON EMP" - the leading letters are timing (Before/After/Instead of) and
// level (Row/Statement).
function renderTriggerListRow(trigger) {
    let typeIndicator = '';
    if (trigger.triggerType) {
        if (trigger.triggerType.includes('BEFORE')) typeIndicator += 'B';
        else if (trigger.triggerType.includes('AFTER')) typeIndicator += 'A';
        else if (trigger.triggerType.includes('INSTEAD')) typeIndicator += 'I';
    }
    if (trigger.triggerLevel === 'ROW') typeIndicator += 'R';
    else if (trigger.triggerLevel === 'STATEMENT') typeIndicator += 'S';

    const displayName = `${trigger.triggerName} ON ${trigger.tableName}`;
    return `<div class="table-item"><span class="trigger-indicator">${typeIndicator}</span> ${escapeHtml(displayName)}</div>`;
}

function toggleTriggerList(database) {
    toggleDeferredList(`${database}-trigger-list`);
}

function displayTriggerImplementationResults(result) {
    const summary = result.summary;
    if (!summary) return;

    setResultsPanel('postgres-trigger-implementation', {
        summaryHtml: renderSummaryStats([
            { label: 'Implemented', value: summary.implementedCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'implemented triggers',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Implemented Triggers',
                items: toSortedArray(summary.implementedTriggers, 'triggerName'),
                cssClass: 'created', nameKey: 'triggerName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Triggers',
                items: toSortedArray(summary.skippedTriggers, 'triggerName'),
                cssClass: 'skipped', nameKey: 'triggerName', suffix: ' (skipped)'
            },
            {
                title: 'Failed Triggers',
                items: toSortedArray(summary.errors, 'triggerName'),
                cssClass: 'error', nameKey: 'triggerName', showError: true
            }
        ], { jobId: result.jobId, label: 'triggers' })
    });
}

function displayTriggerVerificationResults(result) {
    const triggers = result.result || [];

    setResultsPanel('postgres-unified-trigger-verification', {
        summaryHtml: renderSummaryStats([
            { label: 'Total Triggers', value: triggers.length }
        ]),
        detailLabel: 'triggers by schema (click a trigger for its DDL)',
        renderDetail: () => {
            if (triggers.length === 0) {
                return '<div class="table-items"><div class="table-item">No triggers found in PostgreSQL</div></div>';
            }
            return renderSchemaGroups(
                triggers.slice().sort((a, b) => a.triggerName.localeCompare(b.triggerName)),
                trigger => renderVerifiedTriggerRow(trigger),
                { label: 'triggers', groupIdPrefix: 'trigger-verification-schema' });
        }
    });
}

// One trigger row; the DDL is fetched on click. The indicator letters are timing and level:
// B/A/I for BEFORE/AFTER/INSTEAD OF, R/S for ROW/STATEMENT.
function renderVerifiedTriggerRow(trigger) {
    const schemaName = trigger.schema || 'unknown';
    const triggerId = `trigger-ddl-${schemaName}-${trigger.triggerName}`.replace(/[^a-z0-9]/gi, '_');
    const displayName = `${trigger.triggerName} ON ${trigger.tableName}`;

    let typeIndicator = '';
    if (trigger.triggerType) {
        if (trigger.triggerType.includes('BEFORE')) typeIndicator += 'B';
        else if (trigger.triggerType.includes('AFTER')) typeIndicator += 'A';
        else if (trigger.triggerType.includes('INSTEAD')) typeIndicator += 'I';
    }
    if (trigger.triggerLevel === 'ROW') typeIndicator += 'R';
    else if (trigger.triggerLevel === 'STATEMENT') typeIndicator += 'S';

    let html = '<div class="table-item created">';
    html += `<div class="view-header" onclick="toggleTriggerDdlLazy('${triggerId}', '${escapeHtml(schemaName)}', '${escapeHtml(trigger.triggerName)}')">`;
    html += `<span class="toggle-indicator" id="${triggerId}-indicator">▶</span> `;
    html += `<strong><span class="trigger-indicator">${typeIndicator}</span> ${escapeHtml(displayName)}</strong>`;
    html += '</div>';
    html += `<div class="view-ddl-section" id="${triggerId}" style="display: none;" data-schema="${escapeHtml(schemaName)}" data-trigger-name="${escapeHtml(trigger.triggerName)}" data-loaded="false">`;
    html += '<div class="loading-message">Loading...</div>';
    html += '</div></div>';
    return html;
}


function toggleTriggerImplementationResults(database) {
    toggleResultsPanel(`${database}-trigger-implementation`);
}

function toggleUnifiedTriggerVerificationResults() {
    toggleResultsPanel('postgres-unified-trigger-verification');
}

/**
 * Toggle trigger DDL visibility with lazy loading.
 */
async function toggleTriggerDdlLazy(triggerId, schema, triggerName) {
    const ddlSection = document.getElementById(triggerId);
    const indicator = document.getElementById(`${triggerId}-indicator`);

    if (!ddlSection) {
        console.warn(`Trigger DDL section not found: ${triggerId}`);
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
        const response = await fetch(`/api/triggers/postgres/source/${encodeURIComponent(schema)}/${encodeURIComponent(triggerName)}`);
        const result = await response.json();

        if (result.status === 'success' && result.postgresSql) {
            // Replace loading message with actual DDL
            ddlSection.innerHTML = '<pre class="sql-statement">' + escapeHtml(result.postgresSql) + '</pre>';
            ddlSection.setAttribute('data-loaded', 'true');
        } else {
            // Show error
            ddlSection.innerHTML = '<div class="error-message">Failed to load trigger DDL: ' + escapeHtml(result.message || 'Unknown error') + '</div>';
        }
    } catch (error) {
        console.error('Error fetching trigger DDL:', error);
        ddlSection.innerHTML = '<div class="error-message">Failed to load trigger DDL: ' + escapeHtml(error.message) + '</div>';
    }
}

/**
 * Toggle schema group visibility (shared helper function).
 */
// ===== END TRIGGER FUNCTIONS =====
