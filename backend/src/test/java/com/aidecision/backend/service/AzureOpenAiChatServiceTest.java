package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.dto.SimilarRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        void parsesJsonObjectContent() {
            server.expect(requestTo(
                            "http://localhost:10001/openai/deployments/my-chat/chat/completions?api-version=2024-08-01-preview"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("api-key", "secret"))
                    .andRespond(withSuccess(
                            "{\"choices\":[{\"message\":{\"content\":\"{\\\"label\\\":\\\"passed\\\",\\\"reason\\\":\\\"Because similar cases approved.\\\"}\"}}]}",
                            MediaType.APPLICATION_JSON));

            AzureOpenAiChatService.LabelDecision d = service.classifyWithSimilar(
                    "notes",
                    "{\"a\":1}",
                    List.of(new SimilarRecord("r1", "[passed] x", 0.9)));

            assertThat(d.label()).isEqualTo("passed");
            assertThat(d.reason()).contains("similar cases");
        }
    }
}
