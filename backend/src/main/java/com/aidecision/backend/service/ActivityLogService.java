package com.aidecision.backend.service;

import com.aidecision.backend.dto.ActivityLogEntry;
import com.aidecision.backend.dto.ActivityLogRequest;
import com.aidecision.backend.dto.ActivityLogResponse;
import com.aidecision.backend.entity.ActivityLog;
import com.aidecision.backend.repository.ActivityLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);

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

    /**
     * Best-effort hash-chain append for server-side API flows (ingest / assess).
     * Skips when {@code userId} is blank. Never throws to callers.
     */
    public void tryAppendFromApi(String userId, String transactionId, String bizAction, String recordAction) {
        if (userId == null || userId.isBlank()) {
            log.debug("Skipping activity autolog: blank userId");
            return;
        }
        String uid = truncate(userId.trim(), 200);
        String tid = (transactionId == null || transactionId.isBlank())
                ? "api:" + System.currentTimeMillis()
                : truncate(transactionId.trim(), 200);
        try {
            append(new ActivityLogRequest(uid, tid, bizAction, recordAction));
        } catch (Exception e) {
            log.warn("Activity autolog failed for user {}: {}", uid, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
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
