/**
 * Synonym Service Module
 *
 * This module handles Oracle synonym extraction and display operations.
 * It provides functions for:
 * - Extracting Oracle synonyms via job-based API
 * - Retrieving and processing synonym job results
 * - Displaying synonyms grouped by schema with collapsible UI
 * - Managing synonym list visibility and interactions
 */

// Extract Oracle synonyms using job-based approach
async function loadOracleSynonyms() {
    console.log('Starting Oracle synonym extraction job...');
    updateMessage('Starting Oracle synonym extraction...');

    updateComponentCount("oracle-synonyms", "-");

    try {
        // Start the job
        const startResponse = await fetch('/api/transfer/oracle/synonyms', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('Oracle synonym extraction job started:', startResult.jobId);
            updateMessage('Oracle synonym extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#oracle-synonyms .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'oracle', 'synonyms');

        } else {
            updateComponentCount('oracle-synonyms', '!', 'error');
            updateMessage('Failed to start Oracle synonym extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting Oracle synonym extraction:', error);
        updateComponentCount('oracle-synonyms', '!', 'error');
        updateMessage('Error starting Oracle synonym extraction: ' + error.message);
    }
}

// Get synonym job results and display them
async function getSynonymJobResults(jobId, database) {
    console.log('Getting synonym job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Synonym job results:', result);

            // The job returns a list of SynonymMetadata, we need to group by schema
            const synonyms = result.result || [];
            const synonymsBySchema = {};

            synonyms.forEach(synonym => {
                if (!synonymsBySchema[synonym.owner]) {
                    synonymsBySchema[synonym.owner] = [];
                }
                synonymsBySchema[synonym.owner].push(synonym);
            });

            // Update component count
            updateComponentCount(`${database}-synonyms`, synonyms.length);

            // Populate the synonym list
            populateSynonymList(database, synonymsBySchema);

            // Show the synonym list if we have synonyms
            if (synonyms.length > 0) {
                document.getElementById(`${database}-synonym-list`).style.display = 'block';
            }

            updateMessage(`Loaded ${synonyms.length} ${database} synonyms`);
        } else {
            throw new Error(result.message || 'Failed to get synonym job results');
        }

    } catch (error) {
        console.error('Error getting synonym job results:', error);
        updateComponentCount(`${database}-synonyms`, '!', 'error');
        updateMessage(`Error getting synonym results: ${error.message}`);
    }
}

// Populate synonym list with synonyms grouped by schema
function populateSynonymList(database, synonymsBySchema) {
    const synonyms = [];
    Object.entries(synonymsBySchema || {}).forEach(([schemaName, list]) => {
        (list || []).forEach(synonym => synonyms.push(Object.assign({ schema: schemaName }, synonym)));
    });

    setDeferredList(`${database}-synonym-list`, `${database}-synonym-items`,
        () => renderSchemaGroups(synonyms, synonym => {
            const target = `${synonym.tableOwner}.${synonym.tableName}`;
            let html = `<div class="synonym-item">${escapeHtml(synonym.synonymName)} → ${escapeHtml(target)}`;
            if (synonym.dbLink) {
                html += `<span class="remote-indicator" style="color: #ff9800; font-style: italic;"> @${escapeHtml(synonym.dbLink)}</span>`;
            }
            return html + '</div>';
        }, { label: 'synonyms', groupIdPrefix: `${database}-synonym-group` }),
        `Synonyms (${formatCount(synonyms.length)})`);
}

function toggleSynonymList(database) {
    toggleDeferredList(`${database}-synonym-list`);
}


