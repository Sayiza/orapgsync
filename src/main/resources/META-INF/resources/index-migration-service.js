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
 * - toggleIndexList() / toggleIndexCreationResults(): UI toggles
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
    const indexes = summary.indexes || [];

    setDeferredList(`${database}-index-list`, `${database}-index-items`,
        () => renderSchemaGroups(indexes, index => {
            const flags = [];
            if (index.unique) flags.push('UNIQUE');
            if (index.functionBased) flags.push('function-based');
            const suffix = flags.length > 0 ? ` [${flags.join(', ')}]` : '';
            return `<div class="table-item">${escapeHtml(index.indexName)} on ${escapeHtml(index.tableName)} `
                 + `(${escapeHtml(index.keys)})${escapeHtml(suffix)}</div>`;
        }, { label: 'indexes', groupIdPrefix: `${database}-index-group` }),
        `Indexes (${formatCount(indexes.length)})`);
}

function displayIndexCreationResults(result) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount('postgres-indexes', summary.createdCount || 0);
    updateMessage(`PostgreSQL: created ${summary.createdCount} indexes, skipped ${summary.skippedCount}, `
        + `${summary.unsupportedCount} unsupported, ${summary.errorCount} errors`);

    setResultsPanel('postgres-index-creation', {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Unsupported', value: summary.unsupportedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'index outcomes',
        renderDetail: () => renderOutcomeSections([
            { title: 'Created Indexes', items: toIndexArray(summary.createdIndexes), renderItem: indexRow('created') },
            { title: 'Skipped Indexes', items: toIndexArray(summary.skippedIndexes), renderItem: indexRow('skipped') },
            { title: 'Unsupported Indexes', items: toIndexArray(summary.unsupportedIndexes), renderItem: indexRow('skipped') },
            { title: 'Failed Indexes', items: toIndexArray(summary.errors), renderItem: indexRow('error') }
        ], { jobId: result.jobId, label: 'indexes' })
    });
}

// One index outcome row. Every index carries a reason, so the row states why it ended up in
// the section it is in.
function indexRow(cssClass) {
    return item => {
        let html = `<div class="table-item ${cssClass}">`;
        html += `<strong>${escapeHtml(item.indexName)}</strong> on ${escapeHtml(item.tableName)} (${escapeHtml(item.keys)})`;
        if (item.reason) {
            html += ` \u2014 ${escapeHtml(item.reason)}`;
        }
        if (cssClass === 'error') {
            html += renderDeferredCode(item.sql);
        }
        html += '</div>';
        return html;
    };
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
    toggleDeferredList(`${database}-index-list`);
}

function toggleIndexCreationResults() {
    toggleResultsPanel('postgres-index-creation');
}

// ===== END INDEX MIGRATION FUNCTIONS =====
