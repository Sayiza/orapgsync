// Oracle to PostgreSQL Migration Tool Frontend
document.addEventListener('DOMContentLoaded', function() {
    console.log('Migration tool frontend initialized');
    initializeInterface();
});

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
    const orchestrationBar = document.getElementById('orchestration-progress-bar');
    if (orchestrationBar) {
        orchestrationBar.style.display = 'block';
    }
}

// Hide orchestration progress bar
function hideOrchestrationProgress() {
    const orchestrationBar = document.getElementById('orchestration-progress-bar');
    if (orchestrationBar) {
        orchestrationBar.style.display = 'none';
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
        const response = await fetch('/api/database/test/oracle');
        const result = await response.json();

        if (result.connected) {
            updateConnectionStatus('oracle', 'connected', `Connected in ${result.connectionTimeMs}ms`);
            updateMessage(`Oracle connection established successfully (${result.databaseProductName} ${result.databaseProductVersion})`);

            // TODO: Call actual API to get schema information
            setTimeout(() => {
                updateComponentCount('oracle-schemas', '?');
                updateComponentCount('oracle-objects', '?');
                updateComponentCount('oracle-tables', '?');
                updateComponentCount('oracle-data', '?');
                updateComponentCount('oracle-views', '?');
            }, 500);
        } else {
            updateConnectionStatus('oracle', 'disconnected', 'Connection failed');
            updateMessage('Oracle connection failed: ' + result.message);
        }

    } catch (error) {
        console.error('Oracle connection test failed:', error);
        updateConnectionStatus('oracle', 'disconnected', 'Connection failed');
        updateMessage('Failed to test Oracle connection: ' + error.message);
    }
}

// Test PostgreSQL connection
async function testPostgresConnection() {
    console.log('Testing PostgreSQL connection...');

    updateConnectionStatus('postgres', 'connecting', 'Testing connection...');
    updateMessage('Testing PostgreSQL database connection...');

    try {
        const response = await fetch('/api/database/test/postgres');
        const result = await response.json();

        if (result.connected) {
            updateConnectionStatus('postgres', 'connected', `Connected in ${result.connectionTimeMs}ms`);
            updateMessage(`PostgreSQL connection established successfully (${result.databaseProductName} ${result.databaseProductVersion})`);

            // TODO: Call actual API to get schema information
            setTimeout(() => {
                updateComponentCount('postgres-schemas', '?');
                updateComponentCount('postgres-objects', '?');
                updateComponentCount('postgres-tables', '?');
                updateComponentCount('postgres-data', '?');
                updateComponentCount('postgres-views', '?');
            }, 500);
        } else {
            updateConnectionStatus('postgres', 'disconnected', 'Connection failed');
            updateMessage('PostgreSQL connection failed: ' + result.message);
        }

    } catch (error) {
        console.error('PostgreSQL connection test failed:', error);
        updateConnectionStatus('postgres', 'disconnected', 'Connection failed');
        updateMessage('Failed to test PostgreSQL connection: ' + error.message);
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

// Load configuration with localStorage priority and auto-sync
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

// Schema Management Functions

// Load Oracle schemas using job-based approach
async function loadOracleSchemas() {
    console.log('Starting Oracle schema extraction job...');
    updateMessage('Starting Oracle schema extraction...');

    updateComponentCount("oracle-schemas", "-");

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/schemas/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('Oracle schema extraction job started:', startResult.jobId);
            updateMessage('Oracle schema extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#oracle-schemas .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'oracle', 'schemas');

        } else {
            updateComponentCount('oracle-schemas', '!', 'error');
            updateMessage('Failed to start Oracle schema extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting Oracle schema extraction:', error);
        updateComponentCount('oracle-schemas', '!', 'error');
        updateMessage('Error starting Oracle schema extraction: ' + error.message);
    }
}

// Load PostgreSQL schemas using job-based approach
async function loadPostgresSchemas() {
    console.log('Starting PostgreSQL schema extraction job...');
    updateMessage('Starting PostgreSQL schema extraction...');

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/schemas/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('PostgreSQL schema extraction job started:', startResult.jobId);
            updateMessage('PostgreSQL schema extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#postgres-schemas .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'postgres', 'schemas');

        } else {
            updateComponentCount('postgres-schemas', '!', 'error');
            updateMessage('Failed to start PostgreSQL schema extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting PostgreSQL schema extraction:', error);
        updateComponentCount('postgres-schemas', '!', 'error');
        updateMessage('Error starting PostgreSQL schema extraction: ' + error.message);
    }
}

// Populate schema list with schema names
function populateSchemaList(database, schemas) {
    const schemaItemsElement = document.getElementById(`${database}-schema-items`);

    if (!schemaItemsElement) {
        console.warn(`Schema items element not found for database: ${database}`);
        return;
    }

    // Clear existing items
    schemaItemsElement.innerHTML = '';

    if (schemas && schemas.length > 0) {
        schemas.forEach(schema => {
            const schemaItem = document.createElement('div');
            schemaItem.className = 'schema-item';
            schemaItem.textContent = schema;
            schemaItemsElement.appendChild(schemaItem);
        });
    } else {
        const noSchemasItem = document.createElement('div');
        noSchemasItem.className = 'schema-item';
        noSchemasItem.textContent = 'No schemas found';
        noSchemasItem.style.fontStyle = 'italic';
        noSchemasItem.style.color = '#999';
        schemaItemsElement.appendChild(noSchemasItem);
    }
}

// Toggle schema list visibility
function toggleSchemaList(database) {
    const schemaItems = document.getElementById(`${database}-schema-items`);
    const header = document.querySelector(`#${database}-schema-list .schema-list-header`);

    if (!schemaItems || !header) {
        console.warn(`Schema list elements not found for database: ${database}`);
        return;
    }

    if (schemaItems.style.display === 'none') {
        schemaItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        schemaItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Object Type Management Functions

// Extract Oracle object data types using job-based approach
async function loadOracleObjectTypes() {
    console.log('Starting Oracle object data type extraction job...');
    updateMessage('Starting Oracle object data type extraction...');

    updateComponentCount('oracle-objects', '-');

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/objects/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('Oracle object data type extraction job started:', startResult.jobId);
            updateMessage('Oracle object data type extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#oracle-objects .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'oracle', 'objects');

        } else {
            updateComponentCount('oracle-objects', '!', 'error');
            updateMessage('Failed to start Oracle object data type extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting Oracle object data type extraction:', error);
        updateComponentCount('oracle-objects', '!', 'error');
        updateMessage('Error starting Oracle object data type extraction: ' + error.message);
    }
}

// Extract PostgreSQL object data types using job-based approach
async function loadPostgresObjectTypes() {
    console.log('Starting PostgreSQL object data type extraction job...');
    updateMessage('Starting PostgreSQL object data type extraction...');

    try {
        // Start the job
        const startResponse = await fetch('/api/jobs/objects/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const startResult = await startResponse.json();

        if (startResult.status === 'success') {
            console.log('PostgreSQL object data type extraction job started:', startResult.jobId);
            updateMessage('PostgreSQL object data type extraction started...');

            // Disable refresh button during extraction
            const button = document.querySelector('#postgres-objects .refresh-btn');
            if (button) {
                button.disabled = true;
                button.innerHTML = '⏳';
            }

            // Start polling for job status
            await pollJobUntilComplete(startResult.jobId, 'postgres', 'objects');

        } else {
            updateComponentCount('postgres-objects', '!', 'error');
            updateMessage('Failed to start PostgreSQL object data type extraction: ' + startResult.message);
        }

    } catch (error) {
        console.error('Error starting PostgreSQL object data type extraction:', error);
        updateComponentCount('postgres-objects', '!', 'error');
        updateMessage('Error starting PostgreSQL object data type extraction: ' + error.message);
    }
}

// Populate object type list with object types grouped by schema
function populateObjectTypeList(database, objectTypesBySchema) {
    const objectItemsElement = document.getElementById(`${database}-object-items`);

    if (!objectItemsElement) {
        console.warn(`Object items element not found for database: ${database}`);
        return;
    }

    // Clear existing items
    objectItemsElement.innerHTML = '';

    if (objectTypesBySchema && Object.keys(objectTypesBySchema).length > 0) {
        Object.entries(objectTypesBySchema).forEach(([schemaName, objectTypes]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'object-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'object-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${objectTypes.length})`;
            schemaHeader.onclick = () => toggleObjectSchemaGroup(database, schemaName);

            const objectTypeItems = document.createElement('div');
            objectTypeItems.className = 'object-type-items';
            objectTypeItems.id = `${database}-${schemaName}-object-types`;

            if (objectTypes && objectTypes.length > 0) {
                objectTypes.forEach(objectType => {
                    const objectTypeItem = document.createElement('div');
                    objectTypeItem.className = 'object-type-item';
                    objectTypeItem.innerHTML = `${objectType.name} (${objectType.variables?.length || 0} vars)`;

                    // Add click handler to show/hide details
                    objectTypeItem.onclick = () => toggleObjectTypeDetails(objectTypeItem, objectType);

                    objectTypeItems.appendChild(objectTypeItem);
                });
            } else {
                const noObjectTypesItem = document.createElement('div');
                noObjectTypesItem.className = 'object-type-item';
                noObjectTypesItem.textContent = 'No object types found';
                noObjectTypesItem.style.fontStyle = 'italic';
                noObjectTypesItem.style.color = '#999';
                objectTypeItems.appendChild(noObjectTypesItem);
            }

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(objectTypeItems);
            objectItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noObjectTypesGroup = document.createElement('div');
        noObjectTypesGroup.className = 'object-schema-group';
        noObjectTypesGroup.innerHTML = `
            <div class="object-type-item" style="font-style: italic; color: #999;">
                No object types found
            </div>
        `;
        objectItemsElement.appendChild(noObjectTypesGroup);
    }
}

// Toggle object list visibility
function toggleObjectList(database) {
    const objectItems = document.getElementById(`${database}-object-items`);
    const header = document.querySelector(`#${database}-object-list .object-list-header`);

    if (!objectItems || !header) {
        console.warn(`Object list elements not found for database: ${database}`);
        return;
    }

    if (objectItems.style.display === 'none') {
        objectItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        objectItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle object schema group visibility
function toggleObjectSchemaGroup(database, schemaName) {
    const objectTypeItems = document.getElementById(`${database}-${schemaName}-object-types`);
    const header = event.target;

    if (!objectTypeItems || !header) {
        console.warn(`Object schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (objectTypeItems.style.display === 'none') {
        objectTypeItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        objectTypeItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle object type details visibility
function toggleObjectTypeDetails(objectTypeItem, objectType) {
    let detailsElement = objectTypeItem.querySelector('.object-type-details');

    if (detailsElement) {
        // Toggle existing details
        detailsElement.style.display = detailsElement.style.display === 'none' ? 'block' : 'none';
    } else {
        // Create and show details
        detailsElement = document.createElement('div');
        detailsElement.className = 'object-type-details';

        if (objectType.variables && objectType.variables.length > 0) {
            detailsElement.innerHTML = `
                <div><strong>Variables:</strong></div>
                ${objectType.variables.map(variable => `
                    <div class="object-type-variable">
                        <span class="object-type-variable-name">${variable.name}</span>:
                        <span class="object-type-variable-type">${variable.dataType}</span>
                    </div>
                `).join('')}
            `;
        } else {
            detailsElement.innerHTML = '<div style="font-style: italic; color: #999;">No variables defined</div>';
        }

        objectTypeItem.appendChild(detailsElement);
        detailsElement.style.display = 'block';
    }
}

// Automatically test connections after configuration save
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

// Automatically test connections after configuration load
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
            updateMessage('Connection tests completed after configuration load');
        }, 200);

    } catch (error) {
        console.error('Error during automatic connection testing after load:', error);
        updateMessage('Connection testing failed after configuration load');
    }
}

// Synonym Management Functions

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

// Table Metadata Extraction Job Management Functions

// Extract Oracle table metadata (starts the job)
async function extractTableMetadata() {
    console.log('Starting Oracle table metadata extraction job...');

    const button = document.querySelector('#oracle-tables .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle table metadata extraction...');
    updateProgress(0, 'Starting Oracle table metadata extraction');

    try {
        const response = await fetch('/api/jobs/tables/oracle/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle table extraction job started:', result.jobId);
            updateMessage('Oracle table extraction job started successfully');

            // Start polling for progress
            pollJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle table extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle table extraction job:', error);
        updateMessage('Failed to start Oracle table extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle table extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Extract PostgreSQL table metadata (starts the job)
async function extractPostgresTableMetadata() {
    console.log('Starting PostgreSQL table metadata extraction job...');

    const button = document.querySelector('#postgres-tables .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL table metadata extraction...');
    updateProgress(0, 'Starting PostgreSQL table metadata extraction');

    try {
        const response = await fetch('/api/jobs/tables/postgres/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL table extraction job started:', result.jobId);
            updateMessage('PostgreSQL table extraction job started successfully');

            // Start polling for progress
            pollJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL table extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL table extraction job:', error);
        updateMessage('Failed to start PostgreSQL table extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL table extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Poll job status until completion
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
                button.innerHTML = '⚙';
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
            button.innerHTML = '⚙';
        }
    }
}

// Poll job status until completion (generic for all job types)
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
                        button.innerHTML = jobType === 'schemas' ? '↻' : '↻';
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
                    button.innerHTML = '↻';
                }

                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

// Get schema job results and display them
async function getSchemaJobResults(jobId, database) {
    console.log('Getting schema job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Schema job results:', result);

            const schemas = result.schemas || [];

            // Update component count
            updateComponentCount(`${database}-schemas`, schemas.length);

            // Populate the schema list
            populateSchemaList(database, schemas);

            // Show the schema list if we have schemas
            if (schemas.length > 0) {
                document.getElementById(`${database}-schema-list`).style.display = 'block';
            }

            updateMessage(`Loaded ${schemas.length} ${database} schemas`);
        } else {
            throw new Error(result.message || 'Failed to get schema job results');
        }

    } catch (error) {
        console.error('Error getting schema job results:', error);
        updateComponentCount(`${database}-schemas`, '!', 'error');
        updateMessage(`Error getting schema results: ${error.message}`);
    }
}

// Get object data type job results and display them
async function getObjectDataTypeJobResults(jobId, database) {
    console.log('Getting object data type job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Object data type job results:', result);

            // For object data types, we need to process the result differently
            // The job returns a list of ObjectDataTypeMetaData, we need to group by schema
            const objectDataTypes = result.result || [];
            const objectDataTypesBySchema = {};

            objectDataTypes.forEach(objectType => {
                if (!objectDataTypesBySchema[objectType.schema]) {
                    objectDataTypesBySchema[objectType.schema] = [];
                }
                objectDataTypesBySchema[objectType.schema].push(objectType);
            });

            // Update component count
            updateComponentCount(`${database}-objects`, objectDataTypes.length);

            // Populate the object type list
            populateObjectTypeList(database, objectDataTypesBySchema);

            // Show the object list if we have object data types
            if (objectDataTypes.length > 0) {
                document.getElementById(`${database}-object-list`).style.display = 'block';
            }

            updateMessage(`Loaded ${objectDataTypes.length} ${database} object data types`);
        } else {
            throw new Error(result.message || 'Failed to get object data type job results');
        }

    } catch (error) {
        console.error('Error getting object data type job results:', error);
        updateComponentCount(`${database}-objects`, '!', 'error');
        updateMessage(`Error getting object data type results: ${error.message}`);
    }
}

// Get job results and display them
async function getJobResults(jobId, database = 'oracle') {
    console.log('Getting job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Job results:', result);
            displayTableResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get job results');
        }

    } catch (error) {
        console.error('Error getting job results:', error);
        updateMessage('Error getting results: ' + error.message);
    }
}

// Display table extraction results
function displayTableResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Update table count badge
        updateComponentCount(`${database}-tables`, summary.totalTables);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted ${summary.totalTables} ${databaseName} tables with ${summary.totalColumns} columns from ${Object.keys(summary.schemaTableCounts).length} schemas`);

        // Populate table list
        populateTableList(summary, database);

        // Show table list
        if (summary.totalTables > 0) {
            document.getElementById(`${database}-table-list`).style.display = 'block';
        }
    }
}

// Populate table list with extracted table metadata
function populateTableList(summary, database = 'oracle') {
    const tableItemsElement = document.getElementById(`${database}-table-items`);

    if (!tableItemsElement) {
        console.warn('Table items element not found');
        return;
    }

    // Clear existing items
    tableItemsElement.innerHTML = '';

    if (summary.schemaTableCounts && Object.keys(summary.schemaTableCounts).length > 0) {
        Object.entries(summary.schemaTableCounts).forEach(([schemaName, tableCount]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${tableCount} tables)`;
            schemaHeader.onclick = () => toggleTableSchemaGroup(database, schemaName);

            const tableItems = document.createElement('div');
            tableItems.className = 'table-items-list';
            tableItems.id = `${database}-${schemaName}-tables`;

            // Add individual table entries for this schema
            if (summary.tables) {
                const schemaTables = Object.entries(summary.tables).filter(([key, table]) =>
                    table.schema === schemaName);

                schemaTables.forEach(([tableKey, table]) => {
                    const tableItem = document.createElement('div');
                    tableItem.className = 'table-item';
                    // Note: Constraints are extracted but NOT created yet (will be created after data transfer - Step C)
                    tableItem.innerHTML = `${table.name} (${table.columnCount} cols)`;
                    tableItems.appendChild(tableItem);
                });
            }

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(tableItems);
            tableItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noTablesItem = document.createElement('div');
        noTablesItem.className = 'table-item';
        noTablesItem.textContent = 'No tables found';
        noTablesItem.style.fontStyle = 'italic';
        noTablesItem.style.color = '#999';
        tableItemsElement.appendChild(noTablesItem);
    }
}

// Toggle table list visibility
function toggleTableList(database) {
    const tableItems = document.getElementById(`${database}-table-items`);
    const header = document.querySelector(`#${database}-table-list .table-list-header`);

    if (!tableItems || !header) {
        console.warn(`Table list elements not found for database: ${database}`);
        return;
    }

    if (tableItems.style.display === 'none') {
        tableItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        tableItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle table schema group visibility
function toggleTableSchemaGroup(database, schemaName) {
    const tableItems = document.getElementById(`${database}-${schemaName}-tables`);
    const header = event.target;

    if (!tableItems || !header) {
        console.warn(`Table schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (tableItems.style.display === 'none') {
        tableItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        tableItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Row Count Extraction Functions

// Extract Oracle row counts (starts the job)
async function extractOracleRowCounts() {
    console.log('Starting Oracle row count extraction job...');

    updateComponentCount("oracle-data", "-");

    const button = document.querySelector('#oracle-data .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting Oracle row count extraction...');
    updateProgress(0, 'Starting Oracle row count extraction');

    try {
        const response = await fetch('/api/jobs/oracle/row_count/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('Oracle row count extraction job started:', result.jobId);
            updateMessage('Oracle row count extraction job started successfully');

            // Start polling for progress
            pollRowCountJobStatus(result.jobId, 'oracle');
        } else {
            throw new Error(result.message || 'Failed to start Oracle row count extraction job');
        }

    } catch (error) {
        console.error('Error starting Oracle row count extraction job:', error);
        updateMessage('Failed to start Oracle row count extraction: ' + error.message);
        updateProgress(0, 'Failed to start Oracle row count extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Poll row count job status until completion
async function pollRowCountJobStatus(jobId, database = 'oracle') {
    console.log('Polling row count job status for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/status`);
        const result = await response.json();

        if (result.status === 'error') {
            throw new Error(result.message);
        }

        console.log('Row count job status:', result);

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
                console.log('Row count job completed successfully');
                updateProgress(100, 'Row count extraction completed successfully');
                updateMessage('Row count extraction completed');

                // Get job results
                await getRowCountJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('Row count job failed:', result.error);
                updateProgress(0, 'Row count extraction failed');
                updateMessage('Row count extraction failed: ' + (result.error || 'Unknown error'));
            }

            // Re-enable extract button
            const button = document.querySelector(`#${database}-data .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '⚙';
            }
        } else {
            // Continue polling
            setTimeout(() => pollRowCountJobStatus(jobId, database), 1000);
        }

    } catch (error) {
        console.error('Error polling row count job status:', error);
        updateMessage('Error checking row count job status: ' + error.message);
        updateProgress(0, 'Error checking row count job status');

        // Re-enable button
        const button = document.querySelector(`#${database}-data .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Get row count job results and display them
async function getRowCountJobResults(jobId, database) {
    console.log('Getting row count job results for:', jobId);

    try {
        const response = await fetch(`/api/jobs/${jobId}/result`);
        const result = await response.json();

        if (result.status === 'success') {
            console.log('Row count job results:', result);
            displayRowCountResults(result, database);
        } else {
            throw new Error(result.message || 'Failed to get row count job results');
        }

    } catch (error) {
        console.error('Error getting row count job results:', error);
        updateMessage('Error getting row count results: ' + error.message);
    }
}

// Display row count extraction results
function displayRowCountResults(result, database = 'oracle') {
    const summary = result.summary;

    if (summary) {
        // Extract total row count from summary message
        const rowCounts = result.result || [];
        const totalRows = rowCounts.reduce((sum, rc) => sum + (rc.rowCount >= 0 ? rc.rowCount : 0), 0);

        // Format the total row count
        const formattedTotal = totalRows.toLocaleString();

        // Update row count badge with formatted number
        updateComponentCount(`${database}-data`, formattedTotal);

        // Show success message
        const databaseName = database === 'oracle' ? 'Oracle' : 'PostgreSQL';
        updateMessage(`Extracted row counts for ${rowCounts.length} ${databaseName} tables: ${formattedTotal} total rows`);

        // Populate row count list
        populateRowCountList(rowCounts, database);

        // Show row count list
        if (rowCounts.length > 0) {
            document.getElementById(`${database}-rowcount-list`).style.display = 'block';
        }
    }
}

// Populate row count list with extracted row count data
function populateRowCountList(rowCounts, database = 'oracle') {
    const rowCountItemsElement = document.getElementById(`${database}-rowcount-items`);

    if (!rowCountItemsElement) {
        console.warn('Row count items element not found');
        return;
    }

    // Clear existing items
    rowCountItemsElement.innerHTML = '';

    if (rowCounts && rowCounts.length > 0) {
        // Group row counts by schema
        const rowCountsBySchema = {};
        rowCounts.forEach(rowCount => {
            if (!rowCountsBySchema[rowCount.schema]) {
                rowCountsBySchema[rowCount.schema] = [];
            }
            rowCountsBySchema[rowCount.schema].push(rowCount);
        });

        Object.entries(rowCountsBySchema).forEach(([schemaName, schemaRowCounts]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            // Calculate total rows for this schema
            const schemaTotalRows = schemaRowCounts.reduce((sum, rc) => sum + (rc.rowCount >= 0 ? rc.rowCount : 0), 0);

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaRowCounts.length} tables, ${schemaTotalRows.toLocaleString()} rows)`;
            schemaHeader.onclick = () => toggleRowCountSchemaGroup(database, schemaName);

            const rowCountItems = document.createElement('div');
            rowCountItems.className = 'table-items-list';
            rowCountItems.id = `${database}-${schemaName}-rowcounts`;

            // Add individual table row counts for this schema
            schemaRowCounts.forEach(rowCount => {
                const rowCountItem = document.createElement('div');
                rowCountItem.className = 'table-item';

                if (rowCount.rowCount >= 0) {
                    rowCountItem.innerHTML = `${rowCount.tableName}: ${rowCount.rowCount.toLocaleString()} rows`;
                } else {
                    rowCountItem.innerHTML = `${rowCount.tableName}: <span style="color: #d73502;">Error counting rows</span>`;
                    rowCountItem.style.color = '#666';
                }

                rowCountItems.appendChild(rowCountItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(rowCountItems);
            rowCountItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noRowCountsItem = document.createElement('div');
        noRowCountsItem.className = 'table-item';
        noRowCountsItem.textContent = 'No row count data found';
        noRowCountsItem.style.fontStyle = 'italic';
        noRowCountsItem.style.color = '#999';
        rowCountItemsElement.appendChild(noRowCountsItem);
    }
}

// Toggle row count list visibility
function toggleRowCountList(database) {
    const rowCountItems = document.getElementById(`${database}-rowcount-items`);
    const header = document.querySelector(`#${database}-rowcount-list .table-list-header`);

    if (!rowCountItems || !header) {
        console.warn(`Row count list elements not found for database: ${database}`);
        return;
    }

    if (rowCountItems.style.display === 'none') {
        rowCountItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        rowCountItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Toggle row count schema group visibility
function toggleRowCountSchemaGroup(database, schemaName) {
    const rowCountItems = document.getElementById(`${database}-${schemaName}-rowcounts`);
    const header = event.target;

    if (!rowCountItems || !header) {
        console.warn(`Row count schema group elements not found for: ${database}.${schemaName}`);
        return;
    }

    if (rowCountItems.style.display === 'none') {
        rowCountItems.style.display = 'block';
        header.classList.remove('collapsed');
    } else {
        rowCountItems.style.display = 'none';
        header.classList.add('collapsed');
    }
}

// Extract PostgreSQL row counts (starts the job)
async function extractPostgresRowCounts() {
    console.log('Starting PostgreSQL row count extraction job...');

    const button = document.querySelector('#postgres-data .refresh-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateMessage('Starting PostgreSQL row count extraction...');
    updateProgress(0, 'Starting PostgreSQL row count extraction');

    try {
        const response = await fetch('/api/jobs/postgres/row_count/extract', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL row count extraction job started:', result.jobId);
            updateMessage('PostgreSQL row count extraction job started successfully');

            // Start polling for progress
            pollRowCountJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL row count extraction job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL row count extraction job:', error);
        updateMessage('Failed to start PostgreSQL row count extraction: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL row count extraction');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = '⚙';
        }
    }
}

// Schema Creation Functions
async function createPostgresSchemas() {
    console.log('Starting PostgreSQL schema creation job...');
    const button = document.querySelector('#postgres-schemas .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateComponentCount("postgres-schemas", "-");

    updateMessage('Starting PostgreSQL schema creation...');
    updateProgress(0, 'Starting PostgreSQL schema creation');

    try {
        const response = await fetch('/api/jobs/postgres/schema/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (result.status === 'success') {
            console.log('PostgreSQL schema creation job started:', result.jobId);
            updateMessage('PostgreSQL schema creation job started successfully');

            // Start polling for progress and AWAIT completion
            await pollSchemaCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL schema creation job');
        }

    } catch (error) {
        console.error('Error starting PostgreSQL schema creation job:', error);
        updateMessage('Failed to start PostgreSQL schema creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL schema creation');

        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Schemas';
        }
    }
}

async function pollSchemaCreationJobStatus(jobId, database) {
    console.log(`Polling schema creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Schema creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Schema creation job completed for ${database}`);

                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleSchemaCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-schemas .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Schemas';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }

            } catch (error) {
                console.error('Error polling schema creation job status:', error);
                updateMessage('Error checking schema creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');

                // Re-enable button
                const button = document.querySelector(`#${database}-schemas .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Schemas';
                }

                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleSchemaCreationJobComplete(result, database) {
    console.log(`Schema creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Schema creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Schema creation completed successfully: ${createdCount} schemas created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Schema creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update schema creation results section
    displaySchemaCreationResults(result, database);

    // Refresh PostgreSQL schemas to show newly created ones ... not really needed
    //setTimeout(() => {
    //    loadPostgresSchemas();
    //}, 1000);
}

function displaySchemaCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-schema-creation-results`);
    const detailsDiv = document.getElementById(`${database}-schema-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Schema creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-schemas", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="schema-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created schemas
        if (summary.createdCount > 0) {
            html += '<div class="created-schemas-section">';
            html += '<h4>Created Schemas:</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.createdSchemas).forEach(schema => {
                html += `<div class="schema-item created">${schema.schema} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped schemas
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-schemas-section">';
            html += '<h4>Skipped Schemas (already exist):</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.skippedSchemas).forEach(schema => {
                html += `<div class="schema-item skipped">${schema.schema} (${schema.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-schemas-section">';
            html += '<h4>Failed Schemas:</h4>';
            html += '<div class="schema-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="schema-item error">${error.schema}: ${error.error}</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleSchemaCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-schema-creation-results`);
    const detailsDiv = document.getElementById(`${database}-schema-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// Object Type Creation Functions
async function createPostgresObjectTypes() {
    console.log('Starting PostgreSQL object type creation job...');

    updateComponentCount("postgres-objects", "-");

    const button = document.querySelector('#postgres-objects .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }
    updateMessage('Starting PostgreSQL object type creation...');
    updateProgress(0, 'Starting PostgreSQL object type creation');

    try {
        const response = await fetch('/api/jobs/postgres/object-type/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL object type creation job started:', result.jobId);
            updateMessage('PostgreSQL object type creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollObjectTypeCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL object type creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL object type creation job:', error);
        updateMessage('Failed to start PostgreSQL object type creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL object type creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Types';
        }
    }
}

async function pollObjectTypeCreationJobStatus(jobId, database) {
    console.log(`Polling object type creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Object type creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Object type creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleObjectTypeCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-objects .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Types';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling object type creation job status:', error);
                updateMessage('Error checking object type creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-objects .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Types';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleObjectTypeCreationJobComplete(result, database) {
    console.log(`Object type creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Object type creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Object type creation completed successfully: ${createdCount} types created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Object type creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Display object type creation results
    displayObjectTypeCreationResults(result, database);

    // Refresh PostgreSQL object types to show newly created ones ... no need
    //setTimeout(() => {
    //    loadPostgresObjectTypes();
    //}, 1000);
}

function displayObjectTypeCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-object-type-creation-results`);
    const detailsDiv = document.getElementById(`${database}-object-type-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Object type creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-objects", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="object-type-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created object types
        if (summary.createdCount > 0) {
            html += '<div class="created-types-section">';
            html += '<h4>Created Object Types:</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.createdTypes).forEach(type => {
                html += `<div class="object-type-item created">${type.typeName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped object types
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-types-section">';
            html += '<h4>Skipped Object Types (already exist):</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.skippedTypes).forEach(type => {
                html += `<div class="object-type-item skipped">${type.typeName} (${type.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-types-section">';
            html += '<h4>Failed Object Types:</h4>';
            html += '<div class="object-type-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="object-type-item error">`;
                html += `<strong>${error.typeName}</strong>: ${error.error}`;
                if (error.sql) {
                    html += `<div class="sql-statement"><pre>${error.sql}</pre></div>`;
                }
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleObjectTypeCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-object-type-creation-results`);
    const detailsDiv = document.getElementById(`${database}-object-type-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}


// Table Creation Functions
async function createPostgresTables() {
    console.log('Starting PostgreSQL table creation job...');

    updateComponentCount("postgres-tables", "-");

    const button = document.querySelector('#postgres-tables .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }
    updateMessage('Starting PostgreSQL table creation...');
    updateProgress(0, 'Starting PostgreSQL table creation');

    try {
        const response = await fetch('/api/jobs/postgres/table/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('PostgreSQL table creation job started:', result.jobId);
            updateMessage('PostgreSQL table creation job started successfully');
            // Start polling for progress and AWAIT completion
            await pollTableCreationJobStatus(result.jobId, 'postgres');
        } else {
            throw new Error(result.message || 'Failed to start PostgreSQL table creation job');
        }
    } catch (error) {
        console.error('Error starting PostgreSQL table creation job:', error);
        updateMessage('Failed to start PostgreSQL table creation: ' + error.message);
        updateProgress(0, 'Failed to start PostgreSQL table creation');
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Create Tables';
        }
    }
}

async function pollTableCreationJobStatus(jobId, database) {
    console.log(`Polling table creation job status for ${database}:`, jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log(`Table creation job status for ${database}:`, status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log(`Table creation job completed for ${database}`);
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleTableCreationJobComplete(result, database);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector(`#${database}-tables .action-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Create Tables';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling table creation job status:', error);
                updateMessage('Error checking table creation progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector(`#${database}-tables .action-btn`);
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Create Tables';
                }
                // Reject the promise to signal error
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleTableCreationJobComplete(result, database) {
    console.log(`Table creation job results for ${database}:`, result);

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    updateProgress(100, `Table creation completed: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);

    if (result.isSuccessful) {
        updateMessage(`Table creation completed successfully: ${createdCount} tables created, ${skippedCount} already existed`);
    } else {
        updateMessage(`Table creation completed with errors: ${createdCount} created, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update table creation results section
    displayTableCreationResults(result, database);

    // Refresh PostgreSQL tables to show newly created ones not need any more
    //setTimeout(() => {
    //    extractPostgresTableMetadata();
    //}, 1000);
}

function displayTableCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-table-creation-results`);
    const detailsDiv = document.getElementById(`${database}-table-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Table creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-tables", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        if (summary.unmappedDefaultCount > 0) {
            html += `<span class="stat-item warnings">Unmapped Defaults: ${summary.unmappedDefaultCount}</span>`;
        }
        html += `</div>`;
        html += '</div>';

        // Show created tables
        if (summary.createdCount > 0) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdTables).forEach(table => {
                html += `<div class="table-item created">${table.tableName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped tables
        if (summary.skippedCount > 0) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Tables (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedTables).forEach(table => {
                html += `<div class="table-item skipped">${table.tableName} (${table.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors
        if (summary.errorCount > 0) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Tables:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.tableName}</strong>: ${error.error}`;
                if (error.sql) {
                    html += `<div class="sql-statement"><pre>${error.sql}</pre></div>`;
                }
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show unmapped defaults (columns with complex Oracle default values that need manual review)
        if (summary.unmappedDefaultCount > 0) {
            html += '<div class="warning-tables-section">';
            html += '<h4>Columns with Unmapped Default Values (Require Manual Review):</h4>';
            html += '<div class="table-items">';
            html += '<p style="font-style: italic; color: #666; margin: 5px 0;">The following columns have complex Oracle default values that could not be automatically transformed. Tables were created without these defaults. You can add them manually later.</p>';
            Object.values(summary.unmappedDefaults).forEach(warning => {
                html += `<div class="table-item warning">`;
                html += `<strong>${warning.tableName}.${warning.columnName}</strong>`;
                html += `<div style="margin-left: 15px; margin-top: 5px;">`;
                html += `<div><strong>Oracle Default:</strong> <code>${warning.oracleDefault}</code></div>`;
                html += `<div><strong>Note:</strong> ${warning.note}</div>`;
                html += `</div>`;
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;

    // Show the results section
    resultsDiv.style.display = 'block';
}

function toggleTableCreationResults(database) {
    const resultsDiv = document.getElementById(`${database}-table-creation-results`);
    const detailsDiv = document.getElementById(`${database}-table-creation-details`);
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

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
        const response = await fetch('/api/jobs/oracle/sequence/extract', {
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
            button.innerHTML = '⚙';
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
        const response = await fetch('/api/jobs/postgres/sequence/extract', {
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
            button.innerHTML = '⚙';
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
        const response = await fetch('/api/jobs/postgres/sequence-creation/create', {
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
                        button.innerHTML = '⚙';
                    }

                    resolve(status);
                } else if (status.status === 'FAILED') {
                    updateProgress(-1, `${database.toUpperCase()} sequence extraction failed`);
                    updateMessage(`${database.toUpperCase()} sequence extraction failed: ` + (status.error || 'Unknown error'));

                    // Re-enable button
                    const button = document.querySelector(`#${database}-sequences .refresh-btn`);
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = '⚙';
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
        updateComponentCount(`${database}-sequences`, '?', 'error');
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
    const sequenceItemsElement = document.getElementById(`${database}-sequence-items`);

    if (!sequenceItemsElement) {
        console.warn('Sequence items element not found');
        return;
    }

    // Clear existing items
    sequenceItemsElement.innerHTML = '';

    // Get sequences from result
    const sequences = result.result || [];

    if (sequences && sequences.length > 0) {
        // Group sequences by schema
        const schemaGroups = {};
        sequences.forEach(seq => {
            const schema = seq.schema || 'unknown';
            if (!schemaGroups[schema]) {
                schemaGroups[schema] = [];
            }
            schemaGroups[schema].push(seq);
        });

        // Create schema groups
        Object.entries(schemaGroups).forEach(([schemaName, schemaSequences]) => {
            const schemaGroup = document.createElement('div');
            schemaGroup.className = 'table-schema-group';

            const schemaHeader = document.createElement('div');
            schemaHeader.className = 'table-schema-header';
            schemaHeader.innerHTML = `<span class="toggle-indicator">▼</span> ${schemaName} (${schemaSequences.length} sequences)`;
            schemaHeader.onclick = () => toggleSequenceSchemaGroup(database, schemaName);

            const sequenceItems = document.createElement('div');
            sequenceItems.className = 'table-items-list';
            sequenceItems.id = `${database}-${schemaName}-sequences`;

            // Add individual sequence entries for this schema
            schemaSequences.forEach(seq => {
                const sequenceItem = document.createElement('div');
                sequenceItem.className = 'table-item';
                sequenceItem.textContent = seq.sequenceName;
                sequenceItems.appendChild(sequenceItem);
            });

            schemaGroup.appendChild(schemaHeader);
            schemaGroup.appendChild(sequenceItems);
            sequenceItemsElement.appendChild(schemaGroup);
        });
    } else {
        const noSequencesItem = document.createElement('div');
        noSequencesItem.className = 'table-item';
        noSequencesItem.textContent = 'No sequences found';
        noSequencesItem.style.fontStyle = 'italic';
        noSequencesItem.style.color = '#999';
        sequenceItemsElement.appendChild(noSequencesItem);
    }
}

function toggleSequenceSchemaGroup(database, schemaName) {
    const sequenceItems = document.getElementById(`${database}-${schemaName}-sequences`);
    const header = sequenceItems.previousElementSibling;
    const indicator = header.querySelector('.toggle-indicator');

    if (sequenceItems.style.display === 'none') {
        sequenceItems.style.display = 'block';
        indicator.textContent = '▼';
    } else {
        sequenceItems.style.display = 'none';
        indicator.textContent = '▶';
    }
}

function displaySequenceCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-sequence-creation-results`);
    const detailsDiv = document.getElementById(`${database}-sequence-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Sequence creation results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-sequences", summary.createdCount + summary.skippedCount + summary.errorCount);

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        html += `</div>`;
        html += '</div>';

        // Show created sequences - convert Map to Array using Object.values()
        if (summary.createdCount > 0 && summary.createdSequences) {
            html += '<div class="created-tables-section">';
            html += '<h4>Created Sequences:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.createdSequences).forEach(seq => {
                html += `<div class="table-item created">${seq.sequenceName} ✓</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show skipped sequences - convert Map to Array using Object.values()
        if (summary.skippedCount > 0 && summary.skippedSequences) {
            html += '<div class="skipped-tables-section">';
            html += '<h4>Skipped Sequences (already exist):</h4>';
            html += '<div class="table-items">';
            Object.values(summary.skippedSequences).forEach(seq => {
                html += `<div class="table-item skipped">${seq.sequenceName} (${seq.reason})</div>`;
            });
            html += '</div>';
            html += '</div>';
        }

        // Show errors - convert Map to Array using Object.values()
        if (summary.errorCount > 0 && summary.errors) {
            html += '<div class="error-tables-section">';
            html += '<h4>Failed Sequences:</h4>';
            html += '<div class="table-items">';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="table-item error">`;
                html += `<strong>${error.sequenceName}</strong>: ${error.error}`;
                if (error.sql) {
                    html += `<div class="sql-statement"><pre>${error.sql}</pre></div>`;
                }
                html += `</div>`;
            });
            html += '</div>';
            html += '</div>';
        }
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleSequenceList(database) {
    const listDiv = document.getElementById(`${database}-sequence-list`);
    const toggleIndicator = listDiv.querySelector('.toggle-indicator');

    if (listDiv.style.display === 'none' || !listDiv.style.display) {
        listDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        listDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

function toggleSequenceCreationResults() {
    const resultsDiv = document.getElementById('postgres-sequence-creation-results');
    const detailsDiv = document.getElementById('postgres-sequence-creation-details');
    const toggleIndicator = resultsDiv.querySelector('.toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        toggleIndicator.textContent = '▲';
    } else {
        detailsDiv.style.display = 'none';
        toggleIndicator.textContent = '▼';
    }
}

// ===== END SEQUENCE FUNCTIONS =====

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
            updateProgress(0, 'Ready to start');

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

    // Show and initialize orchestration progress bar
    showOrchestrationProgress();
    updateOrchestrationProgress(0, 'Initializing migration...');

    try {
        // Step 1: Test Oracle connection (synchronous)
        updateOrchestrationProgress(5, 'Step 1/13: Testing Oracle connection...');
        await testOracleConnection();
        // Check if connection succeeded by looking for the "connected" status
        const oracleConnected = document.querySelector('#oracle-connection .status-indicator').classList.contains('connected');
        if (!oracleConnected) {
            throw new Error('Oracle connection test failed. Cannot proceed with migration.');
        }
        await delay(500);

        // Step 2: Test PostgreSQL connection (synchronous)
        updateOrchestrationProgress(10, 'Step 2/13: Testing PostgreSQL connection...');
        await testPostgresConnection();
        // Check if connection succeeded
        const postgresConnected = document.querySelector('#postgres-connection .status-indicator').classList.contains('connected');
        if (!postgresConnected) {
            throw new Error('PostgreSQL connection test failed. Cannot proceed with migration.');
        }
        await delay(500);

        // Step 3: Extract Oracle schemas
        updateOrchestrationProgress(15, 'Step 3/13: Extracting Oracle schemas...');
        await loadOracleSchemas();
        await pollCountBadge('oracle-schemas', { requirePositive: true, allowZero: false });
        updateOrchestrationProgress(20, 'Oracle schemas extracted');

        // Step 4: Create PostgreSQL schemas
        updateOrchestrationProgress(25, 'Step 4/13: Creating PostgreSQL schemas...');
        await createPostgresSchemas();
        await pollCountBadge('postgres-schemas', { requirePositive: true, allowZero: false });
        updateOrchestrationProgress(30, 'PostgreSQL schemas created');

        // Step 5: Extract Oracle synonyms
        updateOrchestrationProgress(35, 'Step 5/13: Extracting Oracle synonyms...');
        await loadOracleSynonyms();
        await pollCountBadge('oracle-synonyms', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(40, 'Oracle synonyms extracted');

        // Step 6: Extract Oracle object types
        updateOrchestrationProgress(45, 'Step 6/13: Extracting Oracle object types...');
        await loadOracleObjectTypes();
        await pollCountBadge('oracle-objects', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(50, 'Oracle object types extracted');

        // Step 7: Create PostgreSQL object types
        updateOrchestrationProgress(55, 'Step 7/13: Creating PostgreSQL object types...');
        await createPostgresObjectTypes();
        await pollCountBadge('postgres-objects', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(60, 'PostgreSQL object types created');

        // Step 8: Extract Oracle table metadata
        updateOrchestrationProgress(65, 'Step 8/13: Extracting Oracle table metadata...');
        await extractTableMetadata();
        await pollCountBadge('oracle-tables', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(68, 'Oracle table metadata extracted');

        // Step 9: Create PostgreSQL tables
        updateOrchestrationProgress(70, 'Step 9/13: Creating PostgreSQL tables...');
        await createPostgresTables();
        await pollCountBadge('postgres-tables', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(73, 'PostgreSQL tables created');

        // Step 10: Extract Oracle sequences
        updateOrchestrationProgress(75, 'Step 10/13: Extracting Oracle sequences...');
        await extractOracleSequences();
        await pollCountBadge('oracle-sequences', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(77, 'Oracle sequences extracted');

        // Step 11: Create PostgreSQL sequences
        updateOrchestrationProgress(80, 'Step 11/13: Creating PostgreSQL sequences...');
        await createPostgresSequences();
        await pollCountBadge('postgres-sequences', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(83, 'PostgreSQL sequences created');

        // Step 12: Extract Oracle row counts
        updateOrchestrationProgress(85, 'Step 12/13: Extracting Oracle row counts...');
        await extractOracleRowCounts();
        await pollCountBadge('oracle-data', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(90, 'Oracle row counts extracted');

        // Step 13: Transfer data from Oracle to PostgreSQL
        updateOrchestrationProgress(93, 'Step 13/13: Transferring data...');
        await transferData();
        await pollCountBadge('postgres-data', { requirePositive: false, allowZero: true });
        updateOrchestrationProgress(100, 'Data transfer completed');

        // Complete
        console.log('Full migration orchestration completed successfully');
        updateOrchestrationProgress(100, 'Migration completed successfully!');

        // Hide orchestration progress bar after a short delay
        await delay(2000);
        hideOrchestrationProgress();

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

// Helper function to wait for async job completion
// This monitors the UI state to determine when a job has finished
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

// ============================================================================
// Data Transfer Functions
// ============================================================================

async function transferData() {
    console.log('Starting data transfer job...');
    const button = document.querySelector('#postgres-data .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳';
    }

    updateComponentCount("postgres-data", "-");

    updateMessage('Starting data transfer from Oracle to PostgreSQL...');
    updateProgress(0, 'Starting data transfer');

    try {
        const response = await fetch('/api/jobs/postgres/data-transfer/create', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();
        if (result.status === 'success') {
            console.log('Data transfer job started:', result.jobId);
            updateMessage('Data transfer job started successfully');
            // Start polling for progress and AWAIT completion
            await pollDataTransferJobStatus(result.jobId);
        } else {
            throw new Error(result.message || 'Failed to start data transfer job');
        }
    } catch (error) {
        console.error('Error starting data transfer job:', error);
        updateMessage('Failed to start data transfer: ' + error.message);
        updateProgress(0, 'Failed to start data transfer');
    } finally {
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Transfer Data';
        }
    }
}

async function pollDataTransferJobStatus(jobId) {
    console.log('Polling data transfer job status:', jobId);

    return new Promise((resolve, reject) => {
        const pollOnce = async () => {
            try {
                const response = await fetch(`/api/jobs/${jobId}/status`);
                const status = await response.json();

                if (status.status === 'error') {
                    throw new Error(status.message || 'Job status check failed');
                }

                console.log('Data transfer job status:', status);

                if (status.progress) {
                    updateProgress(status.progress.percentage, status.progress.currentTask);
                    updateMessage(`${status.progress.currentTask}: ${status.progress.details}`);
                }

                if (status.isComplete) {
                    console.log('Data transfer job completed');
                    // Get final results
                    const resultResponse = await fetch(`/api/jobs/${jobId}/result`);
                    const result = await resultResponse.json();

                    if (result.status === 'success') {
                        handleDataTransferJobComplete(result);
                    } else {
                        throw new Error(result.message || 'Job completed with errors');
                    }

                    // Re-enable button
                    const button = document.querySelector('#postgres-data .action-btn');
                    if (button) {
                        button.disabled = false;
                        button.innerHTML = 'Transfer Data';
                    }

                    // Resolve the promise to signal completion
                    resolve();
                } else {
                    // Continue polling
                    setTimeout(pollOnce, 1000);
                }
            } catch (error) {
                console.error('Error polling data transfer job status:', error);
                updateMessage('Error checking data transfer progress: ' + error.message);
                updateProgress(0, 'Error checking progress');
                // Re-enable button
                const button = document.querySelector('#postgres-data .action-btn');
                if (button) {
                    button.disabled = false;
                    button.innerHTML = 'Transfer Data';
                }
                reject(error);
            }
        };

        // Start polling
        pollOnce();
    });
}

function handleDataTransferJobComplete(result) {
    console.log('Data transfer job results:', result);

    const transferredCount = result.transferredCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;
    const totalRows = result.totalRowsTransferred || 0;

    updateProgress(100, `Data transfer completed: ${transferredCount} tables, ${totalRows.toLocaleString()} rows transferred`);

    if (result.isSuccessful) {
        updateMessage(`Data transfer completed successfully: ${transferredCount} tables, ${totalRows.toLocaleString()} rows transferred`);
    } else {
        updateMessage(`Data transfer completed with errors: ${transferredCount} transferred, ${skippedCount} skipped, ${errorCount} errors`);
    }

    // Update data transfer results section
    displayDataTransferResults(result);

    // Refresh PostgreSQL row counts to show newly transferred data, not needed
    //setTimeout(() => {
    //    extractPostgresRowCounts();
    //}, 1000);
}

function displayDataTransferResults(result) {
    const resultsDiv = document.getElementById('postgres-data-transfer-results');
    const detailsDiv = document.getElementById('postgres-data-transfer-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Data transfer results container not found');
        return;
    }

    let html = '';

    if (result.summary) {
        const summary = result.summary;

        updateComponentCount("postgres-data", (summary.totalRowsTransferred || 0).toLocaleString());

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item transferred">Transferred: ${summary.transferredCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        if (summary.errorCount > 0) {
            html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
        }
        html += `<span class="stat-item total-rows">Total Rows: ${(summary.totalRowsTransferred || 0).toLocaleString()}</span>`;
        html += '</div>';

        if (summary.executionTimestamp) {
            const date = new Date(summary.executionTimestamp);
            html += `<div class="execution-time">Executed: ${date.toLocaleString()}</div>`;
        }

        html += '</div>';

        // Show transferred tables
        if (summary.transferredTables && Object.keys(summary.transferredTables).length > 0) {
            html += '<div class="created-items-section">';
            html += '<h4>Transferred Tables:</h4>';
            Object.values(summary.transferredTables).forEach(table => {
                const rowInfo = table.rowsTransferred ? ` (${table.rowsTransferred.toLocaleString()} rows)` : '';
                html += `<div class="creation-item created">${table.tableName}${rowInfo} ✓</div>`;
            });
            html += '</div>';
        }

        // Show skipped tables
        if (summary.skippedTables && Object.keys(summary.skippedTables).length > 0) {
            html += '<div class="skipped-items-section">';
            html += '<h4>Skipped Tables:</h4>';
            Object.values(summary.skippedTables).forEach(table => {
                html += `<div class="creation-item skipped">${table.tableName} (already synced or empty)</div>`;
            });
            html += '</div>';
        }

        // Show errors
        if (summary.errors && Object.keys(summary.errors).length > 0) {
            html += '<div class="error-items-section">';
            html += '<h4>Transfer Errors:</h4>';
            Object.values(summary.errors).forEach(error => {
                html += `<div class="creation-item error"><strong>${error.tableName}</strong>: ${error.error}</div>`;
            });
            html += '</div>';
        }
    } else {
        html += '<div class="no-results">No detailed results available</div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

function toggleDataTransferResults() {
    const detailsDiv = document.getElementById('postgres-data-transfer-details');
    const header = document.querySelector('#postgres-data-transfer-results .table-creation-header .toggle-indicator');

    if (detailsDiv.style.display === 'none' || !detailsDiv.style.display) {
        detailsDiv.style.display = 'block';
        if (header) header.textContent = '▼';
    } else {
        detailsDiv.style.display = 'none';
        if (header) header.textContent = '▶';
    }
}

// Load configuration when page loads
document.addEventListener('DOMContentLoaded', function() {
    // Load configuration after a short delay to ensure all other initialization is complete
    setTimeout(() => {
        loadConfiguration();
    }, 500);
});