package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureOpenAiEmbeddingServiceTest {

    @Test
    void notConfiguredThrowsWithoutHttp() {
        AzureOpenAiProperties empty = new AzureOpenAiProperties();
        AzureOpenAiEmbeddingService s = new AzureOpenAiEmbeddingService(empty, new ObjectMapper(), RestClient.create());
        assertThatThrownBy(() -> s.embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Nested
    class HttpCalls {

        private MockRestServiceServer server;
        private AzureOpenAiEmbeddingService service;

        @BeforeEach
        void setUp() {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:9999");
            server = MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder.build();
            AzureOpenAiProperties props = new AzureOpenAiProperties();
            props.setEndpoint("http://localhost:9999");
            props.setApiKey("secret-key");
            props.setEmbeddingDeployment("mydep");
            props.setApiVersion("2024-02-01");
            service = new AzureOpenAiEmbeddingService(props, new ObjectMapper(), client);
        }

        @AfterEach
        void tearDown() {
            server.verify();
        }

        @Test
        void embedParsesVector() {
            server.expect(requestTo("http://localhost:9999/openai/deployments/mydep/embeddings?api-version=2024-02-01"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("api-key", "secret-key"))
                    .andRespond(withSuccess(
                            "{\"data\":[{\"embedding\":[0.25,-0.5]}],\"model\":\"text-embedding-3-small\"}",
                            MediaType.APPLICATION_JSON));

            AzureOpenAiEmbeddingService.EmbeddingVector v = service.embed("hello");
            assertThat(v.values()).containsExactly(0.25, -0.5);
            assertThat(v.dimensions()).isEqualTo(2);
            assertThat(v.modelName()).isEqualTo("text-embedding-3-small");
        }
    }
}
