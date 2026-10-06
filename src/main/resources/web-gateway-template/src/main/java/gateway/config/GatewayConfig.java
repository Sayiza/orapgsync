package {{packageName}}.gateway.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Gateway configuration properties.
 * <p>
 * Reads from application.yaml under the 'gateway' prefix.
 */
@ConfigMapping(prefix = "gateway")
public interface GatewayConfig {

    /**
     * The Database Access Descriptor name used in URL routing.
     * Example: /pls/{dadName}/schema.procedure
     */
    @WithDefault("{{dadName}}")
    String dadName();

    /**
     * The URL prefix for all gateway routes.
     * Default: /pls
     */
    @WithDefault("{{urlPrefix}}")
    String urlPrefix();

    /**
     * The default schema for unqualified procedure names.
     */
    @WithDefault("{{defaultSchema}}")
    String defaultSchema();
}
