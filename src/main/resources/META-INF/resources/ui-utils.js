// UI Utility Functions
// Handles all UI updates: status indicators, count badges, progress bars, messages

// Initialize the interface with default states
function initializeInterface() {
    updateConnectionStatus('oracle', 'disconnected', 'Not connected');
    updateConnectionStatus('postgres', 'disconnected', 'Not connected');

    updateComponentCount('oracle-schemas', '-');
    updateComponentCount('postgres-schemas', '-');
    updateComponentCount('oracle-synonyms', '-');
    updateComponentCount('oracle-objects', '-');
    updateComponentCount('postgres-objects', '-');
    updateComponentCount('oracle-tables', '-');
    updateComponentCount('postgres-tables', '-');
    updateComponentCount('oracle-sequences', '-');
    updateComponentCount('postgres-sequences', '-');
    updateComponentCount('oracle-data', '-');
    updateComponentCount('postgres-data', '-');
    updateComponentCount('oracle-views', '-');
    updateComponentCount('postgres-views', '-');
}

// Update connection status for Oracle or PostgreSQL
function updateConnectionStatus(database, status, message) {
    const connectionElement = document.getElementById(`${database}-connection`);
    if (!connectionElement) return;

    const indicator = connectionElement.querySelector('.status-indicator');
    const text = connectionElement.querySelector('.status-text');
    const button = connectionElement.querySelector('.test-connection-btn');

    // Remove all status classes
    indicator.classList.remove('connected', 'disconnected', 'connecting');
    indicator.classList.add(status);

    text.textContent = `Connection: ${message}`;

    // Update button state based on connection status
    if (status === 'connecting') {
        button.disabled = true;
        button.textContent = 'Testing...';
    } else {
        button.disabled = false;
        button.textContent = 'Test';
    }
}

// Update component count badges
function updateComponentCount(elementId, count, status = 'default') {
    const element = document.getElementById(elementId);
    if (!element) return;

    const badge = element.querySelector('.count-badge');
    if (!badge) return;

    badge.textContent = count;

    // Remove all status classes
    badge.classList.remove('success', 'warning', 'error');

    // Add appropriate status class
    if (status !== 'default' && status !== '') {
        badge.classList.add(status);
    }
}

// Update progress bar
function updateProgress(percentage, status) {
    const progressFill = document.querySelector('.progress-fill');
    const progressStatus = document.querySelector('.progress-status');

    if (progressFill) {
        progressFill.style.width = `${percentage}%`;
    }

    if (progressStatus) {
        progressStatus.textContent = status;
    }
}

// Update orchestration progress bar (for full job only)
function updateOrchestrationProgress(percentage, status) {
    const progressFill = document.querySelector('.orchestration-progress-fill');
    const progressStatus = document.querySelector('.orchestration-progress-status');

    if (progressFill) {
        progressFill.style.width = `${percentage}%`;
    }

    if (progressStatus) {
        progressStatus.textContent = status;
    }
}

// Show orchestration progress bar
function showOrchestrationProgress() {
    const progressBar = document.querySelector('.orchestration-progress');
    if (progressBar) {
        progressBar.style.display = 'block';
    }
}

// Hide orchestration progress bar
function hideOrchestrationProgress() {
    const progressBar = document.querySelector('.orchestration-progress');
    if (progressBar) {
        progressBar.style.display = 'none';
    }
}

// Update status message in progress bar
function updateMessage(message) {
    const messageElement = document.querySelector('.progress-message');
    if (messageElement) {
        messageElement.textContent = message;
    }
}

// Utility function to simulate migration progress (for testing/demo purposes)
function simulateMigrationProgress() {
    let progress = 0;
    const interval = setInterval(() => {
        progress += 10;
        updateProgress(progress, `Processing: ${progress}%`);

        if (progress >= 100) {
            clearInterval(interval);
            updateMessage('Migration completed successfully');
        }
    }, 1000);
}
