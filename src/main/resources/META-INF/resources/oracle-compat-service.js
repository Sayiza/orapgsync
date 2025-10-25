/**
 * Oracle Compatibility Layer Service Module
 *
 * This module handles operations related to installing PostgreSQL equivalents
 * for Oracle built-in packages (DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB, etc.).
 *
 * Key Responsibilities:
 * - Installation: Install PostgreSQL functions that provide Oracle compatibility
 * - Verification: Verify that compatibility functions are correctly installed
 * - Display Operations: Render installation and verification results in the UI
 * - Job Management: Poll job status and handle completion for compatibility layer jobs
 *
 * Functions included:
 * - installOracleCompat(): Install Oracle compatibility layer
 * - verifyOracleCompat(): Verify Oracle compatibility layer installation
 * - pollOracleCompatJobStatus(): Poll compatibility layer job status
 * - getOracleCompatJobResults(): Retrieve job results
 * - displayOracleCompatInstallationResults(): Display installation results
 * - displayOracleCompatVerificationResults(): Display verification results
 * - toggleOracleCompatInstallationResults(): Toggle installation results visibility
 * - toggleOracleCompatVerificationResults(): Toggle verification results visibility
 */

// Oracle Compatibility Layer Installation Functions

// Install Oracle compatibility layer (starts the job)
async function installOracleCompat() {
    console.log('Starting Oracle compatibility layer installation job...');

    const button = document.querySelector('#postgres-oracle-compat .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳ Installing...';
    }

    updateMessage('Starting Oracle compatibility layer installation...');
    updateProgress(0, 'Starting Oracle compatibility layer installation');

    try {
        const response = await fetch('/api/oracle-compat/postgres/install', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle compatibility installation job started:', result.jobId);
            updateMessage('Oracle compatibility installation job started successfully');

            // Start polling for progress
            pollOracleCompatJobStatus(result.jobId, 'installation');
        } else {
            throw new Error(result.message || 'Failed to start Oracle compatibility installation job');
        }

    } catch (error) {
        console.error('Error starting Oracle compatibility installation job:', error);
        updateMessage('Failed to start Oracle compatibility installation: ' + error.message);
        updateProgress(0, 'Failed to start Oracle compatibility installation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Install Compatibility Layer';
        }
    }
}

// Verify Oracle compatibility layer installation (starts the verification job)
async function verifyOracleCompat() {
    console.log('Starting Oracle compatibility layer verification job...');

    const button = document.querySelector('#postgres-oracle-compat .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle compatibility layer verification...');
    updateProgress(0, 'Starting Oracle compatibility layer verification');

    try {
        const response = await fetch('/api/oracle-compat/postgres/verify', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle compatibility verification job started:', result.jobId);
            updateMessage('Oracle compatibility verification job started successfully');

            // Start polling for progress
            pollOracleCompatJobStatus(result.jobId, 'verification');
        } else {
            throw new Error(result.message || 'Failed to start Oracle compatibility verification job');
        }

    } catch (error) {
        console.error('Error starting Oracle compatibility verification job:', error);
        updateMessage('Failed to start Oracle compatibility verification: ' + error.message);
        updateProgress(0, 'Failed to start Oracle compatibility verification');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Poll job status for Oracle compatibility operations
async function pollOracleCompatJobStatus(jobId, jobType) {
    const maxAttempts = 200;
    const pollInterval = 1000;
    let attempts = 0;

    const poll = async () => {
        try {
            const response = await fetch(`/api/jobs/status/${jobId}`);
            const status = await response.json();

            console.log(`Job ${jobId} status:`, status.status, `(attempt ${attempts}/${maxAttempts})`);

            if (status.progress) {
                const progressPercent = status.progress.total > 0
                    ? Math.round((status.progress.current / status.progress.total) * 100)
                    : 0;

                updateProgress(
                    progressPercent,
                    status.progress.message || `Processing Oracle compatibility ${jobType}...`
                );
            }

            if (status.status === 'COMPLETED') {
                console.log(`Job ${jobId} completed successfully`);
                updateMessage(`Oracle compatibility ${jobType} completed successfully`);
                updateProgress(100, `Oracle compatibility ${jobType} completed`);

                // Fetch and display results
                await getOracleCompatJobResults(jobId, jobType);

                // Re-enable buttons
                const actionButton = document.querySelector('#postgres-oracle-compat .action-btn');
                const refreshButton = document.querySelector('#postgres-oracle-compat .refresh-btn');
                if (actionButton) {
                    actionButton.disabled = false;
                    actionButton.innerHTML = 'Install Compatibility Layer';
                }
                if (refreshButton) {
                    refreshButton.disabled = false;
                    refreshButton.innerHTML = '⚙';
                }

                return;

            } else if (status.status === 'FAILED') {
                console.error(`Job ${jobId} failed:`, status.error);
                updateMessage(`Oracle compatibility ${jobType} failed: ${status.error || 'Unknown error'}`);
                updateProgress(0, `Oracle compatibility ${jobType} failed`);

                // Re-enable buttons
                const actionButton = document.querySelector('#postgres-oracle-compat .action-btn');
                const refreshButton = document.querySelector('#postgres-oracle-compat .refresh-btn');
                if (actionButton) {
                    actionButton.disabled = false;
                    actionButton.innerHTML = 'Install Compatibility Layer';
                }
                if (refreshButton) {
                    refreshButton.disabled = false;
                    refreshButton.innerHTML = '⚙';
                }

                return;
            }

            // Continue polling if still in progress
            attempts++;
            if (attempts < maxAttempts) {
                setTimeout(poll, pollInterval);
            } else {
                console.warn(`Polling timeout for job ${jobId}`);
                updateMessage('Oracle compatibility operation timed out - please check manually');
                updateProgress(0, 'Operation timed out');

                // Re-enable buttons
                const actionButton = document.querySelector('#postgres-oracle-compat .action-btn');
                const refreshButton = document.querySelector('#postgres-oracle-compat .refresh-btn');
                if (actionButton) {
                    actionButton.disabled = false;
                    actionButton.innerHTML = 'Install Compatibility Layer';
                }
                if (refreshButton) {
                    refreshButton.disabled = false;
                    refreshButton.innerHTML = '⚙';
                }
            }

        } catch (error) {
            console.error(`Error polling job ${jobId}:`, error);
            updateMessage(`Error polling Oracle compatibility ${jobType}: ${error.message}`);

            // Re-enable buttons
            const actionButton = document.querySelector('#postgres-oracle-compat .action-btn');
            const refreshButton = document.querySelector('#postgres-oracle-compat .refresh-btn');
            if (actionButton) {
                actionButton.disabled = false;
                actionButton.innerHTML = 'Install Compatibility Layer';
            }
            if (refreshButton) {
                refreshButton.disabled = false;
                refreshButton.innerHTML = '⚙';
            }
        }
    };

    // Start polling
    poll();
}

// Get Oracle compatibility job results
async function getOracleCompatJobResults(jobId, jobType) {
    try {
        const response = await fetch(`/api/jobs/results/${jobId}`);
        const results = await response.json();

        console.log(`Oracle compatibility ${jobType} results:`, results);

        if (jobType === 'installation') {
            displayOracleCompatInstallationResults(results);
        } else if (jobType === 'verification') {
            displayOracleCompatVerificationResults(results);
        }

    } catch (error) {
        console.error(`Error fetching Oracle compatibility ${jobType} results:`, error);
        updateMessage(`Error fetching Oracle compatibility ${jobType} results: ${error.message}`);
    }
}

// Display Oracle compatibility installation results
function displayOracleCompatInstallationResults(results) {
    console.log('Displaying Oracle compatibility installation results:', results);

    // Update count badge
    const countBadge = document.querySelector('#postgres-oracle-compat .count-badge');
    if (countBadge) {
        const totalInstalled = (results.installedFull?.length || 0) +
                              (results.installedPartial?.length || 0) +
                              (results.installedStubs?.length || 0);
        countBadge.textContent = totalInstalled;
        countBadge.style.display = 'inline-block';
    }

    // Build detailed results HTML
    const totalInstalled = (results.installedFull?.length || 0) +
                          (results.installedPartial?.length || 0) +
                          (results.installedStubs?.length || 0);

    let html = `
        <div class="table-creation-summary">
            <p><strong>Total Functions:</strong> ${results.totalFunctions || 0}</p>
            <p><strong>Successfully Installed:</strong> ${totalInstalled}</p>
            <div class="summary-stats">
                <span class="stat-item created">Full Support: ${results.installedFull?.length || 0}</span>
                <span class="stat-item skipped">Partial Support: ${results.installedPartial?.length || 0}</span>
                <span class="stat-item">Stubs: ${results.installedStubs?.length || 0}</span>
    `;

    if (results.failed && results.failed.length > 0) {
        html += `<span class="stat-item errors">Failed: ${results.failed.length}</span>`;
    }

    html += `
            </div>
            <p style="margin-top: 0.5rem;"><strong>Execution Time:</strong> ${results.executionTimeMs || 0}ms</p>
        </div>
    `;

    // Full support functions
    if (results.installedFull && results.installedFull.length > 0) {
        html += '<div class="created-items-section"><h4>Full Support</h4><div class="table-items">';
        results.installedFull.forEach(fn => {
            html += `<div class="creation-item created">✓ ${fn}</div>`;
        });
        html += '</div></div>';
    }

    // Partial support functions
    if (results.installedPartial && results.installedPartial.length > 0) {
        html += '<div class="skipped-items-section"><h4>Partial Support (with limitations)</h4><div class="table-items">';
        results.installedPartial.forEach(fn => {
            html += `<div class="creation-item skipped">⚠ ${fn}</div>`;
        });
        html += '</div></div>';
    }

    // Stub functions
    if (results.installedStubs && results.installedStubs.length > 0) {
        html += '<div class="created-items-section"><h4>Stubs (minimal functionality)</h4><div class="table-items">';
        results.installedStubs.forEach(fn => {
            html += `<div class="creation-item">○ ${fn}</div>`;
        });
        html += '</div></div>';
    }

    // Failed functions
    if (results.failed && results.failed.length > 0) {
        html += '<div class="error-items-section"><h4>Failed</h4><div class="table-items">';
        results.errorMessages.forEach(msg => {
            html += `<div class="creation-item error">✗ ${msg}</div>`;
        });
        html += '</div></div>';
    }

    // Update details div
    const detailsDiv = document.getElementById('postgres-oracle-compat-installation-details');
    if (detailsDiv) {
        detailsDiv.innerHTML = html;
    }

    // Show results section
    const resultsSection = document.getElementById('postgres-oracle-compat-installation-results');
    if (resultsSection) {
        resultsSection.style.display = 'block';
    }
}

// Display Oracle compatibility verification results
function displayOracleCompatVerificationResults(results) {
    console.log('Displaying Oracle compatibility verification results:', results);

    // Update count badge
    const countBadge = document.querySelector('#postgres-oracle-compat .count-badge');
    if (countBadge) {
        countBadge.textContent = results.verified || 0;
        countBadge.style.display = 'inline-block';
    }

    // Build verification results HTML
    let html = `
        <div class="table-creation-summary">
            <p><strong>Total Expected:</strong> ${results.totalExpected || 0}</p>
            <div class="summary-stats">
                <span class="stat-item created">Verified: ${results.verified || 0}</span>
    `;

    if (results.missing > 0) {
        html += `<span class="stat-item skipped">Missing: ${results.missing}</span>`;
    }

    if (results.errors && results.errors.length > 0) {
        html += `<span class="stat-item errors">Errors: ${results.errors.length}</span>`;
    }

    html += `
            </div>
            <p style="margin-top: 0.5rem;"><strong>Execution Time:</strong> ${results.executionTimeMs || 0}ms</p>
        </div>
    `;

    // Error details
    if (results.errors && results.errors.length > 0) {
        html += '<div class="error-items-section"><h4>Verification Errors</h4><div class="table-items">';
        results.errors.forEach(error => {
            html += `<div class="creation-item error">✗ ${error}</div>`;
        });
        html += '</div></div>';
    }

    // Update details div
    const detailsDiv = document.getElementById('postgres-oracle-compat-verification-details');
    if (detailsDiv) {
        detailsDiv.innerHTML = html;
    }

    // Show results section
    const resultsSection = document.getElementById('postgres-oracle-compat-verification-results');
    if (resultsSection) {
        resultsSection.style.display = 'block';
    }
}

// Toggle installation results visibility
function toggleOracleCompatInstallationResults() {
    const details = document.getElementById('postgres-oracle-compat-installation-details');
    const header = document.querySelector('#postgres-oracle-compat-installation-results .table-creation-header');

    if (details && header) {
        const isVisible = details.style.display !== 'none';
        details.style.display = isVisible ? 'none' : 'block';

        const indicator = header.querySelector('.toggle-indicator');
        if (indicator) {
            indicator.textContent = isVisible ? '▶' : '▼';
        }
    }
}

// Toggle verification results visibility
function toggleOracleCompatVerificationResults() {
    const details = document.getElementById('postgres-oracle-compat-verification-details');
    const header = document.querySelector('#postgres-oracle-compat-verification-results .table-creation-header');

    if (details && header) {
        const isVisible = details.style.display !== 'none';
        details.style.display = isVisible ? 'none' : 'block';

        const indicator = header.querySelector('.toggle-indicator');
        if (indicator) {
            indicator.textContent = isVisible ? '▶' : '▼';
        }
    }
}
