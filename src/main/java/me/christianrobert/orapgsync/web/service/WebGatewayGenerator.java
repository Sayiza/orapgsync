package me.christianrobert.orapgsync.web.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.web.model.WebGatewayGenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the Quarkus web gateway project from templates.
 * <p>
 * Reads template files from resources, replaces placeholders with configuration
 * values, and writes the generated project to the output directory.
 */
@ApplicationScoped
public class WebGatewayGenerator {

    private static final Logger log = LoggerFactory.getLogger(WebGatewayGenerator.class);

    private static final String TEMPLATE_PATH = "web-gateway-template";

    @Inject
    ConfigService configService;

    @Inject
    StateService stateService;

    /**
     * Generate the web gateway project.
     *
     * @return Generation result with list of created files
     * @throws IOException if file operations fail
     * @throws IllegalStateException if output directory is not empty
     */
    public WebGatewayGenerationResult generate() throws IOException {
        long startTime = System.currentTimeMillis();
        WebGatewayGenerationResult result = new WebGatewayGenerationResult();

        // Get configuration
        String outputPath = configService.getConfigValueAsString("web-gateway.output-path");
        String dadName = configService.getConfigValueAsString("web-gateway.dad-name");
        String urlPrefix = configService.getConfigValueAsString("web-gateway.url-prefix");
        String serverPort = configService.getConfigValueAsString("web-gateway.server-port");
        String packageName = configService.getConfigValueAsString("java.generated-package-name");

        // Get database config for .env file
        String dbUrl = configService.getConfigValueAsString("postgre.url");
        String dbUsername = configService.getConfigValueAsString("postgre.username");
        String dbPassword = configService.getConfigValueAsString("postgre.password");

        // Get default schema from state (first schema or "public")
        String defaultSchema = getDefaultSchema();

        result.setOutputPath(outputPath);

        log.info("Generating web gateway project to: {}", outputPath);
        log.info("Configuration: dadName={}, urlPrefix={}, serverPort={}, packageName={}",
                dadName, urlPrefix, serverPort, packageName);

        // Validate output directory
        Path outputDir = Paths.get(outputPath);
        validateOutputDirectory(outputDir);

        // Build placeholder replacements
        Map<String, String> replacements = new HashMap<>();
        replacements.put("{{packageName}}", packageName);
        replacements.put("{{dadName}}", dadName);
        replacements.put("{{urlPrefix}}", urlPrefix);
        replacements.put("{{serverPort}}", serverPort);
        replacements.put("{{defaultSchema}}", defaultSchema);
        replacements.put("{{dbJdbcUrl}}", dbUrl);
        replacements.put("{{dbUsername}}", dbUsername);
        replacements.put("{{dbPassword}}", dbPassword);

        try {
            // Create output directory
            Files.createDirectories(outputDir);

            // Generate all files from templates
            generateFromTemplates(outputDir, replacements, result);

            // Generate .env file (not a template, created directly)
            generateEnvFile(outputDir, replacements, result);

            result.setSuccess(true);
            log.info("Web gateway project generated successfully: {} files created",
                    result.getFileCount());

        } catch (Exception e) {
            log.error("Failed to generate web gateway project", e);
            result.addError(e.getMessage());
            result.setSuccess(false);
        }

        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * Validate the output directory is empty or doesn't exist.
     */
    private void validateOutputDirectory(Path outputDir) throws IOException {
        if (Files.exists(outputDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
                if (stream.iterator().hasNext()) {
                    throw new IllegalStateException(
                            "Output directory is not empty: " + outputDir +
                            ". Please use an empty directory or delete existing contents.");
                }
            }
        }
    }

    /**
     * Get the default schema from state, or "public" if none available.
     */
    private String getDefaultSchema() {
        List<String> schemas = stateService.getOracleSchemaNames();
        if (schemas != null && !schemas.isEmpty()) {
            return schemas.get(0).toLowerCase();
        }
        return "public";
    }

    /**
     * Generate files from templates in resources.
     */
    private void generateFromTemplates(
            Path outputDir,
            Map<String, String> replacements,
            WebGatewayGenerationResult result) throws IOException {

        // List of template files to process
        // We need to manually list them since we can't easily scan resources at runtime
        String[] templateFiles = {
                "pom.xml",
                "src/main/resources/application.yaml",
                "src/main/java/gateway/GatewayApplication.java",
                "src/main/java/gateway/config/GatewayConfig.java",
                "src/main/java/gateway/routing/PlsqlRouter.java",
                "src/main/java/gateway/execution/PlsqlExecutor.java"
        };

        String packageName = replacements.get("{{packageName}}");
        String packagePath = packageName.replace(".", "/");

        for (String templateFile : templateFiles) {
            String resourcePath = TEMPLATE_PATH + "/" + templateFile;

            // Adjust output path for Java files (replace "gateway" with actual package path)
            String outputFile = templateFile;
            if (templateFile.contains("src/main/java/gateway/")) {
                outputFile = templateFile.replace(
                        "src/main/java/gateway/",
                        "src/main/java/" + packagePath + "/gateway/");
            }

            try {
                // Read template from resources
                String content = readResourceFile(resourcePath);

                // Replace placeholders
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    content = content.replace(entry.getKey(), entry.getValue());
                }

                // Write to output
                Path outputPath = outputDir.resolve(outputFile);
                Files.createDirectories(outputPath.getParent());
                Files.writeString(outputPath, content, StandardCharsets.UTF_8);

                result.addGeneratedFile(outputFile);
                log.debug("Generated: {}", outputFile);

            } catch (Exception e) {
                log.error("Failed to process template: {}", templateFile, e);
                result.addError("Failed to process " + templateFile + ": " + e.getMessage());
            }
        }
    }

    /**
     * Generate the .env file with database credentials.
     */
    private void generateEnvFile(
            Path outputDir,
            Map<String, String> replacements,
            WebGatewayGenerationResult result) throws IOException {

        try {
            // Read .env template
            String content = readResourceFile(TEMPLATE_PATH + "/.env.template");

            // Replace placeholders
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                content = content.replace(entry.getKey(), entry.getValue());
            }

            // Write .env file
            Path envPath = outputDir.resolve(".env");
            Files.writeString(envPath, content, StandardCharsets.UTF_8);

            result.addGeneratedFile(".env");
            log.debug("Generated: .env");

        } catch (Exception e) {
            log.error("Failed to generate .env file", e);
            result.addError("Failed to generate .env: " + e.getMessage());
        }
    }

    /**
     * Read a file from the classpath resources.
     */
    private String readResourceFile(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
