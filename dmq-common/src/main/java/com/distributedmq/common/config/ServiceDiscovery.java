package com.distributedmq.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Service discovery utility that reads from centralized config file
 */
@Slf4j
public class ServiceDiscovery {

    private static final String[] CONFIG_FILE_PATHS = {
        "config/services.json",           // From project root
        "../config/services.json",        // From service subdirectories
        "../../config/services.json",     // From target directory (CLI)
        "src/main/resources/services.json" // From classpath
    };
    private static ServiceConfig config;

    static {
        loadConfig();
    }

    public static void loadConfig() {
        ObjectMapper mapper = new ObjectMapper();

        for (String configPath : CONFIG_FILE_PATHS) {
            try {
                File configFile = new File(configPath);
                log.info("Trying to load config from: {} (exists: {}, absolute: {})",
                        configPath, configFile.exists(), configFile.getAbsolutePath());
                if (configFile.exists()) {
                    config = mapper.readValue(configFile, ServiceConfig.class);
                    log.info("Loaded service configuration from {}", configFile.getAbsolutePath());
                    return;
                }
            } catch (IOException e) {
                log.debug("Failed to load config from {}: {}", configPath, e.getMessage());
            }
        }

        log.warn("Could not load service configuration from any known path, using defaults");
        // Create default config if file doesn't exist
        config = createDefaultConfig();
    }

    private static ServiceConfig createDefaultConfig() {
        ServiceConfig defaultConfig = new ServiceConfig();

        // Default metadata services
        ServiceInfo ms1 = new ServiceInfo();
        ms1.setId(1);
        ms1.setHost("localhost");
        ms1.setPort(9091);
        ms1.setUrl("http://localhost:9091");

        ServiceInfo ms2 = new ServiceInfo();
        ms2.setId(2);
        ms2.setHost("localhost");
        ms2.setPort(9092);
        ms2.setUrl("http://localhost:9092");

        ServiceInfo ms3 = new ServiceInfo();
        ms3.setId(3);
        ms3.setHost("localhost");
        ms3.setPort(9093);
        ms3.setUrl("http://localhost:9093");

        defaultConfig.getServices().getMetadataServices().add(ms1);
        defaultConfig.getServices().getMetadataServices().add(ms2);
        defaultConfig.getServices().getMetadataServices().add(ms3);

        // Default storage services
        StorageServiceInfo ss1 = new StorageServiceInfo();
        ss1.setId(101);
        ss1.setHost("localhost");
        ss1.setPort(8081);
        ss1.setUrl("http://localhost:8081");

        StorageServiceInfo ss2 = new StorageServiceInfo();
        ss2.setId(102);
        ss2.setHost("localhost");
        ss2.setPort(8082);
        ss2.setUrl("http://localhost:8082");

        StorageServiceInfo ss3 = new StorageServiceInfo();
        ss3.setId(103);
        ss3.setHost("localhost");
        ss3.setPort(8083);
        ss3.setUrl("http://localhost:8083");

        defaultConfig.getServices().getStorageServices().add(ss1);
        defaultConfig.getServices().getStorageServices().add(ss2);
        defaultConfig.getServices().getStorageServices().add(ss3);

        // Default JWT config
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setSecret("dmq-secret-key-256-bits-for-hs256-signature-algorithm-change-in-production-please");
        jwtConfig.setAlgorithm("HS256");
        jwtConfig.setAccessTokenExpirySeconds(900);
        defaultConfig.setJwt(jwtConfig);

        return defaultConfig;
    }

    public static String getMetadataServiceUrl(Integer serviceId) {
        return config.getServices().getMetadataServices().stream()
                .filter(s -> s.getId().equals(serviceId))
                .findFirst()
                .map(ServiceInfo::getUrl)
                .orElse(null);
    }

    public static String getStorageServiceUrl(Integer serviceId) {
        return config.getServices().getStorageServices().stream()
                .filter(s -> s.getId().equals(serviceId))
                .findFirst()
                .map(StorageServiceInfo::getUrl)
                .orElse(null);
    }

    public static List<ServiceInfo> getAllMetadataServices() {
        return config.getServices().getMetadataServices();
    }

    public static List<StorageServiceInfo> getAllStorageServices() {
        return config.getServices().getStorageServices();
    }

    public static ServiceInfo getMetadataServiceById(Integer id) {
        return config.getServices().getMetadataServices().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public static StorageServiceInfo getStorageServiceById(Integer id) {
        return config.getServices().getStorageServices().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get JWT secret from configuration
     */
    public static String getJwtSecret() {
        JwtConfig jwtConfig = config.getJwt();
        return jwtConfig != null ? jwtConfig.getSecret() : null;
    }

    /**
     * Get JWT algorithm from configuration
     */
    public static String getJwtAlgorithm() {
        JwtConfig jwtConfig = config.getJwt();
        return jwtConfig != null ? jwtConfig.getAlgorithm() : "HS256";
    }

    // Config classes
    public static class ServiceConfig {
        private Services services = new Services();
        private ControllerConfig controller = new ControllerConfig();
        private MetadataConfig metadata = new MetadataConfig();
        private JwtConfig jwt = new JwtConfig();

        public Services getServices() { return services; }
        public void setServices(Services services) { this.services = services; }
        public ControllerConfig getController() { return controller; }
        public void setController(ControllerConfig controller) { this.controller = controller; }
        public MetadataConfig getMetadata() { return metadata; }
        public void setMetadata(MetadataConfig metadata) { this.metadata = metadata; }
        public JwtConfig getJwt() { return jwt; }
        public void setJwt(JwtConfig jwt) { this.jwt = jwt; }
    }

    public static class Services {
        @JsonProperty("metadata-services")
        private List<ServiceInfo> metadataServices = new java.util.ArrayList<>();
        @JsonProperty("storage-services")
        private List<StorageServiceInfo> storageServices = new java.util.ArrayList<>();

        public List<ServiceInfo> getMetadataServices() { return metadataServices; }
        public void setMetadataServices(List<ServiceInfo> metadataServices) { this.metadataServices = metadataServices; }
        public List<StorageServiceInfo> getStorageServices() { return storageServices; }
        public void setStorageServices(List<StorageServiceInfo> storageServices) { this.storageServices = storageServices; }
    }

    public static class ServiceInfo {
        private Integer id;
        private String host;
        private Integer port;
        private String url;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class StorageServiceInfo extends ServiceInfo {
        // No additional fields - storage services discover controller dynamically
    }

    public static class ControllerConfig {
        private Integer electionTimeoutMs = 5000;

        public Integer getElectionTimeoutMs() { return electionTimeoutMs; }
        public void setElectionTimeoutMs(Integer electionTimeoutMs) { this.electionTimeoutMs = electionTimeoutMs; }
    }

    public static class MetadataConfig {
        private SyncConfig sync = new SyncConfig();

        public SyncConfig getSync() { return sync; }
        public void setSync(SyncConfig sync) { this.sync = sync; }
    }

    public static class SyncConfig {
        private Integer syncTimeoutMs = 30000;

        public Integer getSyncTimeoutMs() { return syncTimeoutMs; }
        public void setSyncTimeoutMs(Integer syncTimeoutMs) { this.syncTimeoutMs = syncTimeoutMs; }
    }

    public static class JwtConfig {
        private String secret;
        private String algorithm = "HS256";
        // Centralized access-token expiry (seconds)
        @JsonProperty("access-token-expiry-seconds")
        private long accessTokenExpirySeconds = 900;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public long getAccessTokenExpirySeconds() { return accessTokenExpirySeconds; }
        public void setAccessTokenExpirySeconds(long accessTokenExpirySeconds) { this.accessTokenExpirySeconds = accessTokenExpirySeconds; }
    }

    /**
     * Get JWT access token expiry in seconds (centralized value)
     */
    public static long getJwtExpirySeconds() {
        JwtConfig jwtConfig = config.getJwt();
        return jwtConfig != null ? jwtConfig.getAccessTokenExpirySeconds() : 900L;
    }
}