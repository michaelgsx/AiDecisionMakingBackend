package com.aidecision.backend.controller;

import com.aidecision.backend.config.GlobalExceptionHandler;
import com.aidecision.backend.dto.ActivityLogEntry;
import com.aidecision.backend.dto.ActivityLogResponse;
import com.aidecision.backend.service.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ActivityLogController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActivityLogService activityLogService;

    @Test
    void appendOk() throws Exception {
        when(activityLogService.append(any())).thenReturn(
                new ActivityLogResponse(true, 10L, "h1", "0x", "appended"));

        mockMvc.perform(post("/audit/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"u1","transactionId":"t1","bizAction":"pass","recordAction":"add"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void appendValidationRejectsBadBizAction() throws Exception {
        mockMvc.perform(post("/audit/log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"u1","transactionId":"t1","bizAction":"bogus","recordAction":"add"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("bizAction")));
    }

    @Test
    void getAll() throws Exception {
        when(activityLogService.getAll()).thenReturn(List.of(
                new ActivityLogEntry(1L, "u1", "t1", "pass", "add", "p", "h", Instant.parse("2024-01-01T00:00:00Z"))));

        mockMvc.perform(get("/audit/log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("u1"));
    }

    @Test
    void getByUser() throws Exception {
        when(activityLogService.getByUser("u9")).thenReturn(List.of());

        mockMvc.perform(get("/audit/log/user/u9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getByTransaction() throws Exception {
        when(activityLogService.getByTransaction("txn-1")).thenReturn(List.of());

        mockMvc.perform(get("/audit/log/transaction/txn-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void verifyChain() throws Exception {
        when(activityLogService.verifyChain("u1")).thenReturn(true);

        mockMvc.perform(get("/audit/log/verify/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainValid").value(true))
                .andExpect(jsonPath("$.userId").value("u1"));
    }
}
