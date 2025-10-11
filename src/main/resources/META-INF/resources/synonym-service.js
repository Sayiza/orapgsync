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
        const startResponse = await fetch('/api/jobs/oracle/synonym/extract', {
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
    const synonymItemsElement = document.getElementById(`${database}-synonym-items`);

    if (!synonymItemsElement) {
        console.warn(`Synonym items element not found for database: ${database}`);
        return;
    }

    // Clear existing items
    synonymItemsElement.innerHTML = '';

    if (synonymsBySchema && Object.keys(synonymsBySchema).length > 0) {
        Object.entries(synonymsBySchema).forEach(([schemaName, synonyms]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'synonym-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'synonym-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${synonyms.length})`;
            schemaHeader.onclick = () => toggleSynonymSchemaGroup(database, schemaName);

            const synonymItems = document.createElement('div');
            synonymItems.className = 'synonym-items-inner';
            synonymItems.id = `${database}-${schemaName}-synonyms`;

            if (synonyms && synonyms.length > 0) {
                synonyms.forEach(synonym => {
                    const synonymItem = document.createElement('div');
                    synonymItem.className = 'synonym-item';
                    const target = `${synonym.tableOwner}.${synonym.tableName}`;
                    synonymItem.innerHTML = `${synonym.synonymName} → ${target}`;

                    // Add remote indicator if applicable
                    if (synonym.dbLink) {
                        const remoteIndicator = document.createElement('span');
                        remoteIndicator.className = 'remote-indicator';
                        remoteIndicator.textContent = ` @${synonym.dbLink}`;
                        remoteIndicator.style.color = '#ff9800';
                        remoteIndicator.style.fontStyle = 'italic';
                        synonymItem.appendChild(remoteIndicator);
                    }

                    synonymItems.appendChild(synonymItem);
                });
            } else {
                const noSynonymsItem = document.createElement('div');
                noSynonymsItem.className = 'synonym-item';
                noSynonymsItem.textContent = 'No synonyms found';
                noSynonymsItem.style.fontStyle = 'italic';
                noSynonymsItem.style.color = '#999';
                synonymItems.appendChild(noSynonymsItem);
            }

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(synonymItems);
            synonymItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noSynonymsGroup = document.createElement('div');
        noSynonymsGroup.className = 'synonym-schema-group';
        noSynonymsGroup.innerHTML = `
            <div class="synonym-item" style="font-style: italic; color: #999;">
                No synonyms found
            </div>
        `;
        synonymItemsElement.appendChild(noSynonymsGroup);
    }
}

// Toggle synonym list visibility
function toggleSynonymList(database) {
    const synonymListElement = document.getElementById(`${database}-synonym-list`);
    if (synonymListElement) {
        const isVisible = synonymListElement.style.display !== 'none';
        synonymListElement.style.display = isVisible ? 'none' : 'block';

        // Update toggle indicator
        const header = synonymListElement.querySelector('.synonym-list-header');
        if (header) {
            const indicator = header.querySelector('.toggle-indicator');
            if (indicator) {
                indicator.textContent = isVisible ? '▶' : '▼';
            }
        }
    }
}

// Toggle synonym schema group visibility
function toggleSynonymSchemaGroup(database, schemaName) {
    const synonymItemsElement = document.getElementById(`${database}-${schemaName}-synonyms`);
    if (synonymItemsElement) {
        const isVisible = synonymItemsElement.style.display !== 'none';
        synonymItemsElement.style.display = isVisible ? 'none' : 'block';

        // Update toggle indicator
        const header = synonymItemsElement.previousElementSibling;
        if (header && header.classList.contains('synonym-schema-header')) {
            const indicator = header.querySelector('.toggle-indicator');
            if (indicator) {
                indicator.textContent = isVisible ? '▶' : '▼';
            }
        }
    }
}
