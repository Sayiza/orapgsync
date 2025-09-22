// Oracle to PostgreSQL Migration Tool Frontend
document.addEventListener('DOMContentLoaded', function() {
    console.log('Migration tool frontend initialized');
    initializeInterface();
});

// Initialize the interface with default states
function initializeInterface() {
    updateConnectionStatus('oracle', 'disconnected', 'Not connected');
    updateConnectionStatus('postgres', 'disconnected', 'Not connected');

    updateComponentCount('oracle-objects', '-');
    updateComponentCount('postgres-objects', '-');
    updateComponentCount('oracle-tables', '-');
    updateComponentCount('postgres-tables', '-');
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

    text.textContent = message;

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

// Update message bar
function updateMessage(message, type = 'info') {
    const messageContent = document.querySelector('.message-content');
    const messageBar = document.querySelector('.message-bar-section');

    if (messageContent) {
        messageContent.textContent = message;
    }

    if (messageBar) {
        // Remove existing type classes
        messageBar.classList.remove('info', 'success', 'warning', 'error');
        messageBar.classList.add(type);

        // Update border color based on type
        const colors = {
            info: '#1976d2',
            success: '#4CAF50',
            warning: '#ff9800',
            error: '#f44336'
        };

        messageBar.style.borderLeftColor = colors[type] || colors.info;
    }
}

// Test Oracle connection
async function testOracleConnection() {
    console.log('Testing Oracle connection...');

    updateConnectionStatus('oracle', 'connecting', 'Testing connection...');
    updateMessage('Testing Oracle database connection...', 'info');

    try {
        // TODO: Implement actual API call to test Oracle connection
        // const response = await fetch('/api/test-oracle-connection');
        // const result = await response.json();

        // Simulate API call for now
        await new Promise(resolve => setTimeout(resolve, 2000));

        // Mock successful connection for demonstration
        updateConnectionStatus('oracle', 'connected', 'Connected successfully');
        updateMessage('Oracle connection established successfully', 'success');

        // Mock some data discovery
        setTimeout(() => {
            updateComponentCount('oracle-objects', '15');
            updateComponentCount('oracle-tables', '42');
            updateComponentCount('oracle-data', '125.3k');
            updateComponentCount('oracle-views', '8');
        }, 500);

    } catch (error) {
        console.error('Oracle connection failed:', error);
        updateConnectionStatus('oracle', 'disconnected', 'Connection failed');
        updateMessage('Failed to connect to Oracle database: ' + error.message, 'error');
    }
}

// Test PostgreSQL connection
async function testPostgresConnection() {
    console.log('Testing PostgreSQL connection...');

    updateConnectionStatus('postgres', 'connecting', 'Testing connection...');
    updateMessage('Testing PostgreSQL database connection...', 'info');

    try {
        // TODO: Implement actual API call to test PostgreSQL connection
        // const response = await fetch('/api/test-postgres-connection');
        // const result = await response.json();

        // Simulate API call for now
        await new Promise(resolve => setTimeout(resolve, 1500));

        // Mock successful connection for demonstration
        updateConnectionStatus('postgres', 'connected', 'Connected successfully');
        updateMessage('PostgreSQL connection established successfully', 'success');

        // Mock some initial state
        setTimeout(() => {
            updateComponentCount('postgres-objects', '0');
            updateComponentCount('postgres-tables', '0');
            updateComponentCount('postgres-data', '0');
            updateComponentCount('postgres-views', '0');
        }, 500);

    } catch (error) {
        console.error('PostgreSQL connection failed:', error);
        updateConnectionStatus('postgres', 'disconnected', 'Connection failed');
        updateMessage('Failed to connect to PostgreSQL database: ' + error.message, 'error');
    }
}

// Utility function to simulate migration progress
function simulateMigrationProgress() {
    let progress = 0;
    const interval = setInterval(() => {
        progress += Math.random() * 10;
        if (progress >= 100) {
            progress = 100;
            clearInterval(interval);
            updateProgress(progress, 'Migration completed successfully');
            updateMessage('All database components have been migrated successfully', 'success');
        } else {
            updateProgress(progress, `Migrating... ${Math.round(progress)}% complete`);
        }
    }, 500);
}