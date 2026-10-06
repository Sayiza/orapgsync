/**
 * Web Gateway Service Module
 *
 * This module handles generation of the Quarkus web gateway project that replaces
 * Oracle mod_plsql functionality. The gateway routes HTTP requests to PostgreSQL
 * functions that use HTP/HTF compatibility layer to generate web content.
 *
 * Key Responsibilities:
 * - Generate: Create the Quarkus web gateway project from templates
 * - Display: Show generation results in the UI
 * - Configuration: Get/update web gateway configuration
 *
 * Functions included:
 * - generateWebGateway(): Generate the web gateway project
 * - displayWebGatewayResults(): Display generation results
 * - toggleWebGatewayResults(): Toggle visibility of generation results
 * - getWebGatewayConfig(): Retrieve current configuration
 */

// Generate the Quarkus web gateway project
async function generateWebGateway() {
    console.log('Starting web gateway generation...');

    updateComponentCount("postgres-web-gateway", "-");

    const button = document.querySelector('#postgres-web-gateway .action-btn');
    if (button) {
        button.disabled = true;
        button.innerHTML = '⏳ Generating...';
    }

    updateMessage('Generating web gateway project...');
    updateProgress(0, 'Generating web gateway project');

    try {
        const response = await fetch('/api/web-gateway/generate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const result = await response.json();

        if (response.ok && result.success) {
            console.log('Web gateway generated successfully:', result);
            updateMessage(`Web gateway generated: ${result.fileCount} files created`);
            updateProgress(100, 'Web gateway generation completed');

            // Display results
            displayWebGatewayResults(result);

            // Update count badge
            updateComponentCount("postgres-web-gateway", result.fileCount);

        } else if (response.status === 409) {
            // Directory not empty error
            console.warn('Web gateway generation failed - directory not empty:', result);
            updateMessage('Generation failed: ' + (result.error || 'Output directory is not empty'));
            updateProgress(0, 'Generation failed - directory not empty');
            displayWebGatewayError(result.error || 'Output directory is not empty');
            updateComponentCount("postgres-web-gateway", "!");

        } else {
            throw new Error(result.error || 'Failed to generate web gateway project');
        }

    } catch (error) {
        console.error('Error generating web gateway:', error);
        updateMessage('Failed to generate web gateway: ' + error.message);
        updateProgress(0, 'Web gateway generation failed');
        displayWebGatewayError(error.message);
        updateComponentCount("postgres-web-gateway", "!");

    } finally {
        // Re-enable button
        if (button) {
            button.disabled = false;
            button.innerHTML = 'Generate Web Gateway';
        }
    }
}

// Display web gateway generation results
function displayWebGatewayResults(result) {
    const resultsDiv = document.getElementById('postgres-web-gateway-generation-results');
    const detailsDiv = document.getElementById('postgres-web-gateway-generation-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Web gateway results container not found');
        return;
    }

    let html = `
        <div class="table-creation-summary">
            <p><strong>Output Path:</strong> ${result.outputPath || 'N/A'}</p>
            <div class="summary-stats">
                <span class="stat-item created">Files Generated: ${result.fileCount || 0}</span>
            </div>
            <p style="margin-top: 0.5rem;"><strong>Execution Time:</strong> ${result.executionTimeMs || 0}ms</p>
        </div>
    `;

    // List generated files
    if (result.generatedFiles && result.generatedFiles.length > 0) {
        html += '<div class="created-items-section"><h4>Generated Files:</h4><div class="table-items">';
        result.generatedFiles.forEach(file => {
            html += `<div class="creation-item created">✓ ${file}</div>`;
        });
        html += '</div></div>';
    }

    // Show any errors
    if (result.errors && result.errors.length > 0) {
        html += '<div class="error-items-section"><h4>Errors:</h4><div class="table-items">';
        result.errors.forEach(error => {
            html += `<div class="creation-item error">✗ ${error}</div>`;
        });
        html += '</div></div>';
    }

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

// Display web gateway error
function displayWebGatewayError(errorMessage) {
    const resultsDiv = document.getElementById('postgres-web-gateway-generation-results');
    const detailsDiv = document.getElementById('postgres-web-gateway-generation-details');

    if (!resultsDiv || !detailsDiv) {
        console.error('Web gateway results container not found');
        return;
    }

    let html = `
        <div class="table-creation-summary">
            <div class="summary-stats">
                <span class="stat-item errors">Generation Failed</span>
            </div>
        </div>
        <div class="error-items-section">
            <h4>Error:</h4>
            <div class="table-items">
                <div class="creation-item error">✗ ${errorMessage}</div>
            </div>
        </div>
    `;

    detailsDiv.innerHTML = html;
    resultsDiv.style.display = 'block';
}

// Toggle web gateway results visibility
function toggleWebGatewayResults() {
    const details = document.getElementById('postgres-web-gateway-generation-details');
    const header = document.querySelector('#postgres-web-gateway-generation-results .table-creation-header');

    if (details && header) {
        const isVisible = details.style.display !== 'none';
        details.style.display = isVisible ? 'none' : 'block';

        const indicator = header.querySelector('.toggle-indicator');
        if (indicator) {
            indicator.textContent = isVisible ? '▶' : '▼';
        }
    }
}

// Get current web gateway configuration
async function getWebGatewayConfig() {
    try {
        const response = await fetch('/api/web-gateway/config');
        const config = await response.json();
        console.log('Web gateway config:', config);
        return config;
    } catch (error) {
        console.error('Error getting web gateway config:', error);
        return null;
    }
}
