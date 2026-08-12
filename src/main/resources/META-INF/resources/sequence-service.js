/**
 * Sequence Service Module
 *
 * This module handles all sequence-related operations for the Oracle-to-PostgreSQL migration tool.
 * It provides functionality for:
 * - Extracting sequence metadata from Oracle and PostgreSQL databases
 * - Creating sequences in PostgreSQL based on Oracle sequence definitions
 * - Polling job status for extraction and creation operations
 * - Displaying sequence lists and creation results in the UI
 * - Managing UI interactions for sequence-related components
 *
 * Functions included:
 * - extractOracleSequences(): Initiates Oracle sequence extraction job
 * - extractPostgresSequences(): Initiates PostgreSQL sequence extraction job
 * - createPostgresSequences(): Initiates PostgreSQL sequence creation job
 * - pollSequenceJobStatus(): Monitors sequence extraction job progress
 * - pollSequenceCreationJobStatus(): Monitors sequence creation job progress
 * - getSequenceJobResults(): Retrieves and displays extraction results
 * - getSequenceCreationResults(): Retrieves and displays creation results
 * - populateSequenceList(): Populates UI with extracted sequences grouped by schema
 * - displaySequenceCreationResults(): Displays detailed creation results
 * - toggleSequenceList(): Toggles visibility of sequence list panels
 * - toggleSequenceCreationResults(): Toggles visibility of creation results panels
 */

// ===== SEQUENCE FUNCTIONS =====

async function extractOracleSequences() {
    console.log('Starting Oracle sequence extraction job...');

    const button = document.querySelector('#oracle-sequences .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle sequence extraction...');
    updateProgress(0, 'Starting Oracle sequence extraction');

    try {
        const response = await fetch('/api/sequences/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle sequence extraction job started:', result.jobId);
            updateMessage('Oracle sequence extraction job started successfully');

            // Start polling for progress
            await pollSequenceJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle sequence extraction job');
        }
    } catch (error) {
        console.error('Error starting Oracle sequence extraction job:', error);
        updateMessage('Failed to start Oracle sequence extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle sequence extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function extractPostgresSequences() {
    console.log('Starting PostgreSQL sequence extraction job...');

    const button = document.querySelector('#postgres-sequences .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL sequence extraction...');
    updateProgress(0, 'Starting PostgreSQL sequence extraction');

    try {
        const response = await fetch('/api/sequences/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL sequence extraction job started:', result.jobId);
            updateMessage('PostgreSQL sequence extraction job started successfully');

            // Start polling for progress
            await pollSequenceJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL sequence extraction job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL sequence extraction job:', error);
        updateMessage('Failed to start PostgreSQL sequence extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL sequence extraction');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

async function createPostgresSequences() {
    console.log('Starting PostgreSQL sequence creation job...');

    updateComponentCount("postgres-sequences", "-");

    const button = document.querySelector('#postgres-sequences .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL sequence creation...');
    updateProgress(0, 'Starting PostgreSQL sequence creation');

    try {
        const response = await fetch('/api/sequences/postgres/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL sequence creation job started:', result.jobId);
            updateMessage('PostgreSQL sequence creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollSequenceCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL sequence creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL sequence creation job:', error);
        updateMessage('Failed to start PostgreSQL sequence creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL sequence creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Sequences';
        }
    }
}

async function pollSequenceJobStatus(jobId, database) {
    console.log(`Polling sequence job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Sequence job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Sequence extraction completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} sequence extraction completed`);

                    // Get job results and update the UI
                    await getSequenceJobResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-sequences .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} sequence extraction failed`);
                    updateMessage(`${database.toUpperCase()} sequence extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-sequences .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    reject(new Error(status.error || 'Sequence extraction failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling sequence job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function pollSequenceCreationJobStatus(jobId, database) {
    console.log(`Polling sequence creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                console.log(`Sequence creation job status for ${database}:`, status);

                // Update progress bar
                if (status.progress !== undefined) {
                    updateProgress(status.progress.percentage, status.progress.currentTask || 'Processing...');
                }

                if (status.status === 'COMPLETED') {
                    console.log(`Sequence creation completed for ${database}:`, status);
                    updateProgress(100, `${database.toUpperCase()} sequence creation completed`);

                    // Get job results and display
                    await getSequenceCreationResults(jobId, database);

                    // Re-enable button
                    const button = document.querySelector(`#${database}-sequences .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Sequences';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} sequence creation failed`);
                    updateMessage(`${database.toUpperCase()} sequence creation failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-sequences .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Sequences';
                    }

                    reject(new Error(status.error || 'Sequence creation failed'));
                } else {
                    // Still processing
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling sequence creation job status:', error);
                reject(error);
            }
        };

        pollOnce();
    });
}

async function getSequenceJobResults(jobId, database) {
    console.log('Getting sequence job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Sequence job results:', result);

            // Update badge count
            const sequenceCount = result.sequenceCount || 0;
            updateComponentCount(`${database}-sequences`, sequenceCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            if (result.summary && result.summary.message) {
                updateMessage(`${databaseName}: ${result.summary.message}`);
            } else {
                updateMessage(`Extracted ${sequenceCount} ${databaseName} sequences`);
            }

            // Populate sequence list UI
            populateSequenceList(result, database);

            // Show sequence list if there are sequences
            if (sequenceCount > 0) {
                document.getElementById(`${database}-sequence-list`).style.display = 'block';
            }

        } else {
            throw new Error(result.message || 'Failed to get sequence job results');
        }

    } catch (error) {
        console.error('Error getting sequence job results:', error);
        updateMessage('Error getting sequence results: ' + error.message);
        updateComponentCount(`${database}-sequences`, '-', 'error');
    }
}

async function getSequenceCreationResults(jobId, database) {
    console.log('Getting sequence creation job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Sequence creation job results:', result);

            // Display the creation results
            displaySequenceCreationResults(result, database);

            // Update badge count
            const sequenceCount = result.createdCount || 0;
            updateComponentCount(`${database}-sequences`, sequenceCount);

            // Show success message
            const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
            updateMessage(`${databaseName}: Created ${result.createdCount} sequences, skipped ${result.skippedCount}, ${result.errorCount} errors`);

        } else {
            throw new Error(result.message || 'Failed to get sequence creation results');
        }

    } catch (error) {
        console.error('Error getting sequence creation results:', error);
        updateMessage('Error getting sequence creation results: ' + error.message);
    }
}

function populateSequenceList(result, database) {
    const sequences = result.result || [];

    setDeferredList(`${database}-sequence-list`, `${database}-sequence-items`,
        () => renderSchemaGroups(sequences,
            seq => `<div class="table-item">${escapeHtml(seq.sequenceName)}</div>`,
            { label: 'sequences', groupIdPrefix: `${database}-sequence-group` }),
        `Sequences (${formatCount(sequences.length)})`);
}

function toggleSequenceList(database) {
    toggleDeferredList(`${database}-sequence-list`);
}

function displaySequenceCreationResults(result, database) {
    const summary = result.summary;
    if (!summary) return;

    updateComponentCount("postgres-sequences", summary.createdCount + summary.skippedCount + summary.errorCount);

    setResultsPanel(`${database}-sequence-creation`, {
        summaryHtml: renderSummaryStats([
            { label: 'Created', value: summary.createdCount, cssClass: 'created' },
            { label: 'Skipped', value: summary.skippedCount, cssClass: 'skipped' },
            { label: 'Errors', value: summary.errorCount, cssClass: 'errors' }
        ]),
        detailLabel: 'created sequences',
        renderDetail: () => renderOutcomeSections([
            {
                title: 'Created Sequences',
                items: toSortedArray(summary.createdSequences, 'sequenceName'),
                cssClass: 'created', nameKey: 'sequenceName', suffix: ' \u2713'
            },
            {
                title: 'Skipped Sequences (already exist)',
                items: toSortedArray(summary.skippedSequences, 'sequenceName'),
                cssClass: 'skipped', nameKey: 'sequenceName'
            },
            {
                title: 'Failed Sequences',
                items: toSortedArray(summary.errors, 'sequenceName'),
                cssClass: 'error', nameKey: 'sequenceName', showError: true
            }
        ], { jobId: result.jobId, label: 'sequences' })
    });
}

function toggleSequenceCreationResults() {
    toggleResultsPanel('postgres-sequence-creation');
}

// ===== END SEQUENCE FUNCTIONS =====
