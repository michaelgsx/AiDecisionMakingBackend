package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.IngestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureSearchIngestServiceTest {

    private MockRestServiceServer server;
    private AzureSearchIngestService service;
    private AzureSearchProperties props;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:7777");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        props = new AzureSearchProperties();
        props.setEndpoint("http://localhost:7777");
        props.setAdminKey("adm");
        props.setIndexName("risk-records");
        props.setApiVersion("2024-07-01");
        props.setSkip(false);
        service = new AzureSearchIngestService(props, new ObjectMapper(), client);
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    void uploadSendsIndexBatch() {
        server.expect(requestTo("http://localhost:7777/indexes/risk-records/docs/index?api-version=2024-07-01"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "adm"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        var vec = new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(1.0, 2.0), "model");
        service.uploadIngestDocument(
                "rec-id",
                new IngestRequest("hello", "{\"user_id\":\"u1\"}", "passed"),
                "{\"user_id\":\"u1\"}",
                "merged text",
                vec);
    }

    @Test
    void uploadNoOpWhenSkipped() {
        props.setSkip(true);
        var vec = new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(1.0), "m");
        service.uploadIngestDocument("id", new IngestRequest("t", null, "passed"), "{}", "c", vec);
    }
}
