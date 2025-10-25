/**
 * Orchestration Service Module
 *
 * This module handles the full migration workflow orchestration and state reset operations.
 * It coordinates the complete end-to-end migration process from Oracle to PostgreSQL,
 * executing each step in sequence with proper error handling and progress tracking.
 *
 * Main Functions:
 * - startAll(): Orchestrates the complete 25-step migration workflow
 * - resetAll(): Resets all application state to initial conditions
 *
 * Dependencies:
 * This module depends on various functions defined in app.js including:
 * - Connection testing functions (testOracleConnection, testPostgresConnection)
 * - Extraction functions (loadOracleSchemas, loadOracleSynonyms, etc.)
 * - Creation functions (createPostgresSchemas, createPostgresObjectTypes, etc.)
 * - Transfer functions (transferData)
 * - Oracle compatibility functions (installOracleCompat)
 * - View implementation functions (createPostgresViewImplementation, verifyPostgresViewImplementation)
 * - UI update functions (updateOrchestrationProgress, updateMessage, etc.)
 */

// Reset all application state
async function resetAll() {
    console.log('Resetting all application state...');

    if (!confirm('Are you sure you want to reset all application state? This will clear all extracted metadata but will not affect database configurations.')) {
        return;
    }

    updateMessage('Resetting application state...');
    updateProgress(0, 'Resetting...');

    try {
        const response = await fetch('/api/state/reset');
        const result = await response.json();

        if (response.ok) {
            console.log('State reset successfully:', result);
            updateMessage('Application state reset successfully');
            updateProgress(0, 'Welcome to Ora Pg Sync - Ready to start');

            // Reset all UI elements to default state
            initializeInterface();

        } else {
            throw new Error(result.message || 'Failed to reset state');
        }

    } catch (error) {
        console.error('Error resetting state:', error);
        updateMessage('Failed to reset state: ' + error.message);
    }
}

// Start full sync - orchestrates the complete migration workflow
async function startAll() {
    console.log('Starting full migration orchestration...');

    // Capture start time for duration calculation
    const startTime = Date.now();

    // Show and initialize orchestration progress bar
    showOrchestrationProgress();
    updateOrchestrationProgress(0, 'Initializing migration...');

    try {
        // Step 1: Test Oracle connection (synchronous)
        updateOrchestrationProgress(1, 'Step 1/24: Testing Oracle connection...');
        await testOracleConnection();
        // Check if connection succeeded by looking for the "connected" status
        const oracleConnected = document.querySelector('#oracle-connection .status-indicator').classList.contains('connected');
        if (!oracleConnected) {
            throw new Error('Oracle connection test failed. Cannot proceed with migration.');
        }
        await delay(500);

        // Step 2: Test PostgreSQL connection (synchronous)
        updateOrchestrationProgress(2, 'Step 2/24: Testing PostgreSQL connection...');
        await testPostgresConnection();
        // Check if connection succeeded
        const postgresConnected = document.querySelector('#postgres-connection .status-indicator').classList.contains('connected');
        if (!postgresConnected) {
            throw new Error('PostgreSQL connection test failed. Cannot proceed with migration.');
        }
        await delay(500);

        // Step 3: Extract Oracle schemas
        updateOrchestrationProgress(3, 'Step 3/24: Extracting Oracle schemas...');
        await loadOracleSchemas();
        await pollCountBadge('oracle-schemas', { requirePositive: true, allowZero: false });
        updateOrchestrationProgress(4, 'Oracle schemas extracted');

        // Step 4: Create PostgreSQL schemas
        updateOrchestrationProgress(5, 'Step 4/24: Creating PostgreSQL schemas...');
        await createPostgresSchemas();
        await pollCountBadge('postgres-schemas', { requirePositive: true, allowZero: false });
        updateOrchestrationProgress(6, 'PostgreSQL schemas created');

        // Step 5: Extract Oracle synonyms
        updateOrchestrationProgress(7, 'Step 5/24: Extracting Oracle synonyms...');
        await loadOracleSynonyms();
        await pollCountBadge('oracle-synonyms', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(9, 'Oracle synonyms extracted');

        // Step 6: Extract Oracle object types
        updateOrchestrationProgress(10, 'Step 6/24: Extracting Oracle object types...');
        await loadOracleObjectTypes();
        await pollCountBadge('oracle-objects', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(12, 'Oracle object types extracted');

        // Step 7: Create PostgreSQL object types
        updateOrchestrationProgress(13, 'Step 7/24: Creating PostgreSQL object types...');
        await createPostgresObjectTypes();
        await pollCountBadge('postgres-objects', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(14, 'PostgreSQL object types created');

        // Step 8: Extract Oracle sequences
        updateOrchestrationProgress(15, 'Step 8/24: Extracting Oracle sequences...');
        await extractOracleSequences();
        await pollCountBadge('oracle-sequences', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(17, 'Oracle sequences extracted');

        // Step 9: Create PostgreSQL sequences
        updateOrchestrationProgress(18, 'Step 9/24: Creating PostgreSQL sequences...');
        await createPostgresSequences();
        await pollCountBadge('postgres-sequences', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(19, 'PostgreSQL sequences created');

        // Step 10: Extract Oracle table metadata
        updateOrchestrationProgress(20, 'Step 10/24: Extracting Oracle table metadata...');
        await extractTableMetadata();
        await pollCountBadge('oracle-tables', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(21, 'Oracle table metadata extracted');

        // Step 11: Create PostgreSQL tables (without constraints)
        updateOrchestrationProgress(22, 'Step 11/24: Creating PostgreSQL tables...');
        await createPostgresTables();
        await pollCountBadge('postgres-tables', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(23, 'PostgreSQL tables created');

        // Step 12: Extract Oracle row counts
        updateOrchestrationProgress(24, 'Step 12/24: Extracting Oracle row counts...');
        await extractOracleRowCounts();
        await pollCountBadge('oracle-data', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(25, 'Oracle row counts extracted');

        // Step 13: Transfer data from Oracle to PostgreSQL
        updateOrchestrationProgress(26, 'Step 13/24: Transferring data...');
        await transferData();
        await pollCountBadge('postgres-data', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(55, 'Data transfer completed');

        // Step 14: Extract Oracle constraints
        updateOrchestrationProgress(56, 'Step 14/24: Extracting Oracle constraints...');
        await extractOracleConstraints();
        await pollCountBadge('oracle-constraints', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(60, 'Oracle constraints extracted');

        // Step 15: Create PostgreSQL constraints
        updateOrchestrationProgress(61, 'Step 15/24: Creating PostgreSQL constraints...');
        await createPostgresConstraints();
        await pollCountBadge('postgres-constraints', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(71, 'Constraint creation completed');

        // Step 16: Create PostgreSQL FK indexes
        updateOrchestrationProgress(72, 'Step 16/24: Creating FK indexes...');
        await createPostgresFKIndexes();
        await pollCountBadge('postgres-fk-indexes', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(76, 'FK index creation completed');

        // Step 17: Extract Oracle view definitions
        updateOrchestrationProgress(77, 'Step 17/24: Extracting Oracle view definitions...');
        await extractOracleViews();
        await pollCountBadge('oracle-views', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(78, 'Oracle view definitions extracted');

        // Step 18: Create PostgreSQL view stubs
        updateOrchestrationProgress(79, 'Step 18/24: Creating PostgreSQL view stubs...');
        await createPostgresViewStubs();
        await pollCountBadge('postgres-views', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(80, 'View stub creation completed');

        // Step 19: Extract Oracle functions/procedures
        updateOrchestrationProgress(81, 'Step 19/24: Extracting Oracle functions and procedures...');
        await extractOracleFunctions();
        await pollCountBadge('oracle-functions', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(82, 'Oracle functions/procedures extracted');

        // Step 20: Create PostgreSQL function/procedure stubs
        updateOrchestrationProgress(83, 'Step 20/24: Creating PostgreSQL function stubs...');
        await createPostgresFunctionStubs();
        await pollCountBadge('postgres-functions', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(85, 'Function stub creation completed');

        // Step 21: Extract Oracle type methods
        updateOrchestrationProgress(87, 'Step 21/24: Extracting Oracle type methods...');
        await extractOracleTypeMethods();
        await pollCountBadge('oracle-type-methods', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(89, 'Oracle type methods extracted');

        // Step 22: Create PostgreSQL type method stubs
        updateOrchestrationProgress(90, 'Step 22/24: Creating PostgreSQL type method stubs...');
        await createPostgresTypeMethodStubs();
        await pollCountBadge('postgres-type-methods', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(91, 'Type method stub creation completed');

        // Step 23: Install Oracle compatibility layer
        updateOrchestrationProgress(92, 'Step 23/24: Installing Oracle compatibility layer...');
        await installOracleCompat();
        await pollCountBadge('postgres-oracle-compat', { requirePositive: false, allowZero: false });
        updateOrchestrationProgress(93, 'Oracle compatibility layer installed');

        // Step 24: Create PostgreSQL views (view implementation - replaces stubs with actual SQL)
        updateOrchestrationProgress(94, 'Step 24/24: Creating PostgreSQL views...');
        await createPostgresViewImplementation();
        await pollCountBadge('postgres-view-implementation', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(100, 'View implementation completed');

        //TODO upcoming steps: 25 Functions, 26 Type-Methods, 27 Triggers, ...
        
        // Calculate duration
        const endTime = Date.now();
        const durationMs = endTime - startTime;
        const durationSeconds = Math.floor(durationMs / 1000);
        const minutes = Math.floor(durationSeconds / 60);
        const seconds = durationSeconds % 60;
        const durationText = minutes > 0
            ? `${minutes} minute${minutes !== 1 ? 's' : ''} and ${seconds} second${seconds !== 1 ? 's' : ''}`
            : `${seconds} second${seconds !== 1 ? 's' : ''}`;

        // Complete
        console.log('Full migration orchestration completed successfully');
        const completionMessage = `Migration completed successfully! Total duration: ${durationText}`;
        updateOrchestrationProgress(100, completionMessage);

        // Hide orchestration progress bar after a short delay
        await delay(2000);
        hideOrchestrationProgress();
        await delay(100);
        updateMessage(completionMessage);


    } catch (error) {
        console.error('Migration orchestration failed:', error);
        updateOrchestrationProgress(-1, 'Migration failed: ' + error.message);
        updateMessage('Migration aborted: ' + error.message);

        // Hide orchestration progress bar after showing error
        await delay(5000);
        hideOrchestrationProgress();
    }
}

// Helper function to add delay between steps
function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Poll a count badge until it contains a valid value
 * @param {string} elementId - The ID of the component div (e.g., 'oracle-schemas')
 * @param {object} options - Configuration options
 * @param {boolean} options.requirePositive - If true, require count > 0 (throw error on 0)
 * @param {boolean} options.allowZero - If true, accept 0 as valid completion (no error)
 * @returns {Promise<number>} - The final count value
 * @throws {Error} - If error indicator found or invalid state detected
 */
async function pollCountBadge(elementId, options = {}) {
    const { requirePositive = false, allowZero = false } = options;
    const maxWaitTime = 300000; // 5 minutes
    const checkInterval = 500; // Check every 500ms
    let elapsedTime = 0;

    console.log(`Polling count badge for ${elementId}...`, options);

    return new Promise((resolve, reject) => {
        const intervalId = setInterval(() => {
            elapsedTime += checkInterval;

            // Timeout check
            if (elapsedTime >= maxWaitTime) {
                clearInterval(intervalId);
                reject(new Error(`Timeout waiting for ${elementId} count badge to update (waited ${maxWaitTime/1000}s)`));
                return;
            }

            // Find the count badge element
            const componentElement = document.getElementById(elementId);
            if (!componentElement) {
                clearInterval(intervalId);
                reject(new Error(`Component element not found: ${elementId}`));
                return;
            }

            const countBadge = componentElement.querySelector('.count-badge');
            if (!countBadge) {
                clearInterval(intervalId);
                reject(new Error(`Count badge not found in component: ${elementId}`));
                return;
            }

            const badgeText = countBadge.textContent.trim();
            console.log(`${elementId} badge: "${badgeText}"`);

            // Check for error state
            if (badgeText === '!') {
                clearInterval(intervalId);
                reject(new Error(`Error indicator found in ${elementId}. Step failed.`));
                return;
            }

            // Check if it's still loading (initial state)
            if (badgeText === '-') {
                // Still loading, keep polling
                return;
            }

            // Try to parse as number
            const count = parseInt(badgeText, 10);
            if (isNaN(count)) {
                // Not a number and not "-" or "!" - unexpected state
                console.warn(`Unexpected badge text in ${elementId}: "${badgeText}"`);
                return; // Keep polling
            }

            // We have a valid number
            console.log(`${elementId} completed with count: ${count}`);

            // Check abort conditions based on options
            if (requirePositive && count === 0) {
                clearInterval(intervalId);
                reject(new Error(`${elementId} returned 0 items. Cannot proceed (requirePositive=true).`));
                return;
            }

            if (!allowZero && count === 0 && !requirePositive) {
                // This case shouldn't happen with current logic, but handle it anyway
                clearInterval(intervalId);
                reject(new Error(`${elementId} returned 0 items unexpectedly.`));
                return;
            }

            // Success! Valid count received
            clearInterval(intervalId);
            resolve(count);
        }, checkInterval);
    });
}
