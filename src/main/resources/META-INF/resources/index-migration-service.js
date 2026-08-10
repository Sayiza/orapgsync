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
 * - extractOracleIndexes(): Reads the non-constraint indexes in Oracle
 * - extractPostgresIndexes(): Reads the non-constraint indexes currently in PostgreSQL
 * - createPostgresIndexes(): Creates the migrated indexes in PostgreSQL
 * - pollIndexJobStatus(): Monitors any of the three jobs
 * - populateIndexList(): Renders the index list grouped by schema
 * - displayIndexCreationResults(): Renders per-index outcomes
 * - toggleIndexList() / toggleIndexSchemaGroup() / toggleIndexCreationResults(): UI toggles
 */

// ===== INDEX MIGRATION FUNCTIONS =====

async function extractOracleIndexes() {
    return startIndexJob({
        url: '/api/indexes/oracle/extract',
        componentId: 'oracle-indexes',
        buttonSelector: '#oracle-indexes .refresh-btn',
        buttonLabel: '⟳',
        kind: 'extraction',
        database: 'oracle',
        description: 'Oracle index extraction'
    });
}

async function extractPostgresIndexes() {
    return startIndexJob({
        url: '/api/indexes/postgres/extract',
        componentId: 'postgres-indexes',
        buttonSelector: '#postgres-indexes .refresh-btn',
        buttonLabel: '⟳',
        kind: 'extraction',
        database: 'postgres',
        description: 'PostgreSQL index extraction'
    });
}

async function createPostgresIndexes() {
    return startIndexJob({
        url: '/api/indexes/postgres/create',
        componentId: 'postgres-indexes',
        buttonSelector: '#postgres-indexes .action-btn',
        buttonLabel: 'Create Indexes',
        kind: 'creation',
        database: 'postgres',
        description: 'PostgreSQL index creation'
    });
}

/**
 * Shared job launcher. The three index jobs differ only in endpoint and which element they
 * report into, so the polling, button and error handling live in one place.
 */
async function startIndexJob(job) {
    console.log(`Starting ${job.description} job...`);

    updateComponentCount(job.componentId, '-');

    const button = document.querySelector(job.buttonSelector);
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage(`Starting ${job.description}...`);
    updateProgress(0, `Starting ${job.description}`);

    try {
        const response = await fetch(job.url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log(`${job.description} job started:`, result.jobId);
            updateMessage(`${job.description} job started successfully`);
            await pollIndexJobStatus(result.jobId, job);
        } else {
            throw new Error(result.message || `Failed to start ${job.description} job`);
        }
    } catch (error) {
        console.error(`Error starting ${job.description} job:`, error);
        updateMessage(`Failed to start ${job.description}: ` + error.message);
        updateProgress(0, `Failed to start ${job.description}`);
        resetIndexButton(job);
    }
}

async function pollIndexJobStatus(jobId, job) {
    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    updateProgress(100, `${job.description} completed`);
                    await getIndexJobResults(jobId, job);
                    resetIndexButton(job);
                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${job.description} failed`);
                    updateMessage(`${job.description} failed: ` + (status.error || 'Unknown error'));
                    resetIndexButton(job);
                    reject(new Error(status.error || `${job.description} failed`));
                } else {
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error(`Error polling ${job.description} job status:`, error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getIndexJobResults(jobId, job) {
    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status !== 'success') {
            throw new Error(result.message || 'Failed to get index job results');
        }

        if (job.kind === 'extraction') {
            displayIndexMetadata(result, job.database);
        } else {
            displayIndexCreationResults(result);
        }
    } catch (error) {
        console.error('Error getting index job results:', error);
        updateMessage('Error getting index job results: ' + error.message);
    }
}

function displayIndexMetadata(result, database) {
    const summary = result.summary || {};
    const count = summary.indexCount || 0;

    updateComponentCount(`${database}-indexes`, count);

    if (database === 'oracle') {
        updateMessage(`Oracle: found ${count} non-constraint indexes `
            + `(${summary.uniqueCount || 0} unique, ${summary.functionBasedCount || 0} function-based)`);
    } else {
        // Constraint-backed indexes are excluded on both sides, so the two counts are comparable
        updateMessage(`PostgreSQL: found ${count} non-constraint indexes (${summary.uniqueCount || 0} unique)`);
    }

    populateIndexList(summary, database);

    if (count > 0) {
        document.getElementById(`${database}-index-list`).style.display = 'block';
    }
}

/** Renders the index list grouped by schema, matching the table list. */
function populateIndexList(summary, database) {
    const itemsElement = document.getElementById(`${database}-index-items`);

    if (!itemsElement) {
        console.warn('Index items element not found');
        return;
    }

    itemsElement.innerHTML = '';

    const schemaCounts = summary.schemaIndexCounts || {};
    const indexes = summary.indexes || [];

    if (Object.keys(schemaCounts).length === 0) {
        const empty = document.createElement('div');
        empty.className = 'table-item';
        empty.textContent = 'No indexes found';
        empty.style.fontStyle = 'italic';
        empty.style.color = '#999';
        itemsElement.appendChild(empty);
        return;
    }

    Object.entries(schemaCounts).forEach(([schemaName, indexCount]) => {
        const schemaGroup = document.createElement('div');
        schemaGroup.className = 'table-schema-group';

        const schemaHeader = document.createElement('div');
        schemaHeader.className = 'table-schema-header';
        schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${indexCount} indexes)`;
        schemaHeader.onclick = () => toggleIndexSchemaGroup(database, schemaName);

        const schemaItems = document.createElement('div');
        schemaItems.className = 'table-items-list';
        schemaItems.id = `${database}-${schemaName}-indexes`;

        indexes
            .filter(index => index.schema === schemaName)
            .forEach(index => {
                const item = document.createElement('div');
                item.className = 'table-item';
                const flags = [];
                if (index.unique) {
                    flags.push('UNIQUE');
                }
                if (index.functionBased) {
                    flags.push('function-based');
                }
                const suffix = flags.length > 0 ? ` [${flags.join(', ')}]` : '';
                item.innerHTML = `${index.indexName} on ${index.tableName} (${index.keys})${suffix}`;
                schemaItems.appendChild(item);
            });

        schemaGroup.appendChild(schemaHeader);
        schemaGroup.appendChild(schemaItems);
        itemsElement.appendChild(schemaGroup);
    });
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

function resetIndexButton(job) {
    const button = document.querySelector(job.buttonSelector);
    if (button) {
        button.disabled = false;
        button.innerHTML = job.buttonLabel;
    }
}

function toggleIndexList(database) {
    const items = document.getElementById(`${database}-index-items`);
    const header = document.querySelector(`#${database}-index-list .table-list-header`);

    if (!items || !header) {
        console.warn(`Index list elements not found for database: ${database}`);
        return;
    }

    if (items.style.display === 'none') {
        items.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        items.style.display = 'none';
        header.classList.add('collapsed');
    }
}

function toggleIndexSchemaGroup(database, schemaName) {
    const items = document.getElementById(`${database}-${schemaName}-indexes`);
    if (!items) {
        return;
    }
    items.style.display = items.style.display === 'none' ? 'block' : 'none';
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
