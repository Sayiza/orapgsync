/**
 * Job Service Module
 *
 * This module provides generic job polling, status monitoring, and asynchronous job completion utilities.
 * It handles the common patterns for tracking job progress, waiting for job completion, and monitoring
 * UI state changes during long-running database operations.
 *
 * Key Functions:
 * - pollJobStatus(): Legacy polling function for table metadata extraction jobs
 * - pollJobUntilComplete(): Generic polling for any job type with automatic result fetching
 * - waitForJobCompletion(): UI-based polling that monitors progress indicators
 * - pollCountBadge(): Polls component count badges until valid values appear
 * - delay(): Simple promise-based delay utility
 */

/**
 * Poll job status for table metadata extraction (legacy function)
 * @param {string} jobId - The job ID to poll
 * @param {string} database - The database name ('oracle' or 'postgres')
 */
async function pollJobStatus(jobId, database = 'oracle') {
    console.log('Polling job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Job status:', result);

        // Update progress if available
        if (result.progress) {
            const percentage = result.progress.percentage;
            const currentTask = result.progress.currentTask || 'Processing...';
            const details = result.progress.details || '';

            updateProgress(percentage, currentTask);
            if (details) {
                updateMessage(details);
            }
        }

        // Check if job is complete
        if (result.isComplete) {
            if (result.status === 'COMPLETED') {
                console.log('Job completed successfully');
                updateProgress(100, 'Job completed successfully');
                updateMessage('Table metadata extraction completed');

                // Get job results
                await getJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('Job failed:', result.error);
                updateProgress(0, 'Job failed');
                updateMessage('Table extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-tables .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⟳';
            }
        } else {
            // Continue polling
            setTimeout(() => pollJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling job status:', error);
        updateMessage('Error checking job status: ' + error.message);
        updateProgress(0, 'Error checking job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-tables .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⟳';
        }
    }
}

/**
 * Poll job status until completion (generic for all job types)
 * @param {string} jobId - The job ID to poll
 * @param {string} database - The database name ('oracle' or 'postgres')
 * @param {string} jobType - The type of job ('schemas', 'objects', 'synonyms', etc.)
 * @returns {Promise<void>} Resolves when job completes, rejects on error
 */
async function pollJobUntilComplete(jobId, database, jobType) {
    console.log('Polling job status for:', jobId, 'database:', database, 'type:', jobType);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const result = await response.json();

                if (result.status === 'error') {
                    throw new Error(result.message);
                }

                console.log('Job status:', result);

                // Update progress if available
                if (result.progress) {
                    const percentage = result.progress.percentage;
                    const currentTask = result.progress.currentTask || 'Processing...';
                    const details = result.progress.details || '';

                    updateMessage(`${currentTask}: ${details}`);
                }

                // Check if job is complete
                if (result.isComplete) {
                    if (result.status === 'COMPLETED') {
                        console.log(`${jobType} extraction job completed successfully`);
                        updateMessage(`${database.charAt(0).toUpperCase() + database.slice(1)} ${jobType} extraction completed`);

                        // Get job results based on job type
                        if (jobType === 'schemas') {
                            await getSchemaJobResults(jobId, database);
                        } else if (jobType === 'objects') {
                            await getObjectDataTypeJobResults(jobId, database);
                        } else if (jobType === 'synonyms') {
                            await getSynonymJobResults(jobId, database);
                        }
                    } else if (result.status === 'FAILED') {
                        console.error(`${jobType} extraction job failed:`, result.error);
                        updateComponentCount(`${database}-${jobType}`, '!', 'error');
                        updateMessage(`${jobType} extraction failed: ${result.error || 'Unknown error'}`);
                    }

                    // Re-enable refresh button based on job type
                    const buttonSelector = jobType === 'schemas'
                        ? `#${database}-schemas .refresh-btn`
                        : `#${database}-${jobType} .refresh-btn`;
                    const button = document.querySelector(buttonSelector);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⟳';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }

            } catch (error) {
                console.error(`Error polling ${jobType} job status:`, error);
                updateComponentCount(`${database}-${jobType}`, '!', 'error');
                updateMessage(`Error checking ${jobType} job status: ${error.message}`);

                // Re-enable button
                const buttonSelector = jobType === 'schemas'
                    ? `#${database}-schemas .refresh-btn`
                    : `#${database}-${jobType} .refresh-btn`;
                const button = document.querySelector(buttonSelector);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = '⟳';
                }

                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

/**
 * Helper function to add delay between steps
 * @param {number} ms - Milliseconds to delay
 * @returns {Promise<void>} Resolves after the specified delay
 */
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

/**
 * Helper function to wait for async job completion
 * This monitors the UI state to determine when a job has finished
 * @param {string} jobDescription - Description of the job for logging
 * @returns {Promise<void>} Resolves when job completes (success or failure)
 */
async function waitForJobCompletion(jobDescription) {
    console.log(`Waiting for ${jobDescription} to complete...`);

    // Simple polling approach - wait for buttons to be re-enabled
    // which indicates job completion
    return new Promise((resolve) => {
        const maxWaitTime = 300000; // 5 minutes max wait
        const checkInterval = 1000; // Check every second
        let elapsedTime = 0;

        const intervalId = setInterval(() => {
            elapsedTime += checkInterval;

            // Check if we've exceeded max wait time
            if (elapsedTime >= maxWaitTime) {
                console.warn(`${jobDescription} exceeded max wait time`);
                clearInterval(intervalId);
                resolve();
            }

            // Check progress bar for completion indicators
            const progressStatus = document.querySelector('.progress-status');
            if (progressStatus) {
                const statusText = progressStatus.textContent.toLowerCase();

                // Look for completion keywords
                if (statusText.includes('completed') ||
                    statusText.includes('success') ||
                    statusText.includes('loaded') ||
                    statusText.includes('extracted')) {

                    console.log(`${jobDescription} completed`);
                    clearInterval(intervalId);
                    resolve();
                }

                // Also check for failure keywords
                if (statusText.includes('failed') ||
                    statusText.includes('error')) {

                    console.warn(`${jobDescription} failed or errored`);
                    clearInterval(intervalId);
                    resolve(); // Resolve anyway to continue workflow
                }
            }
        }, checkInterval);
    });
}
