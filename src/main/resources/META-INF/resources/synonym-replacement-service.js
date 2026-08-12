/**
 * Synonym Replacement Service Module
 *
 * This module handles PostgreSQL synonym replacement view creation operations.
 *
 * Oracle synonyms provide alternative names for database objects. PostgreSQL doesn't have
 * synonyms, so external applications (Java/JDBC) that reference objects through synonyms
 * will fail after migration. This module creates PostgreSQL views that emulate synonym
 * behavior: CREATE VIEW synonym_schema.synonym_name AS SELECT * FROM target_schema.target_name
 *
 * Key Responsibilities:
 * - Create synonym replacement views in PostgreSQL
 * - Display creation results with created/skipped/errors breakdown
 * - Job management for synonym replacement view creation
 */

// Create PostgreSQL synonym replacement views (starts the job)
async function createPostgresSynonymReplacementViews() {
    console.log('Starting PostgreSQL synonym replacement view creation job...');

    updateComponentCount("postgres-synonym-replacement", "-");

    const button = document.querySelector('#postgres-synonym-replacement .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL synonym replacement view creation...');
    updateProgress(0, 'Starting PostgreSQL synonym replacement view creation');

    try {
        const response = await fetch('/api/synonyms/postgres/replacement-views/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL synonym replacement view creation job started:', result.jobId);
            updateMessage('PostgreSQL synonym replacement view creation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollSynonymReplacementViewCreationJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL synonym replacement view creation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL synonym replacement view creation job:', error);
        updateMessage('Failed to start PostgreSQL synonym replacement view creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL synonym replacement view creation');
        updateComponentCount("postgres-synonym-replacement", "!", "error");

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Synonym Views';
        }
    }
}

async function pollSynonymReplacementViewCreationJobStatus(jobId) {
    console.log('Polling synonym replacement view creation job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log('Synonym replacement view creation job status:', status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log('Synonym replacement view creation job completed');
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleSynonymReplacementViewCreationJobComplete(result);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector('#postgres-synonym-replacement .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Synonym Views';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling synonym replacement view creation job status:', error);
                updateMessage('Error checking synonym replacement view creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                updateComponentCount("postgres-synonym-replacement", "!", "error");

                // Re-enable button
                const button = document.querySelector('#postgres-synonym-replacement .action-btn');
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Synonym Views';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleSynonymReplacementViewCreationJobComplete(result) {
    console.log('Synonym replacement view creation job results:', result);

    // Access counts from top-level result (these are provided by JobResource)
    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Synonym replacement view creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Synonym replacement view creation completed successfully: ${createdCount} views created, ${skippedCount} skipped`);
    } else {
        updateMessage(`Synonym replacement view creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update synonym replacement view creation results section
    displaySynonymReplacementViewCreationResults(result);
}

function displaySynonymReplacementViewCreationResults(result) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-synonym-replacement", summary.createdCount + summary.skippedCount + summary.errorCount);

    // createdViews and skippedSynonyms are plain strings ("synonym -> target"), not objects.
    const created = (Array.isArray(summary.createdViews) ? summary.createdViews : Object.values(summary.createdViews || {})).slice().sort();
    const skipped = (Array.isArray(summary.skippedSynonyms) ? summary.skippedSynonyms : Object.values(summary.skippedSynonyms || {})).slice().sort();

    setResultsPanel('postgres-synonym-replacement-creation', {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'synonym replacement views',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Synonym Replacement Views',
                items: created,
                renderItem: view => `<div class="table-item created">${escapeHtml(view)} \u2713</div>`
            },
            {
                title: 'Skipped Synonyms',
                items: skipped,
                renderItem: synonym => `<div class="table-item skipped">${escapeHtml(synonym)}</div>`
            },
            {
                title: 'Failed Synonym Replacement Views',
                items: toSortedArray(summary.errors, 'synonymName'),
                renderItem: e => `<div class="table-item error"><strong>${escapeHtml(e.synonymName)}</strong>: ${escapeHtml(e.errorMessage || '')}`
                                 + renderDeferredCode(e.sqlStatement) + `</div>`
            }
        ], { jobId: result.jobId, label: 'synonyms' })
    });
}

function toggleSynonymReplacementViewCreationResults() {
    toggleResultsPanel('postgres-synonym-replacement-creation');
}
