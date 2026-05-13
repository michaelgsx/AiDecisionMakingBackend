package com.aidecision.backend.repository;

import com.aidecision.backend.entity.RiskIngestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskIngestRecordRepository extends JpaRepository<RiskIngestRecord, Long> {
}
