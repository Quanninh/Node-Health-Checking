package com.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.monitoring.model.NodeHistory;

public interface NodeHistoryRepository extends JpaRepository<NodeHistory, Long> {

    List<NodeHistory> findByNodeId(String nodeId);

}
