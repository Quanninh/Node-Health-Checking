package com.monitoring.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.monitoring.model.Node;

@Repository
public interface NodeRepository extends JpaRepository<Node, String> {

}
