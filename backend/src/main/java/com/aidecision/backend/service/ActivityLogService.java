package com.aidecision.backend.service;

import com.aidecision.backend.dto.ActivityLogEntry;
import com.aidecision.backend.dto.ActivityLogRequest;
import com.aidecision.backend.dto.ActivityLogResponse;
import com.aidecision.backend.entity.ActivityLog;
import com.aidecision.backend.repository.ActivityLogRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class ActivityLogService {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final ActivityLogRepository repo;

    public ActivityLogService(ActivityLogRepository repo) {
        this.repo = repo;
    }

    public ActivityLogResponse append(ActivityLogRequest req) {
        String prevHash = repo.findLatestByUserId(req.userId())
                .map(ActivityLog::getHashCode)
                .orElse(GENESIS_HASH);

        String now = Instant.now().toString();
        String hashCode = computeHash(prevHash, req.userId(), req.transactionId(),
                req.bizAction(), req.recordAction(), now);

        ActivityLog entity = new ActivityLog();
        entity.setUserId(req.userId());
        entity.setTransactionId(req.transactionId());
        entity.setBizAction(req.bizAction());
        entity.setRecordAction(req.recordAction());
        entity.setPrevHash(prevHash);
        entity.setHashCode(hashCode);

        ActivityLog saved = repo.save(entity);

        return new ActivityLogResponse(true, saved.getId(), hashCode, prevHash,

                "Activity logged (" + req.bizAction() + "/" + req.recordAction() + ")");
    }

    public List<ActivityLogEntry> getByUser(String userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toEntry).toList();
    }

    public List<ActivityLogEntry> getByTransaction(String transactionId) {
        return repo.findByTransactionIdOrderByCreatedAtDesc(transactionId).stream()
                .map(this::toEntry).toList();
    }

    public List<ActivityLogEntry> getAll() {
        return repo.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toEntry).toList();
    }

    /**
     * Verify the hash chain for a given user. Returns true if every record's
     * hash_code matches the recomputed hash from its prev_hash + content.
     */
    public boolean verifyChain(String userId) {
        List<ActivityLog> chain = repo.findByUserIdOrderByCreatedAtDesc(userId);
        for (ActivityLog entry : chain) {
            String expected = computeHash(entry.getPrevHash(), entry.getUserId(),
                    entry.getTransactionId(), entry.getBizAction(),
                    entry.getRecordAction(), entry.getCreatedAt().toString());
            if (!expected.equals(entry.getHashCode())) {
                return false;
            }
        }
        return true;
    }

    // ── internal ────────────────────────────────────────────────────

    static String computeHash(String prevHash, String userId, String transactionId,
                              String bizAction, String recordAction, String timestamp) {
        String payload = String.join("|", prevHash, userId, transactionId,
                bizAction, recordAction, timestamp);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private ActivityLogEntry toEntry(ActivityLog a) {
        return new ActivityLogEntry(a.getId(), a.getUserId(), a.getTransactionId(),
                a.getBizAction(), a.getRecordAction(), a.getPrevHash(),
                a.getHashCode(), a.getCreatedAt());

    }
}
