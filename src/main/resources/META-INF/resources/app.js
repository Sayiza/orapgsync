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

// Extract Oracle object data types using job-based approach
async function loadOracleObjectTypes() {
    console.log('Starting Oracle object data type extraction job...');
    updateMessage('Starting Oracle object data type extraction...');

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

// Poll job status until completion (for object data type extraction)
async function pollJobUntilComplete(jobId, database, jobType) {
    console.log('Polling job status for:', jobId, 'database:', database, 'type:', jobType);

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
                console.log('Object data type extraction job completed successfully');
                updateMessage(`${database.charAt(0).toUpperCase() + database.slice(1)} object data type extraction completed`);

                // Get job results for object data types
                await getObjectDataTypeJobResults(jobId, database);
            } else if (result.status === 'FAILED') {
                console.error('Object data type extraction job failed:', result.error);
                updateComponentCount(`${database}-objects`, '!', 'error');
                updateMessage(`Object data type extraction failed: ${result.error || 'Unknown error'}`);
            }

            // Re-enable refresh button
            const button = document.querySelector(`#${database}-objects .refresh-btn`);
            if (button) {
                button.disabled = false;
                button.innerHTML = '↻';
            }
        } else {
            // Continue polling
            setTimeout(() => pollJobUntilComplete(jobId, database, jobType), 1000);
        }

    } catch (error) {
        console.error('Error polling object data type job status:', error);
        updateComponentCount(`${database}-objects`, '!', 'error');
        updateMessage(`Error checking object data type job status: ${error.message}`);

        // Re-enable button
        const button = document.querySelector(`#${database}-objects .refresh-btn`);
        if (button) {
            button.disabled = false;
            button.innerHTML = '↻';
        }
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
                    tableItem.innerHTML = `${table.name} (${table.columnCount} cols, ${table.constraintCount} constraints)`;
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

            // Start polling for progress
            pollSchemaCreationJobStatus(result.jobId, 'postgres');
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

        } else {
            // Continue polling
            setTimeout(() => pollSchemaCreationJobStatus(jobId, database), 1000);
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
    }
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

    // Refresh PostgreSQL schemas to show newly created ones
    setTimeout(() => {
        loadPostgresSchemas();
    }, 1000);
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
            // Start polling for progress
            pollObjectTypeCreationJobStatus(result.jobId, 'postgres');
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
        } else {
            // Continue polling
            setTimeout(() => pollObjectTypeCreationJobStatus(jobId, database), 1000);
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
    }
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

    // Refresh PostgreSQL object types to show newly created ones
    setTimeout(() => {
        loadPostgresObjectTypes();
    }, 1000);
}

function displayObjectTypeCreationResults(result, database) {
    const resultsDiv = document.getElementById(`${database}-object-type-creation-results`);
    const detailsDiv = document.getElementById(`${database}-object-type-creation-details`);

    if (!resultsDiv || !detailsDiv) {
        console.error('Object type creation results container not found');
        return;
    }

    let html = '';

    const createdCount = result.createdCount || 0;
    const skippedCount = result.skippedCount || 0;
    const errorCount = result.errorCount || 0;

    html += '<div class="object-type-creation-summary">';
    html += `<div class="summary-stats">`;
    html += `<span class="stat-item created">Created: ${createdCount}</span>`;
    html += `<span class="stat-item skipped">Skipped: ${skippedCount}</span>`;
    html += `<span class="stat-item errors">Errors: ${errorCount}</span>`;
    html += `</div>`;
    html += '</div>';

    // Show created object types
    if (createdCount > 0 && result.createdTypes) {
        html += '<div class="created-types-section">';
        html += '<h4>Created Object Types:</h4>';
        html += '<div class="object-type-items">';
        result.createdTypes.forEach(typeName => {
            html += `<div class="object-type-item created">${typeName} ✓</div>`;
        });
        html += '</div>';
        html += '</div>';
    }

    // Show skipped object types
    if (skippedCount > 0 && result.skippedTypes) {
        html += '<div class="skipped-types-section">';
        html += '<h4>Skipped Object Types (already exist):</h4>';
        html += '<div class="object-type-items">';
        result.skippedTypes.forEach(typeName => {
            html += `<div class="object-type-item skipped">${typeName} (already exists)</div>`;
        });
        html += '</div>';
        html += '</div>';
    }

    // Show errors
    if (errorCount > 0 && result.errors) {
        html += '<div class="error-types-section">';
        html += '<h4>Failed Object Types:</h4>';
        html += '<div class="object-type-items">';
        result.errors.forEach(error => {
            html += `<div class="object-type-item error">`;
            html += `<strong>${error.typeName}:</strong> ${error.errorMessage}`;
            if (error.sqlStatement) {
                html += `<br><code>${error.sqlStatement}</code>`;
            }
            html += `</div>`;
        });
        html += '</div>';
        html += '</div>';
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
            // Start polling for progress
            pollTableCreationJobStatus(result.jobId, 'postgres');
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
        } else {
            // Continue polling
            setTimeout(() => pollTableCreationJobStatus(jobId, database), 1000);
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
    }
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

    // Refresh PostgreSQL tables to show newly created ones
    setTimeout(() => {
        extractPostgresTableMetadata();
    }, 1000);
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

        html += '<div class="table-creation-summary">';
        html += `<div class="summary-stats">`;
        html += `<span class="stat-item created">Created: ${summary.createdCount}</span>`;
        html += `<span class="stat-item skipped">Skipped: ${summary.skippedCount}</span>`;
        html += `<span class="stat-item errors">Errors: ${summary.errorCount}</span>`;
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

// Load configuration when page loads
document.addEventListener('DOMContentLoaded', function() {
    // Load configuration after a short delay to ensure all other initialization is complete
    setTimeout(() => {
        loadConfiguration();
    }, 500);
});