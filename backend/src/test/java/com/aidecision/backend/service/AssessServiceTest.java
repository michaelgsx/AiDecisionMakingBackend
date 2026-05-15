package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.dto.SimilarRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessServiceTest {

    @Mock
    private AzureOpenAiEmbeddingService embeddingClient;

    @Mock
    private AzureSearchQueryService searchQueryService;

    private final AzureOpenAiProperties openAi = new AzureOpenAiProperties();
    private final AzureSearchProperties search = new AzureSearchProperties();
    private final ObjectMapper mapper = new ObjectMapper();

    private AssessService service;

    @BeforeEach
    void setUp() {
        service = new AssessService(embeddingClient, openAi, searchQueryService, search, mapper);
    }

    @Test
    void returnsMessageWhenSearchNotConfigured() {
        search.setEndpoint("");
        AssessResponse res = service.assess(new AssessRequest("note", "{}"));
        assertThat(res.similarRecords()).isEmpty();
        assertThat(res.reason()).contains("Azure AI Search is not configured");
    }

    @Test
    void returnsMessageWhenSearchSkipped() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("i");
        search.setSkip(true);
        AssessResponse res = service.assess(new AssessRequest("note", "{}"));
        assertThat(res.similarRecords()).isEmpty();
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

        when(searchQueryService.searchSimilar(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(8)))
                .thenReturn(List.of(
                        new AzureSearchQueryService.SimilarHit("rid-1", "[passed] hello", 0.91),
                        new AzureSearchQueryService.SimilarHit("rid-2", "[rejected] x", 0.5)
                ));

        AssessResponse res = service.assess(new AssessRequest("case text", "{\"user_id\":\"u1\"}"));

        assertThat(res.similarRecords()).hasSize(2);
        assertThat(res.similarRecords().get(0)).isEqualTo(new SimilarRecord("rid-1", "[passed] hello", 0.91));
        assertThat(res.reason()).contains("hybrid");
        assertThat(res.risk()).isEqualTo("high");
    }

    @Test
    void lexicalOnlyWhenEmbeddingSkippedButTextPresent() {
        search.setEndpoint("https://x.search.windows.net");
        search.setAdminKey("k");
        search.setIndexName("risk-records");
        openAi.setSkipEmbedding(true);

        when(searchQueryService.searchSimilar(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(8)))
                .thenReturn(List.of());

        AssessResponse res = service.assess(new AssessRequest("only text", null));
        assertThat(res.reason()).contains("No indexed cases matched");
        assertThat(res.risk()).isEqualTo("low");
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
        when(searchQueryService.searchSimilar(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(8)))
                .thenThrow(new IllegalStateException("search down"));

        assertThatThrownBy(() -> service.assess(new AssessRequest("text", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search down");
    }
}
