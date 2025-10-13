/**
 * Configuration Management Service
 *
 * This module handles all configuration-related operations including:
 * - Loading configuration from backend server and localStorage
 * - Saving configuration to backend server and localStorage
 * - Resetting configuration to server defaults
 * - localStorage persistence for configuration data
 * - Automatic configuration synchronization between localStorage and server
 * - Form population and data collection
 * - Automatic connection testing after configuration changes
 */

// ============================================================================
// Constants
// ============================================================================

// localStorage key for configuration
const CONFIG_STORAGE_KEY = 'orapgsync-config';

// ============================================================================
// Configuration Loading
// ============================================================================

/**
 * Load configuration with localStorage priority and auto-sync
 *
 * Priority order:
 * 1. Check localStorage first - if found, sync to server and use it
 * 2. If no localStorage config, load from backend server
 *
 * After loading, automatically tests database connections.
 */
async function loadConfiguration() {
    console.log('Loading configuration...');
    updateMessage('Loading configuration...');

    try {
        // Check localStorage first
        const savedConfig = loadConfigurationFromLocalStorage();
        if (savedConfig) {
            console.log('Configuration found in localStorage:', savedConfig);

            // Auto-sync localStorage config to server to ensure consistency
            updateMessage('Syncing local configuration to server...');
            try {
                const syncResponse = await fetch('/api/config', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify(savedConfig)
                });

                if (syncResponse.ok) {
                    console.log('Configuration auto-synced to server successfully');
                    updateMessage('Configuration loaded and synced from local storage');
                } else {
                    console.warn('Failed to sync config to server, but continuing with localStorage config');
                    updateMessage('Configuration loaded from local storage (sync failed)');
                }
            } catch (syncError) {
                console.warn('Failed to sync localStorage config to server:', syncError);
                updateMessage('Configuration loaded from local storage (sync failed)');
            }

            populateConfigurationForm(savedConfig);

            // Automatically test connections after loading configuration
            await testConnectionsAfterConfigLoad();
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

        // Automatically test connections after loading configuration
        await testConnectionsAfterConfigLoad();

    } catch (error) {
        console.error('Failed to load configuration:', error);
        updateMessage('Failed to load configuration: ' + error.message);
    }
}

// ============================================================================
// Configuration Saving
// ============================================================================

/**
 * Save configuration to backend server and localStorage
 *
 * Saves configuration in this order:
 * 1. Collect form data
 * 2. Send to backend server
 * 3. Save to localStorage (only after successful server save)
 * 4. Automatically test database connections
 */
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

        // Automatically test connections after successful save
        await testConnectionsAfterConfigSave();

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

// ============================================================================
// Configuration Reset
// ============================================================================

/**
 * Reset configuration to server defaults
 *
 * Resets configuration in this order:
 * 1. Clear localStorage
 * 2. Send reset request to backend server
 * 3. Load server defaults
 * 4. Populate form with defaults
 */
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

// ============================================================================
// Form Data Collection
// ============================================================================

/**
 * Collect configuration data from form fields
 *
 * Collects both checkbox and text field values and returns them
 * as a configuration object with proper property names.
 *
 * @returns {Object} Configuration object with all form values
 */
function collectConfigurationData() {
    const config = {};

    // Checkbox fields
    const allSchemasCheckbox = document.getElementById('do-all-schemas');
    if (allSchemasCheckbox) {
        config['do.all-schemas'] = allSchemasCheckbox.checked;
    }

    const excludeLobDataCheckbox = document.getElementById('exclude-lob-data');
    if (excludeLobDataCheckbox) {
        config['exclude.lob-data'] = excludeLobDataCheckbox.checked;
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

// ============================================================================
// Form Population
// ============================================================================

/**
 * Populate form fields with configuration data
 *
 * Updates all configuration form fields with values from the provided
 * configuration object.
 *
 * @param {Object} config - Configuration object with property values
 */
function populateConfigurationForm(config) {
    // Checkbox fields
    const allSchemasCheckbox = document.getElementById('do-all-schemas');
    if (allSchemasCheckbox) {
        allSchemasCheckbox.checked = config['do.all-schemas'] === true;
    }

    const excludeLobDataCheckbox = document.getElementById('exclude-lob-data');
    if (excludeLobDataCheckbox) {
        excludeLobDataCheckbox.checked = config['exclude.lob-data'] === true;
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

// ============================================================================
// localStorage Operations
// ============================================================================

/**
 * Load configuration from localStorage
 *
 * @returns {Object|null} Configuration object if found, null otherwise
 */
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

/**
 * Save configuration to localStorage
 *
 * @param {Object} config - Configuration object to save
 */
function saveConfigurationToLocalStorage(config) {
    try {
        localStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(config));
        console.log('Configuration saved to localStorage');
    } catch (error) {
        console.error('Error saving configuration to localStorage:', error);
    }
}

/**
 * Clear configuration from localStorage
 */
function clearConfigurationFromLocalStorage() {
    try {
        localStorage.removeItem(CONFIG_STORAGE_KEY);
        console.log('Configuration cleared from localStorage');
    } catch (error) {
        console.error('Error clearing configuration from localStorage:', error);
    }
}

// ============================================================================
// Automatic Connection Testing
// ============================================================================

/**
 * Automatically test connections after configuration save
 *
 * Tests both Oracle and PostgreSQL connections in parallel after
 * a configuration save operation.
 */
async function testConnectionsAfterConfigSave() {
    console.log('Testing connections after config save...');
    updateMessage('Testing database connections...');

    try {
        // Test both connections in parallel
        const [oraclePromise, postgresPromise] = [
            testOracleConnection(),
            testPostgresConnection()
        ];

        await Promise.allSettled([oraclePromise, postgresPromise]);
        updateMessage('Connection tests completed after configuration save');

    } catch (error) {
        console.error('Error during automatic connection testing after save:', error);
        updateMessage('Connection testing failed after configuration save');
    }
}

/**
 * Automatically test connections after configuration load
 *
 * Tests both Oracle and PostgreSQL connections in parallel after
 * a configuration load operation. Includes a small delay to ensure
 * UI is fully updated before testing.
 */
async function testConnectionsAfterConfigLoad() {
    console.log('Testing connections after config load...');
    updateMessage('Testing database connections...');

    try {
        // Add a small delay to ensure UI is fully updated
        setTimeout(async () => {
            // Test both connections in parallel
            const [oraclePromise, postgresPromise] = [
                testOracleConnection(),
                testPostgresConnection()
            ];

            await Promise.allSettled([oraclePromise, postgresPromise]);
            updateMessage('Welcome to Ora Pg Sync - Ready to start');
        }, 200);

    } catch (error) {
        console.error('Error during automatic connection testing after load:', error);
        updateMessage('Connection testing failed after configuration load');
    }
}
