package com.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.monitoring.model.NodeHistory;

public interface HistoryRepository extends JpaRepository<NodeHistory, Long> {

    List<NodeHistory> findByNodeId(String nodeId);

}
