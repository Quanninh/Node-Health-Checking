package com.monitoring.repository;

import com.monitoring.model.NodeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NodeHistoryRepository extends JpaRepository<NodeHistory, Long> {
    List<NodeHistory> findByNodeId(String nodeId);
}
