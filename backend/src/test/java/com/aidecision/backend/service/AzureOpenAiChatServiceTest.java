package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.dto.AiAssessDecision;
import com.aidecision.backend.dto.SimilarRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureOpenAiChatServiceTest {

    @Test
    void notConfiguredThrows() {
        AzureOpenAiProperties p = new AzureOpenAiProperties();
        p.setEndpoint("http://x");
        p.setApiKey("k");
        AzureOpenAiChatService s = new AzureOpenAiChatService(p, new ObjectMapper(), RestClient.create());
        assertThatThrownBy(() -> s.classifyWithSimilar("n", "{}", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat is not configured");
    }

    @Nested
    class HttpCalls {

        private MockRestServiceServer server;
        private AzureOpenAiChatService service;

        @BeforeEach
        void setUp() {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:10001");
            server = MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder.build();
            AzureOpenAiProperties props = new AzureOpenAiProperties();
            props.setEndpoint("http://localhost:10001");
            props.setApiKey("secret");
            props.setChatDeployment("my-chat");
            props.setChatApiVersion("2024-08-01-preview");
            service = new AzureOpenAiChatService(props, new ObjectMapper(), client);
        }

        @AfterEach
        void tearDown() {
            server.verify();
        }

        @Test
        void parsesStructuredJsonObjectContent() {
            String modelJson =
                    "{\"label\":\"passed\",\"confidence\":0.76,\"key_risk_factors\":[\"low velocity\"],"
                            + "\"reasoning\":{\"retrieval_and_scores\":\"One similar case at 0.9.\","
                            + "\"feature_comparison\":\"Amount matches.\","
                            + "\"narrative_alignment\":\"Notes align.\","
                            + "\"historical_decisions\":\"Similar was passed.\","
                            + "\"synthesis\":\"Because similar cases approved.\"},"
                            + "\"evidence\":{\"summary\":\"One strong similar pass.\",\"items\":[{\"kind\":\"similar_case\","
                            + "\"record_id\":\"r1\",\"similarity_score\":0.9,\"review_outcome\":\"passed\","
                            + "\"claim\":\"Peer approved\",\"quote\":\"[passed] x\",\"supports_label\":\"passed\"}]}}";
            String escaped = modelJson.replace("\"", "\\\"");
            server.expect(requestTo(
                            "http://localhost:10001/openai/deployments/my-chat/chat/completions?api-version=2024-08-01-preview"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("api-key", "secret"))
                    .andRespond(withSuccess(
                            "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}",
                            MediaType.APPLICATION_JSON));

            AiAssessDecision d = service.classifyWithSimilar(
                    "notes",
                    "{\"a\":1}",
                    List.of(SimilarRecord.ofSnippet("r1", "[passed] x", 0.9)));

            assertThat(d.label()).isEqualTo("passed");
            assertThat(d.confidence()).isEqualTo(0.76);
            assertThat(d.keyRiskFactors()).containsExactly("low velocity");
            assertThat(d.reasoning().synthesis()).contains("similar cases");
            assertThat(d.formattedReason()).contains("Retrieval & scores");
            assertThat(d.evidence().items()).hasSize(1);
            assertThat(d.evidence().items().get(0).recordId()).isEqualTo("r1");
        }
    }

    @Test
    void parsesLegacyMonolithicReason() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("label", "frozen");
        body.put("reason", "Legacy single-field rationale.");
        AzureOpenAiProperties props = new AzureOpenAiProperties();
        AzureOpenAiChatService s = new AzureOpenAiChatService(props, new ObjectMapper(), RestClient.create());
        AiAssessDecision d = s.parseAssessDecisionBody(body);
        assertThat(d.label()).isEqualTo("frozen");
        assertThat(d.formattedReason()).contains("Legacy");
    }
}
