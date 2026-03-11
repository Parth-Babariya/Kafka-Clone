package com.distributedmq.metadata.repository;

import com.distributedmq.metadata.entity.BrokerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for Broker entities
 */
@Repository
public interface BrokerRepository extends JpaRepository<BrokerEntity, Integer> {

    /**
     * Find brokers by status
     */
    List<BrokerEntity> findByStatus(String status);

    /**
     * Check if broker exists by ID
     */
    boolean existsById(Integer id);
}