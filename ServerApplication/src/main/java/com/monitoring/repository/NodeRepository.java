package com.monitoring.repository;
import com.monitoring.model.Node;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<Node, String> {
}

