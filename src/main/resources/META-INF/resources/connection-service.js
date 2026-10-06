/**
 * connection-service.js
 *
 * This module handles database connection testing for both Oracle and PostgreSQL databases.
 * It provides functions to test connections and update the UI with connection status.
 */

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
                updateComponentCount('oracle-schemas', '-');
                updateComponentCount('oracle-objects', '-');
                updateComponentCount('oracle-tables', '-');
                updateComponentCount('oracle-data', '-');
                updateComponentCount('oracle-views', '-');
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
                updateComponentCount('postgres-schemas', '-');
                updateComponentCount('postgres-objects', '-');
                updateComponentCount('postgres-tables', '-');
                updateComponentCount('postgres-data', '-');
                updateComponentCount('postgres-views', '-');
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
