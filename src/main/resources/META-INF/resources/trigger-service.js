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
    const triggerItemsElement = document.getElementById(`${database}-trigger-items`);

    if (!triggerItemsElement) {
        console.warn('Trigger items element not found');
        return;
    }

    // Clear existing items
    triggerItemsElement.innerHTML = '';

    // Get triggers from result
    const triggers = result.result || [];

    if (triggers && triggers.length > 0) {
        // Group triggers by schema
        const schemaGroups = {};
        triggers.forEach(trigger => {
            const schema = trigger.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(trigger);
        });

        // Create schema groups
        Object.entries(schemaGroups).forEach(([schemaName, schemaTriggers]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaTriggers.length} triggers)`;
            schemaHeader.onclick = () => toggleTriggerSchemaGroup(database, schemaName);

            const triggerItems = document.createElement('div');
            triggerItems.className = 'table-items-list';
            triggerItems.id = `${database}-${schemaName}-triggers`;

            // Add individual trigger entries for this schema
            schemaTriggers.forEach(trigger => {
                const triggerItem = document.createElement('div');
                triggerItem.className = 'table-item';

                // Display trigger with table
                const displayName = `${trigger.triggerName} ON ${trigger.tableName}`;

                // Add trigger type indicators
                let typeIndicator = '';
                if (trigger.triggerType) {
                    if (trigger.triggerType.includes('BEFORE')) typeIndicator += 'B';
                    else if (trigger.triggerType.includes('AFTER')) typeIndicator += 'A';
                    else if (trigger.triggerType.includes('INSTEAD')) typeIndicator += 'I';
                }
                if (trigger.triggerLevel === 'ROW') typeIndicator += 'R';
                else if (trigger.triggerLevel === 'STATEMENT') typeIndicator += 'S';

                triggerItem.innerHTML = `<span class="trigger-indicator">${typeIndicator}</span> ${displayName}`;

                triggerItems.appendChild(triggerItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(triggerItems);
            triggerItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noTriggersItem = document.createElement('div');
        noTriggersItem.className = 'table-item';
        noTriggersItem.textContent = 'No triggers found';
        noTriggersItem.style.fontStyle = 'italic';
        noTriggersItem.style.color = '#999';
        triggerItemsElement.appendChild(noTriggersItem);
    }
}

function toggleTriggerSchemaGroup(database, schemaName) {
    const triggerItems = document.getElementById(`${database}-${schemaName}-triggers`);
    const header = triggerItems.previousElementSibling;
    const indicator = header.querySelector('.toggle-indicator');

    if (triggerItems.style.display === 'none') {
        triggerItems.style.display = 'block';
        indicator.textContent = '▼';
    } else {
        triggerItems.style.display = 'none';
        indicator.textContent = '▶';
    }
}

function displayTriggerImplementationResults(result) {
    const resultsDiv = document.getElementById('postgres-trigger-implementation-results');
    const detailsDiv = document.getElementById('postgres-trigger-implementation-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Trigger implementation results container not found');
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

        // Show implemented triggers
        if (summary.implementedCount > 0 && summary.implementedTriggers) {
            html += '<div class="created-tables-section">';
            html += '<h4>Implemented Triggers:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.implementedTriggers).forEach(trigger => {
                html += `<div class="table-item created">${trigger.triggerName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped triggers
        if (summary.skippedCount > 0 && summary.skippedTriggers) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Triggers:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedTriggers).forEach(trigger => {
                html += `<div class="table-item skipped">${trigger.triggerName} (skipped)</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Triggers:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.triggerName}</strong>: ${error.error}`;
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

function displayTriggerVerificationResults(result) {
    const resultsDiv = document.getElementById('postgres-unified-trigger-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-trigger-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Trigger verification results container not found');
        return;
    }

    let html = '';

    // Get triggers from result
    const triggers = result.result || [];
    const triggerCount = triggers.length;

    html += '<div class="table-creation-summary">';
    html += `<div class="summary-stats">`;
    html += `<span class="stat-item">Total Triggers: ${triggerCount}</span>`;
    html += `</div>`;
    html += '</div>';

    if (triggerCount > 0) {
        // Group by schema
        const schemaGroups = {};
        triggers.forEach(trigger => {
            const schema = trigger.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(trigger);
        });

        html += '<div class="created-tables-section">';
        html += '<h4>Verified Triggers (Click to view DDL):</h4>';

        Object.keys(schemaGroups).sort().forEach(schemaName => {
            const schemaTriggers = schemaGroups[schemaName];
            const schemaId = `trigger-verification-schema-${schemaName.replace(/[^a-z0-9]/gi, '_')}`;

            html += '<div class="table-schema-group">';
            html += `<div class="table-schema-header" onclick="toggleSchemaGroup('${schemaId}')">`;
            html += `<span class="toggle-indicator" id="${schemaId}-indicator">▶</span> `;
            html += `${schemaName} (${schemaTriggers.length} triggers)`;
            html += '</div>';
            html += `<div class="table-items-list" id="${schemaId}" style="display: none;">`;

            // Sort triggers by name within schema
            schemaTriggers.sort((a, b) => a.triggerName.localeCompare(b.triggerName));

            schemaTriggers.forEach(trigger => {
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

                html += `<div class="table-item created">`;
                html += `<div class="view-header" onclick="toggleTriggerDdlLazy('${triggerId}', '${schemaName}', '${trigger.triggerName}')">`;
                html += `<span class="toggle-indicator" id="${triggerId}-indicator">▶</span> `;
                html += `<strong><span class="trigger-indicator">${typeIndicator}</span> ${displayName}</strong>`;
                html += '</div>';

                // DDL section (collapsible, starts collapsed, will be loaded on demand)
                html += `<div class="view-ddl-section" id="${triggerId}" style="display: none;" data-schema="${schemaName}" data-trigger-name="${trigger.triggerName}" data-loaded="false">`;
                html += '<div class="loading-message">Loading...</div>';
                html += '</div>';
                html += '</div>';
            });

            html += '</div>';
            html += '</div>';
        });

        html += '</div>';
    } else {
        html += '<div class="table-items"><div class="table-item">No triggers found in PostgreSQL</div></div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleTriggerList(database) {
    const triggerItems = document.getElementById(`${database}-trigger-items`);
    const header = document.querySelector(`#${database}-trigger-list .table-list-header`);

    if (!triggerItems || !header) {
        console.warn(`Trigger list elements not found for database: ${database}`);
        return;
    }

    if (triggerItems.style.display === 'none') {
        triggerItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        triggerItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

function toggleTriggerImplementationResults(database) {
    const resultsDiv = document.getElementById(`${database}-trigger-implementation-results`);
    const detailsDiv = document.getElementById(`${database}-trigger-implementation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.warn('Trigger implementation results elements not found for database:', database);
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

function toggleUnifiedTriggerVerificationResults() {
    const resultsDiv = document.getElementById('postgres-unified-trigger-verification-results');
    const detailsDiv = document.getElementById('postgres-unified-trigger-verification-details');

    if (!resultsDiv || !detailsDiv) {
        console.warn('Unified trigger verification results elements not found');
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
function toggleSchemaGroup(schemaId) {
    const schemaItems = document.getElementById(schemaId);
    const indicator = document.getElementById(`${schemaId}-indicator`);

    if (!schemaItems) {
        console.warn(`Schema group not found: ${schemaId}`);
        return;
    }

    if (schemaItems.style.display === 'none') {
        schemaItems.style.display = 'block';
        if (indicator) indicator.textContent = '▼';
    } else {
        schemaItems.style.display = 'none';
        if (indicator) indicator.textContent = '▶';
    }
}

/**
 * Escape HTML to prevent XSS attacks in DDL display.
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ===== END TRIGGER FUNCTIONS =====
