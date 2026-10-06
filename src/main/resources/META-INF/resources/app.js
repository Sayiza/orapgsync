// Oracle to PostgreSQL Migration Tool Frontend
// Main Application Entry Point
//
// This is the minimal entry point that initializes the application.
// All functionality has been modularized into separate service files:
//
// Core/Infrastructure:
// - ui-utils.js: UI helper functions (status, badges, progress bars)
// - config-service.js: Configuration management (load, save, reset)
// - connection-service.js: Database connection testing
// - job-service.js: Generic job polling and status management
//
// Domain-Specific Services:
// - schema-service.js: Schema extraction and creation
// - synonym-service.js: Synonym extraction
// - object-type-service.js: Object type extraction and creation
// - table-service.js: Table metadata, creation, and row counts
// - sequence-service.js: Sequence extraction and creation
// - data-transfer-service.js: Data transfer operations
//
// Orchestration:
// - orchestration-service.js: Full migration workflow and state reset

// Initialize application when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
    console.log('Oracle to PostgreSQL Migration Tool initialized');
    console.log('Version: Modular Architecture');

    // Initialize UI with default states
    initializeInterface();

    // Load configuration after a short delay to ensure all initialization is complete
    setTimeout(() => {
        loadConfiguration();
    }, 500);
});

// Step Checkbox Management
// ========================

/**
 * Toggle all step checkboxes on or off
 * @param {boolean} enabled - true to check all, false to uncheck all
 */
function toggleAllSteps(enabled) {
    const checkboxes = document.querySelectorAll('.step-checkbox');
    checkboxes.forEach(checkbox => {
        checkbox.checked = enabled;
    });
    console.log(`All step checkboxes ${enabled ? 'enabled' : 'disabled'}`);
}

/**
 * Check if a specific step is enabled
 * @param {string} stepName - The step name (e.g., 'schemas', 'functions', 'view-implementation')
 * @returns {boolean} - true if the step is enabled, false otherwise
 */
function isStepEnabled(stepName) {
    const checkbox = document.querySelector(`.step-checkbox[data-step="${stepName}"]`);
    if (!checkbox) {
        console.warn(`No checkbox found for step: ${stepName}`);
        return true; // Default to enabled if checkbox not found
    }
    return checkbox.checked;
}
