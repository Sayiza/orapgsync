package me.christianrobert.orapgsync.web.service;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.web.model.WebGatewayGenerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebGatewayGenerator.
 */
class WebGatewayGeneratorTest {

    @TempDir
    Path tempDir;

    private WebGatewayGenerator generator;
    private ConfigService configService;
    private StateService stateService;

    @BeforeEach
    void setup() throws Exception {
        generator = new WebGatewayGenerator();
        configService = new ConfigService();
        stateService = new StateService();

        // Set up test configuration
        configService.setConfigValue("web-gateway.output-path", tempDir.toString());
        configService.setConfigValue("web-gateway.dad-name", "testapp");
        configService.setConfigValue("web-gateway.url-prefix", "/pls");
        configService.setConfigValue("web-gateway.server-port", "8090");
        configService.setConfigValue("java.generated-package-name", "com.example.test");
        configService.setConfigValue("postgre.url", "jdbc:postgresql://localhost:5432/testdb");
        configService.setConfigValue("postgre.username", "testuser");
        configService.setConfigValue("postgre.password", "testpass");

        // Set up test schemas
        stateService.setOracleSchemaNames(Arrays.asList("HR", "SALES"));

        // Inject dependencies using reflection
        injectField(generator, "configService", configService);
        injectField(generator, "stateService", stateService);
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void generate_createsProjectStructure() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess(), "Generation should succeed");
        assertFalse(result.hasErrors(), "Should have no errors");
        assertTrue(result.getFileCount() > 0, "Should generate files");

        // Verify key files exist
        assertTrue(Files.exists(tempDir.resolve("pom.xml")), "pom.xml should exist");
        assertTrue(Files.exists(tempDir.resolve(".env")), ".env should exist");
        assertTrue(Files.exists(tempDir.resolve("src/main/resources/application.yaml")),
                "application.yaml should exist");
    }

    @Test
    void generate_replacesPlaceholders() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess());

        // Verify placeholder replacement in pom.xml
        String pomContent = Files.readString(tempDir.resolve("pom.xml"));
        assertTrue(pomContent.contains("<groupId>com.example.test</groupId>"),
                "Package name should be replaced in pom.xml");
        assertFalse(pomContent.contains("{{packageName}}"),
                "Placeholder should be replaced");

        // Verify placeholder replacement in application.yaml
        String yamlContent = Files.readString(tempDir.resolve("src/main/resources/application.yaml"));
        assertTrue(yamlContent.contains("dad-name: testapp"),
                "DAD name should be replaced");
        assertTrue(yamlContent.contains("port: 8090"),
                "Server port should be replaced");

        // Verify placeholder replacement in .env
        String envContent = Files.readString(tempDir.resolve(".env"));
        assertTrue(envContent.contains("ENV_CO_DB_JDBC_URL=jdbc:postgresql://localhost:5432/testdb"),
                "Database URL should be replaced");
        assertTrue(envContent.contains("ENV_CO_DB_USERNAME=testuser"),
                "Database username should be replaced");
    }

    @Test
    void generate_createsJavaFilesWithCorrectPackage() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess());

        // Verify Java files exist with correct package path
        Path javaRoot = tempDir.resolve("src/main/java/com/example/test/gateway");
        assertTrue(Files.exists(javaRoot.resolve("GatewayApplication.java")),
                "GatewayApplication.java should exist");
        assertTrue(Files.exists(javaRoot.resolve("config/GatewayConfig.java")),
                "GatewayConfig.java should exist");
        assertTrue(Files.exists(javaRoot.resolve("routing/PlsqlRouter.java")),
                "PlsqlRouter.java should exist");
        assertTrue(Files.exists(javaRoot.resolve("execution/PlsqlExecutor.java")),
                "PlsqlExecutor.java should exist");

        // Verify package declaration is correct
        String appContent = Files.readString(javaRoot.resolve("GatewayApplication.java"));
        assertTrue(appContent.contains("package com.example.test.gateway;"),
                "Package declaration should be correct");
    }

    @Test
    void generate_usesFirstSchemaAsDefault() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess());

        // Verify default schema in application.yaml (first schema, lowercase)
        String yamlContent = Files.readString(tempDir.resolve("src/main/resources/application.yaml"));
        assertTrue(yamlContent.contains("default-schema: hr"),
                "Default schema should be first Oracle schema (lowercase)");
    }

    @Test
    void generate_failsIfDirectoryNotEmpty() throws IOException {
        // Given: Create a file in the output directory
        Files.writeString(tempDir.resolve("existing-file.txt"), "content");

        // When/Then
        assertThrows(IllegalStateException.class, () -> generator.generate(),
                "Should fail if directory is not empty");
    }

    @Test
    void generate_succeedsWithEmptyDirectory() throws IOException {
        // Given: Empty directory (default state)

        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess(), "Should succeed with empty directory");
    }

    @Test
    void generate_reportsExecutionTime() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.getExecutionTimeMs() >= 0, "Execution time should be recorded");
    }

    @Test
    void generate_listsAllGeneratedFiles() throws IOException {
        // When
        WebGatewayGenerationResult result = generator.generate();

        // Then
        assertTrue(result.isSuccess());
        assertTrue(result.getGeneratedFiles().contains("pom.xml"), "Should list pom.xml");
        assertTrue(result.getGeneratedFiles().contains(".env"), "Should list .env");
        assertTrue(result.getGeneratedFiles().stream()
                        .anyMatch(f -> f.contains("GatewayApplication.java")),
                "Should list GatewayApplication.java");
    }
}
