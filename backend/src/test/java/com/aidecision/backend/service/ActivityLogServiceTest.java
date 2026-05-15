package com.aidecision.backend.service;

import com.aidecision.backend.dto.ActivityLogRequest;
import com.aidecision.backend.dto.ActivityLogResponse;
import com.aidecision.backend.entity.ActivityLog;
import com.aidecision.backend.repository.ActivityLogRepository;
import com.aidecision.backend.support.TestReflection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock
    private ActivityLogRepository repo;

    private ActivityLogService service;

    @BeforeEach
    void setUp() {
        service = new ActivityLogService(repo);
    }

    @Test
    void computeHashDeterministic() {
        String h = ActivityLogService.computeHash(
                "0".repeat(64),
                "user-1",
                "TX-1",
                "pass",
                "add",
                "2026-05-15T10:00:00Z");
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
        assertThat(ActivityLogService.computeHash(
                "0".repeat(64),
                "user-1",
                "TX-1",
                "pass",
                "add",
                "2026-05-15T10:00:00Z")).isEqualTo(h);
    }

    @Test
    void appendUsesGenesisWhenNoPriorRow() {
        when(repo.findLatestByUserId("u1")).thenReturn(Optional.empty());
        when(repo.save(any(ActivityLog.class))).thenAnswer(inv -> {
            ActivityLog a = inv.getArgument(0);
            TestReflection.setActivityLogId(a, 10L);
            TestReflection.setActivityLogCreatedAt(a, Instant.parse("2026-05-15T12:00:00Z"));
            return a;
        });

        ActivityLogRequest req = new ActivityLogRequest("u1", "TX-9", "pass", "add");
        ActivityLogResponse res = service.append(req);

        assertThat(res.ok()).isTrue();
        assertThat(res.prevHash()).isEqualTo("0".repeat(64));
        assertThat(res.hash()).hasSize(64);

        ArgumentCaptor<ActivityLog> cap = ArgumentCaptor.forClass(ActivityLog.class);
        verify(repo).save(cap.capture());
        ActivityLog saved = cap.getValue();
        assertThat(saved.getPrevHash()).isEqualTo("0".repeat(64));
        assertThat(saved.getHashCode()).isEqualTo(res.hash());
    }

    @Test
    void appendChainsFromLatestHash() {
        ActivityLog latest = new ActivityLog();
        latest.setPrevHash("aa");
        latest.setUserId("u1");
        latest.setHashCode("deadbeef".repeat(8));
        when(repo.findLatestByUserId("u1")).thenReturn(Optional.of(latest));
        when(repo.save(any(ActivityLog.class))).thenAnswer(inv -> {
            ActivityLog a = inv.getArgument(0);
            TestReflection.setActivityLogId(a, 2L);
            TestReflection.setActivityLogCreatedAt(a, Instant.parse("2026-05-15T12:00:00Z"));
            return a;
        });

        ActivityLogResponse res = service.append(
                new ActivityLogRequest("u1", "TX-2", "freeze", "add"));

        assertThat(res.prevHash()).isEqualTo("deadbeef".repeat(8));
        assertThat(res.hash()).isNotBlank();
    }

    @Test
    void verifyChainTrueWhenRowsMatch() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        String prev = "0".repeat(64);
        String hash = ActivityLogService.computeHash(prev, "u1", "T1", "pass", "add", t.toString());

        ActivityLog row = new ActivityLog();
        row.setUserId("u1");
        row.setTransactionId("T1");
        row.setBizAction("pass");
        row.setRecordAction("add");
        row.setPrevHash(prev);
        row.setHashCode(hash);
        TestReflection.setActivityLogCreatedAt(row, t);

        when(repo.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(row));

        assertThat(service.verifyChain("u1")).isTrue();
    }

    @Test
    void verifyChainFalseWhenTampered() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        ActivityLog row = new ActivityLog();
        row.setUserId("u1");
        row.setTransactionId("T1");
        row.setBizAction("pass");
        row.setRecordAction("add");
        row.setPrevHash("0".repeat(64));
        row.setHashCode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        TestReflection.setActivityLogCreatedAt(row, t);

        when(repo.findByUserIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(row));

        assertThat(service.verifyChain("u1")).isFalse();
    }
}
