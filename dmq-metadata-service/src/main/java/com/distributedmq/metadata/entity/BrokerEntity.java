package com.distributedmq.metadata.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA Entity for Broker metadata
 */
@Entity
@Table(name = "brokers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerEntity {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "status", nullable = false)
    private String status; // ONLINE, OFFLINE

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
        if (status == null) {
            status = "ONLINE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        // Update operations
    }

    public String getAddress() {
        return host + ":" + port;
    }
}