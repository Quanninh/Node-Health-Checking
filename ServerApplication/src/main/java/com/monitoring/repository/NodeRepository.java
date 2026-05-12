package com.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.monitoring.model.Node;

public interface NodeRepository extends JpaRepository<Node, String> {

}
