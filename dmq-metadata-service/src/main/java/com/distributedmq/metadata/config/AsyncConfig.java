package com.distributedmq.metadata.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async configuration for database persistence operations
 * Enables non-blocking database writes that don't block Raft consensus
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor for database persistence operations
     * Leader-only async writes to PostgreSQL backup
     */
    @Bean(name = "dbPersistenceExecutor")
    public Executor dbPersistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core pool size - minimum threads always alive
        executor.setCorePoolSize(2);
        
        // Max pool size - maximum threads under load
        executor.setMaxPoolSize(5);
        
        // Queue capacity - pending tasks before rejecting
        executor.setQueueCapacity(100);
        
        // Thread naming for debugging
        executor.setThreadNamePrefix("db-persist-");
        
        // Rejection policy - caller runs if queue full (back-pressure)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        log.info("Database persistence executor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }

    /**
     * Executor for metadata push operations to storage services
     */
    @Bean(name = "metadataPushExecutor")
    public Executor metadataPushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("metadata-push-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        
        log.info("Metadata push executor initialized: core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }
}
