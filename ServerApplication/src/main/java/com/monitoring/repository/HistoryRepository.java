package com.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.monitoring.model.NodeHistory;

@Repository
public interface HistoryRepository extends JpaRepository<NodeHistory, Long> {

    List<NodeHistory> findByNodeId(String nodeId);

}
