package com.aidecision.backend.controller;

import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.dto.SimilarRecord;
import com.aidecision.backend.service.AssessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AssessController.class)
@AutoConfigureMockMvc(addFilters = false)
class AssessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssessService assessService;

    @Test
    void assessReturnsBody() throws Exception {
        when(assessService.assess(any(AssessRequest.class)))
                .thenReturn(new AssessResponse("low", "because", List.of(new SimilarRecord("r1", "snip", 0.9))));

        mockMvc.perform(post("/rag/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"case","metadata":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk").value("low"))
                .andExpect(jsonPath("$.similarRecords[0].id").value("r1"));
    }
}
