package com.monitoring.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.monitoring.model.FailureReport;

@Repository
public interface FailureReportRepository extends JpaRepository<FailureReport, Long> {

    List<FailureReport> findByReporterNodeId(String reporterNodeId);

    List<FailureReport> findByFailedNodeId(String failedNodeId);

}