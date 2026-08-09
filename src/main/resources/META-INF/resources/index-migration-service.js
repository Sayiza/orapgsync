/**
 * Index Migration Service Module
 *
 * Handles migration of the Oracle indexes that are NOT backed by a primary key or unique
 * constraint - PostgreSQL creates those itself during constraint creation.
 *
 * Distinct from fk-index-service.js: that step invents indexes on foreign key columns which
 * Oracle need not have had, and runs after this one so it can see what was migrated here.
 *
 * Functions included:
 * - extractOracleIndexes(): Starts Oracle index extraction
 * - createPostgresIndexes(): Starts PostgreSQL index creation
 * - pollIndexJobStatus(): Monitors either job
 * - displayIndexCreationResults(): Renders per-index outcomes
 * - toggleIndexCreationResults(): Toggles the results panel
 */

// ===== INDEX MIGRATION FUNCTIONS =====

async function extractOracleIndexes() {
    console.log('Starting Oracle index extraction job...');

    updateComponentCount('oracle-indexes', '-');

    const button = document.querySelector('#oracle-indexes .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle index extraction...');
    updateProgress(0, 'Starting Oracle index extraction');

    try {
        const response = await fetch('/api/indexes/oracle/extract', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('Oracle index extraction job started:', result.jobId);
            updateMessage('Oracle index extraction job started successfully');
            await pollIndexJobStatus(result.jobId, 'extraction');
        } else {
            throw new Error(result.message || 'Failed to start Oracle index extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle index extraction job:', error);
        updateMessage('Failed to start Oracle index extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle index extraction');
        resetIndexButton('oracle-indexes', 'Extract Indexes');
    }
}

async function createPostgresIndexes() {
    console.log('Starting PostgreSQL index creation job...');

    updateComponentCount('postgres-indexes', '-');

    const button = document.querySelector('#postgres-indexes .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL index creation...');
    updateProgress(0, 'Starting PostgreSQL index creation');

    try {
        const response = await fetch('/api/indexes/postgres/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL index creation job started:', result.jobId);
            updateMessage('PostgreSQL index creation job started successfully');
            await pollIndexJobStatus(result.jobId, 'creation');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL index creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL index creation job:', error);
        updateMessage('Failed to start PostgreSQL index creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL index creation');
        resetIndexButton('postgres-indexes', 'Create Indexes');
    }
}

/**
 * @param {string} jobId
 * @param {string} kind - 'extraction' or 'creation'
 */
async function pollIndexJobStatus(jobId, kind) {
    const isExtraction = kind === 'extraction';
    const componentId = isExtraction ? 'oracle-indexes' : 'postgres-indexes';
    const buttonLabel = isExtraction ? 'Extract Indexes' : 'Create Indexes';

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    updateProgress(100, `Index ${kind} completed`);
                    await getIndexJobResults(jobId, kind);
                    resetIndexButton(componentId, buttonLabel);
                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `Index ${kind} failed`);
                    updateMessage(`Index ${kind} failed: ` + (status.error || 'Unknown error'));
                    resetIndexButton(componentId, buttonLabel);
                    reject(new Error(status.error || `Index ${kind} failed`));
                } else {
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error(`Error polling index ${kind} job status:`, error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getIndexJobResults(jobId, kind) {
    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status !== 'success') {
            throw new Error(result.message || 'Failed to get index job results');
        }

        if (kind === 'extraction') {
            const count = (result.summary && result.summary.indexCount) || 0;
            updateComponentCount('oracle-indexes', count);
            const summary = result.summary || {};
            updateMessage(`Oracle: extracted ${count} indexes `
                + `(${summary.uniqueCount || 0} unique, ${summary.functionBasedCount || 0} function-based)`);
        } else {
            displayIndexCreationResults(result);
        }
    } catch (error) {
        console.error('Error getting index job results:', error);
        updateMessage('Error getting index job results: ' + error.message);
    }
}

function displayIndexCreationResults(result) {
    const resultsDiv = document.getElementById('postgres-index-creation-results');
    const detailsDiv = document.getElementById('postgres-index-creation-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Index creation results container not found');
        return;
    }

    const summary = result.summary;
    if (!summary) {
        return;
    }

    updateComponentCount('postgres-indexes', summary.createdCount || 0);
    updateMessage(`PostgreSQL: created ${summary.createdCount} indexes, skipped ${summary.skippedCount}, `
        + `${summary.unsupportedCount} unsupported, ${summary.errorCount} errors`);

    let html = '<div class="table-creation-summary"><div class="summary-stats">';
    html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
    html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
    html += `<span class="stat-item skipped">Unsupported: ${summary.unsupportedCount}</span>`;
    html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
    html += '</div></div>';

    html += renderIndexSection(summary.createdIndexes, 'Created Indexes', 'created');
    html += renderIndexSection(summary.skippedIndexes, 'Skipped Indexes', 'skipped');
    html += renderIndexSection(summary.unsupportedIndexes, 'Unsupported Indexes', 'skipped');
    html += renderIndexSection(summary.errors, 'Failed Indexes', 'error');

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

/**
 * Renders one outcome group. Every entry carries its reason, so a skipped or refused index can be
 * told apart from one that was never considered.
 */
function renderIndexSection(entries, title, cssClass) {
    const items = toIndexArray(entries);
    if (items.length === 0) {
        return '';
    }

    let html = `<div class="${cssClass}-tables-section">`;
    html += `<h4>${title}:</h4>`;
    html += '<div class="table-items">';

    items.forEach(item => {
        html += `<div class="table-item ${cssClass}">`;
        html += `<strong>${item.indexName}</strong> on ${item.tableName} (${item.keys})`;
        if (item.reason) {
            html += ` — ${item.reason}`;
        }
        if (cssClass === 'error' && item.sql) {
            html += `<div class="sql-statement"><pre>${item.sql}</pre></div>`;
        }
        html += '</div>';
    });

    html += '</div></div>';
    return html;
}

function toIndexArray(entries) {
    if (!entries) {
        return [];
    }
    return Array.isArray(entries) ? entries : Object.values(entries);
}

function resetIndexButton(componentId, label) {
    const button = document.querySelector(`#${componentId} .action-btn`);
    if (button) {
        button.disabled = false;
        button.innerHTML = label;
    }
}

function toggleIndexCreationResults() {
    const resultsDiv = document.getElementById('postgres-index-creation-results');
    const detailsDiv = document.getElementById('postgres-index-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== END INDEX MIGRATION FUNCTIONS =====
