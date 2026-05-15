package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureSearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureSearchQueryServiceTest {

    private MockRestServiceServer server;
    private AzureSearchQueryService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8888");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        AzureSearchProperties props = new AzureSearchProperties();
        props.setEndpoint("http://localhost:8888");
        props.setAdminKey("adm");
        props.setIndexName("risk-records");
        props.setApiVersion("2024-07-01");
        props.setSkip(false);
        service = new AzureSearchQueryService(props, new ObjectMapper(), client);
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    void searchSimilarParsesHits() {
        server.expect(requestTo("http://localhost:8888/indexes/risk-records/docs/search?api-version=2024-07-01"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "adm"))
                .andRespond(withSuccess(
                        "{\"value\":["
                                + "{\"id\":\"a\",\"recordId\":\"r1\",\"reviewOutcome\":\"passed\",\"caseNotes\":\"n1\","
                                + "\"metadataJson\":\"{}\",\"content\":\"c1\",\"@search.score\":0.88}"
                                + "]}",
                        MediaType.APPLICATION_JSON));

        List<AzureSearchQueryService.SimilarHit> hits =
                service.searchSimilar("query text", List.of(0.1, 0.2), 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("r1");
        assertThat(hits.get(0).score()).isEqualTo(0.88);
        assertThat(hits.get(0).snippet()).contains("passed");
    }
}
