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

// Load Oracle schemas
async function loadOracleSchemas() {
    console.log('Loading Oracle schemas...');
    updateMessage('Loading Oracle schemas...');

    try {
        const response = await fetch('/api/schemas/oracle');
        const result = await response.json();

        if (result.status === 'success') {
            updateComponentCount('oracle-schemas', result.count);
            populateSchemaList('oracle', result.schemas);
            updateMessage(`Loaded ${result.count} Oracle schemas`);

            // Show the schema list if we have schemas
            if (result.count > 0) {
                document.getElementById('oracle-schema-list').style.display = 'block';
            }
        } else {
            updateComponentCount('oracle-schemas', '!', 'error');
            updateMessage('Failed to load Oracle schemas: ' + result.message);
        }

    } catch (error) {
        console.error('Error loading Oracle schemas:', error);
        updateComponentCount('oracle-schemas', '!', 'error');
        updateMessage('Error loading Oracle schemas: ' + error.message);
    }
}

// Load PostgreSQL schemas
async function loadPostgresSchemas() {
    console.log('Loading PostgreSQL schemas...');
    updateMessage('Loading PostgreSQL schemas...');

    try {
        const response = await fetch('/api/schemas/postgres');
        const result = await response.json();

        if (result.status === 'success') {
            updateComponentCount('postgres-schemas', result.count);
            populateSchemaList('postgres', result.schemas);
            updateMessage(`Loaded ${result.count} PostgreSQL schemas`);

            // Show the schema list if we have schemas
            if (result.count > 0) {
                document.getElementById('postgres-schema-list').style.display = 'block';
            }
        } else {
            updateComponentCount('postgres-schemas', '!', 'error');
            updateMessage('Failed to load PostgreSQL schemas: ' + result.message);
        }

    } catch (error) {
        console.error('Error loading PostgreSQL schemas:', error);
        updateComponentCount('postgres-schemas', '!', 'error');
        updateMessage('Error loading PostgreSQL schemas: ' + error.message);
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

// Load Oracle object data types
async function loadOracleObjectTypes() {
    console.log('Loading Oracle object data types...');
    updateMessage('Loading Oracle object data types...');

    try {
        const response = await fetch('/api/objectdatatypes/oracle');
        const result = await response.json();

        if (result.status === 'success') {
            updateComponentCount('oracle-objects', result.totalCount);
            populateObjectTypeList('oracle', result.objectDataTypesBySchema);
            updateMessage(`Loaded ${result.totalCount} Oracle object data types`);

            // Show the object list if we have object data types
            if (result.totalCount > 0) {
                document.getElementById('oracle-object-list').style.display = 'block';
            }
        } else {
            updateComponentCount('oracle-objects', '!', 'error');
            updateMessage('Failed to load Oracle object data types: ' + result.message);
        }

    } catch (error) {
        console.error('Error loading Oracle object data types:', error);
        updateComponentCount('oracle-objects', '!', 'error');
        updateMessage('Error loading Oracle object data types: ' + error.message);
    }
}

// Load PostgreSQL object data types
async function loadPostgresObjectTypes() {
    console.log('Loading PostgreSQL object data types...');
    updateMessage('Loading PostgreSQL object data types...');

    try {
        const response = await fetch('/api/objectdatatypes/postgres');
        const result = await response.json();

        if (result.status === 'success') {
            updateComponentCount('postgres-objects', result.totalCount);
            populateObjectTypeList('postgres', result.objectDataTypesBySchema);
            updateMessage(`Loaded ${result.totalCount} PostgreSQL object data types`);

            // Show the object list if we have object data types
            if (result.totalCount > 0) {
                document.getElementById('postgres-object-list').style.display = 'block';
            }
        } else {
            updateComponentCount('postgres-objects', '!', 'error');
            updateMessage('Failed to load PostgreSQL object data types: ' + result.message);
        }

    } catch (error) {
        console.error('Error loading PostgreSQL object data types:', error);
        updateComponentCount('postgres-objects', '!', 'error');
        updateMessage('Error loading PostgreSQL object data types: ' + error.message);
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

// Load configuration when page loads
document.addEventListener('DOMContentLoaded', function() {
    // Load configuration after a short delay to ensure all other initialization is complete
    setTimeout(() => {
        loadConfiguration();
    }, 500);
});