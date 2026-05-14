package com.aidecision.backend.repository;

import com.aidecision.backend.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    List<ActivityLog> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ActivityLog> findByTransactionIdOrderByCreatedAtDesc(String transactionId);

    @Query("SELECT a FROM ActivityLog a WHERE a.userId = :userId ORDER BY a.id DESC LIMIT 1")
    Optional<ActivityLog> findLatestByUserId(@Param("userId") String userId);

    List<ActivityLog> findAllByOrderByCreatedAtDesc();
}
