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

// Update status message in progress bar
function updateMessage(message) {
    const progressStatus = document.querySelector('.progress-status');
    if (progressStatus) {
        progressStatus.textContent = message;
    }
}

// Test Oracle connection
async function testOracleConnection() {
    console.log('Testing Oracle connection...');

    updateConnectionStatus('oracle', 'connecting', 'Testing connection...');
    updateMessage('Testing Oracle database connection...');

    try {
        // TODO: Implement actual API call to test Oracle connection
        // const response = await fetch('/api/test-oracle-connection');
        // const result = await response.json();

        // Simulate API call for now
        await new Promise(resolve => setTimeout(resolve, 2000));

        // Mock successful connection for demonstration
        updateConnectionStatus('oracle', 'connected', 'Connected successfully');
        updateMessage('Oracle connection established successfully');

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
        updateMessage('Failed to connect to Oracle database: ' + error.message);
    }
}

// Test PostgreSQL connection
async function testPostgresConnection() {
    console.log('Testing PostgreSQL connection...');

    updateConnectionStatus('postgres', 'connecting', 'Testing connection...');
    updateMessage('Testing PostgreSQL database connection...');

    try {
        // TODO: Implement actual API call to test PostgreSQL connection
        // const response = await fetch('/api/test-postgres-connection');
        // const result = await response.json();

        // Simulate API call for now
        await new Promise(resolve => setTimeout(resolve, 1500));

        // Mock successful connection for demonstration
        updateConnectionStatus('postgres', 'connected', 'Connected successfully');
        updateMessage('PostgreSQL connection established successfully');

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
        updateMessage('Failed to connect to PostgreSQL database: ' + error.message);
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
            updateMessage('All database components have been migrated successfully');
        } else {
            updateProgress(progress, `Migrating... ${Math.round(progress)}% complete`);
        }
    }, 500);
}

// Configuration Management Functions

// localStorage key for configuration
const CONFIG_STORAGE_KEY = 'orapgsync-config';

// Load configuration with localStorage priority
async function loadConfiguration() {
    console.log('Loading configuration...');
    updateMessage('Loading configuration...');

    try {
        // Check localStorage first
        const savedConfig = loadConfigurationFromLocalStorage();
        if (savedConfig) {
            console.log('Configuration loaded from localStorage:', savedConfig);
            populateConfigurationForm(savedConfig);
            updateMessage('Configuration loaded from local storage');
            return;
        }

        // If no localStorage config, load from backend
        console.log('No local configuration found, loading from backend...');
        const response = await fetch('/api/config');
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const config = await response.json();
        console.log('Configuration loaded from backend:', config);

        // Populate form fields with loaded configuration
        populateConfigurationForm(config);
        updateMessage('Configuration loaded from server defaults');

    } catch (error) {
        console.error('Failed to load configuration:', error);
        updateMessage('Failed to load configuration: ' + error.message);
    }
}

// Load configuration from localStorage
function loadConfigurationFromLocalStorage() {
    try {
        const configString = localStorage.getItem(CONFIG_STORAGE_KEY);
        if (configString) {
            return JSON.parse(configString);
        }
    } catch (error) {
        console.error('Error loading configuration from localStorage:', error);
    }
    return null;
}

// Save configuration to localStorage
function saveConfigurationToLocalStorage(config) {
    try {
        localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(config));
        console.log('Configuration saved to localStorage');
    } catch (error) {
        console.error('Error saving configuration to localStorage:', error);
    }
}

// Clear configuration from localStorage
function clearConfigurationFromLocalStorage() {
    try {
        localStorage.removeItem(CONFIG_STORAGE_KEY);
        console.log('Configuration cleared from localStorage');
    } catch (error) {
        console.error('Error clearing configuration from localStorage:', error);
    }
}

// Populate form fields with configuration data
function populateConfigurationForm(config) {
    // Checkbox field
    const allSchemasCheckbox = document.getElementById('do-all-schemas');
    if (allSchemasCheckbox) {
        allSchemasCheckbox.checked = config['do.all-schemas'] === true;
    }

    // Text fields mapping
    const fieldMappings = {
        'do-only-test-schema': 'do.only-test-schema',
        'oracle-url': 'oracle.url',
        'oracle-user': 'oracle.user',
        'oracle-password': 'oracle.password',
        'java-generated-package-name': 'java.generated-package-name',
        'path-target-project-java': 'path.target-project-java',
        'path-target-project-resources': 'path.target-project-resources',
        'path-target-project-postgre': 'path.target-project-postgre',
        'postgre-url': 'postgre.url',
        'postgre-username': 'postgre.username',
        'postgre-password': 'postgre.password'
    };

    Object.entries(fieldMappings).forEach(([fieldId, configKey]) => {
        const field = document.getElementById(fieldId);
        if (field && config[configKey] !== undefined) {
            field.value = config[configKey];
        }
    });
}

// Save configuration to backend and localStorage
async function saveConfiguration() {
    console.log('Saving configuration...');

    const saveButton = document.getElementById('save-config-btn');
    if (saveButton) {
        saveButton.disabled = true;
        saveButton.textContent = 'Saving...';
    }

    updateMessage('Saving configuration...');

    try {
        // Collect form data
        const config = collectConfigurationData();
        console.log('Saving configuration:', config);

        const response = await fetch('/api/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(config)
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const result = await response.json();
        console.log('Configuration saved to server successfully:', result);

        // Save to localStorage after successful server save
        saveConfigurationToLocalStorage(config);

        updateMessage('Configuration saved successfully');

    } catch (error) {
        console.error('Failed to save configuration:', error);
        updateMessage('Failed to save configuration: ' + error.message);
    } finally {
        // Re-enable save button
        if (saveButton) {
            saveButton.disabled = false;
            saveButton.textContent = 'Save Configuration';
        }
    }
}

// Collect configuration data from form
function collectConfigurationData() {
    const config = {};

    // Checkbox field
    const allSchemasCheckbox = document.getElementById('do-all-schemas');
    if (allSchemasCheckbox) {
        config['do.all-schemas'] = allSchemasCheckbox.checked;
    }

    // Text fields mapping
    const fieldMappings = {
        'do-only-test-schema': 'do.only-test-schema',
        'oracle-url': 'oracle.url',
        'oracle-user': 'oracle.user',
        'oracle-password': 'oracle.password',
        'java-generated-package-name': 'java.generated-package-name',
        'path-target-project-java': 'path.target-project-java',
        'path-target-project-resources': 'path.target-project-resources',
        'path-target-project-postgre': 'path.target-project-postgre',
        'postgre-url': 'postgre.url',
        'postgre-username': 'postgre.username',
        'postgre-password': 'postgre.password'
    };

    Object.entries(fieldMappings).forEach(([fieldId, configKey]) => {
        const field = document.getElementById(fieldId);
        if (field) {
            config[configKey] = field.value.trim();
        }
    });

    return config;
}

// Reset configuration to defaults
async function resetConfiguration() {
    console.log('Resetting configuration to defaults...');

    const resetButton = document.getElementById('reset-config-btn');
    if (resetButton) {
        resetButton.disabled = true;
        resetButton.textContent = 'Resetting...';
    }

    updateMessage('Resetting configuration to defaults...');

    try {
        // Clear localStorage first
        clearConfigurationFromLocalStorage();

        // Reset server configuration
        const response = await fetch('/api/config/reset', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const result = await response.json();
        console.log('Configuration reset on server:', result);

        // Load defaults from server
        const configResponse = await fetch('/api/config');
        if (!configResponse.ok) {
            throw new Error(`HTTP ${configResponse.status}: ${configResponse.statusText}`);
        }

        const serverDefaults = await configResponse.json();
        console.log('Server defaults loaded:', serverDefaults);

        populateConfigurationForm(serverDefaults);
        updateMessage('Configuration reset to server defaults');

    } catch (error) {
        console.error('Failed to reset configuration:', error);
        updateMessage('Failed to reset configuration: ' + error.message);
    } finally {
        // Re-enable reset button
        if (resetButton) {
            resetButton.disabled = false;
            resetButton.textContent = 'Reset to Defaults';
        }
    }
}

// Load configuration when page loads
document.addEventListener('DOMContentLoaded', function() {
    // Load configuration after a short delay to ensure all other initialization is complete
    setTimeout(() => {
        loadConfiguration();
    }, 500);
});