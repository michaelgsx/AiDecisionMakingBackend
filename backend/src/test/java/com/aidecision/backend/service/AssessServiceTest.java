package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.AiAssessDecision;
import com.aidecision.backend.dto.AiAssessEvidence;
import com.aidecision.backend.dto.AiAssessReasoning;
import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessServiceTest {

    @Mock
    private AzureOpenAiEmbeddingService embeddingClient;

    @Mock
    private AzureOpenAiChatService chatService;

    @Mock
    private AzureSearchQueryService searchQueryService;

    @Mock
    private ActivityLogService activityLogService;

    private final AzureOpenAiProperties openAi = new AzureOpenAiProperties();
    private final AzureSearchProperties search = new AzureSearchProperties();
    private final ObjectMapper mapper = new ObjectMapper();

    private AssessService service;

    @BeforeEach
    void setUp() {
        service = new AssessService(embeddingClient, chatService, openAi, searchQueryService, search, mapper, activityLogService);
    }

    @Test
    void returnsMessageWhenSearchNotConfigured() {
        search.setEndpoint("");
        AssessResponse res = service.assess(new AssessRequest("note", "{}"));
        assertThat(res.similarRecords()).isEmpty();
        assertThat(res.reason()).contains("Azure AI Search is not configured");
        assertThat(res.aiLabel()).isNull();
        verify(chatService, never()).classifyWithSimilar(anyString(), anyString(), anyList());
        verify(activityLogService, never()).tryAppendFromApi(any(), any(), any(), any());
    }

    @Test
    void returnsMessageWhenSearchSkipped() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("i");
        search.setSkip(true);
        AssessResponse res = service.assess(new AssessRequest("note", "{}"));
        assertThat(res.similarRecords()).isEmpty();
        verify(chatService, never()).classifyWithSimilar(anyString(), anyString(), anyList());
        verify(activityLogService, never()).tryAppendFromApi(any(), any(), any(), any());
    }

    @Test
    void hybridSearchMapsHits() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("risk-records");
        search.setSkip(false);

        openAi.setEndpoint("http://openai");
        openAi.setApiKey("k");
        openAi.setEmbeddingDeployment("d");
        openAi.setSkipEmbedding(false);

        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(0.1, 0.2, 0.3), "m"));

        when(searchQueryService.searchSimilar(anyString(), anyList(), any(), eq(8)))
                .thenReturn(List.of(
                        AzureSearchQueryService.SimilarHit.forTest("rid-1", "[passed] hello", 0.91),
                        AzureSearchQueryService.SimilarHit.forTest("rid-2", "[rejected] x", 0.5)
                ));

        AssessResponse res = service.assess(new AssessRequest("case text", "{\"user_id\":\"u1\"}"));

        assertThat(res.similarRecords()).hasSize(2);
        assertThat(res.similarRecords().get(0).id()).isEqualTo("rid-1");
        assertThat(res.similarRecords().get(0).snippet()).isEqualTo("[passed] hello");
        assertThat(res.similarRecords().get(0).score()).isEqualTo(0.91);
        assertThat(res.similarRecords().get(0).readableText()).contains("Similar record");
        assertThat(res.reason()).contains("hybrid");
        assertThat(res.risk()).isEqualTo("high");
        assertThat(res.aiLabel()).isNull();
        verify(chatService, never()).classifyWithSimilar(anyString(), anyString(), anyList());
        verify(activityLogService).tryAppendFromApi(
                eq("u1"),
                argThat((String t) -> t != null && t.startsWith("assess-")),
                eq("pass"),
                eq("add"));
    }

    @Test
    void chatStepOverridesRiskWhenConfigured() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("risk-records");
        search.setSkip(false);

        openAi.setEndpoint("http://openai");
        openAi.setApiKey("k");
        openAi.setEmbeddingDeployment("d");
        openAi.setChatDeployment("gpt");
        openAi.setSkipEmbedding(false);

        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(0.1), "m"));
        when(searchQueryService.searchSimilar(anyString(), anyList(), any(), eq(8)))
                .thenReturn(List.of(AzureSearchQueryService.SimilarHit.forTest("a", "snip", 0.9)));
        when(chatService.classifyWithSimilar(anyString(), anyString(), anyList()))
                .thenReturn(new AiAssessDecision(
                        "rejected",
                        0.88,
                        List.of("prior reject pattern"),
                        new AiAssessReasoning(
                                "Two hits.",
                                "Amount aligns with rejects.",
                                "Notes overlap.",
                                "Both rejected.",
                                "Pattern matches prior rejects."),
                        AiAssessEvidence.empty()));

        AssessResponse res = service.assess(new AssessRequest("t", "{}"));

        assertThat(res.aiLabel()).isEqualTo("rejected");
        assertThat(res.aiReason()).contains("Pattern");
        assertThat(res.aiConfidence()).isEqualTo(0.88);
        assertThat(res.aiKeyRiskFactors()).contains("prior reject pattern");
        assertThat(res.aiReasoning()).isNotNull();
        assertThat(res.aiReasoning().synthesis()).contains("Pattern");
        assertThat(res.risk()).isEqualTo("high");
        verify(chatService).classifyWithSimilar(anyString(), anyString(), anyList());
        verify(activityLogService).tryAppendFromApi(isNull(), anyString(), eq("pass"), eq("add"));
    }

    @Test
    void lexicalOnlyWhenEmbeddingSkippedButTextPresent() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("risk-records");
        openAi.setSkipEmbedding(true);

        when(searchQueryService.searchSimilar(anyString(), isNull(), isNull(), eq(8)))
                .thenReturn(List.of());

        AssessResponse res = service.assess(new AssessRequest("only text", null));
        assertThat(res.reason()).contains("No indexed cases matched");
        assertThat(res.risk()).isEqualTo("low");
        verify(activityLogService).tryAppendFromApi(isNull(), anyString(), eq("pass"), eq("add"));
    }

    @Test
    void propagatesSearchFailures() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("risk-records");
        openAi.setEndpoint("http://x");
        openAi.setApiKey("k");
        openAi.setEmbeddingDeployment("d");
        openAi.setSkipEmbedding(false);

        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(1.0), "m"));
        when(searchQueryService.searchSimilar(anyString(), anyList(), any(), eq(8)))
                .thenThrow(new IllegalStateException("search down"));

        assertThatThrownBy(() -> service.assess(new AssessRequest("text", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search down");
        verify(activityLogService, never()).tryAppendFromApi(any(), any(), any(), any());
    }
}
