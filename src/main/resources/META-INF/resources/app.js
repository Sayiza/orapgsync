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
