package com.distributedmq.metadata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Web configuration for metadata service
 * Provides HTTP client beans
 */
@Configuration
public class WebConfig {

    /**
     * RestTemplate bean for HTTP communication with storage nodes
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}