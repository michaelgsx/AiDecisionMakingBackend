package com.aidecision.backend.controller;

import com.aidecision.backend.config.GlobalExceptionHandler;
import com.aidecision.backend.dto.IngestResponse;
import com.aidecision.backend.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IngestController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestService ingestService;

    @Test
    void ingestOk() throws Exception {
        when(ingestService.ingest(any())).thenReturn(new IngestResponse(true, 1L, "rid-1", "ok"));

        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"hello","metadata":null,"reviewOutcome":"passed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.recordId").value("rid-1"));
    }

    @Test
    void ingestRejectsEmptyPayload() throws Exception {
        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":null,"metadata":null,"reviewOutcome":"passed"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provide at least one of text or metadata"));
    }

    @Test
    void ingestValidationRejectsInvalidOutcome() throws Exception {
        mockMvc.perform(post("/rag/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"t","metadata":null,"reviewOutcome":"bogus"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("reviewOutcome")));
    }
}
