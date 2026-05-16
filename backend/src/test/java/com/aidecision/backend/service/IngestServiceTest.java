package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;
import com.aidecision.backend.dto.IngestRequest;
import com.aidecision.backend.dto.IngestResponse;
import com.aidecision.backend.entity.RiskEmbedding;
import com.aidecision.backend.entity.RiskFeature;
import com.aidecision.backend.entity.RiskIngestRecord;
import com.aidecision.backend.repository.RiskEmbeddingRepository;
import com.aidecision.backend.repository.RiskFeatureRepository;
import com.aidecision.backend.repository.RiskIngestRecordRepository;
import com.aidecision.backend.support.TestReflection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

    @Mock
    private RiskIngestRecordRepository ingestRepo;
    @Mock
    private RiskFeatureRepository featureRepo;
    @Mock
    private RiskEmbeddingRepository embeddingRepo;
    @Mock
    private AzureOpenAiEmbeddingService embeddingClient;
    @Mock
    private AzureSearchIngestService searchIngestService;

    private final AzureOpenAiProperties openAi = new AzureOpenAiProperties();
    private final AzureSearchProperties search = new AzureSearchProperties();
    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ActivityLogService activityLogService;

    private IngestService service;

    @BeforeEach
    void setUp() {
        service = new IngestService(
                ingestRepo, featureRepo, embeddingRepo, embeddingClient, openAi,
                searchIngestService, search, mapper, transactionTemplate, activityLogService);
    }

    @SuppressWarnings("unchecked")
    private void runTransactionSynchronously() {
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<IngestResponse> cb = invocation.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            return cb.doInTransaction(status);
        });
    }

    @Test
    void ingestPersistsRowsAndIndexesWhenConfigured() {
        runTransactionSynchronously();

        openAi.setEndpoint("http://oai");
        openAi.setApiKey("k");
        openAi.setEmbeddingDeployment("d");
        openAi.setSkipEmbedding(false);

        search.setEndpoint("https://search.local");
        search.setAdminKey("key");
        search.setIndexName("risk-records");
        search.setSkip(false);

        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(0.5, -0.5), "model-x"));

        when(ingestRepo.save(any(RiskIngestRecord.class))).thenAnswer(inv -> {
            RiskIngestRecord r = inv.getArgument(0);
            TestReflection.setRiskIngestRecordId(r, 77L);
            return r;
        });

        IngestRequest req = new IngestRequest("notes", "{\"user_id\":\"u9\"}", "passed");
        IngestResponse res = service.ingest(req);

        assertThat(res.ok()).isTrue();
        assertThat(res.recordIndex()).isEqualTo(77L);
        assertThat(res.message()).contains("Azure AI Search indexed");
        assertThat(res.message()).contains("embedding 2-dim");

        verify(featureRepo).save(any(RiskFeature.class));
        verify(embeddingRepo, times(2)).save(any(RiskEmbedding.class));
        verify(searchIngestService).uploadIngestDocument(
                eq(res.recordId()),
                eq(req),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AzureOpenAiEmbeddingService.EmbeddingVector.class),
                org.mockito.ArgumentMatchers.any(AzureOpenAiEmbeddingService.EmbeddingVector.class));
        verify(activityLogService).tryAppendFromApi(
                eq("u9"),
                argThat((String t) -> t.startsWith("ingest:")),
                eq("pass"),
                eq("add"));
    }

    @Test
    void ingestSkipsSearchWhenEmbeddingSkipped() {
        runTransactionSynchronously();
        openAi.setSkipEmbedding(true);
        search.setEndpoint("https://search.local");
        search.setAdminKey("k");
        search.setIndexName("risk-records");

        when(ingestRepo.save(any(RiskIngestRecord.class))).thenAnswer(inv -> {
            RiskIngestRecord r = inv.getArgument(0);
            TestReflection.setRiskIngestRecordId(r, 1L);
            return r;
        });

        IngestResponse res = service.ingest(new IngestRequest("t", null, "passed"));
        assertThat(res.message()).doesNotContain("Azure AI Search");
        verify(searchIngestService, never()).uploadIngestDocument(
                any(), any(), any(), any(), any());
        verify(embeddingRepo, never()).save(any());
        verify(activityLogService).tryAppendFromApi(isNull(), anyString(), eq("pass"), eq("add"));
    }

    @Test
    void ingestThrowsWhenOpenAiRequiredButMissing() {
        openAi.setSkipEmbedding(false);

        assertThatThrownBy(() -> service.ingest(new IngestRequest("x", null, "passed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Azure OpenAI embedding is required");

        verify(ingestRepo, never()).save(any());
        verify(activityLogService, never()).tryAppendFromApi(any(), any(), any(), any());
    }

    @Test
    void ingestThrowsWhenSearchRequiredAfterEmbeddingButMissing() {
        runTransactionSynchronously();
        openAi.setEndpoint("http://oai");
        openAi.setApiKey("k");
        openAi.setEmbeddingDeployment("d");
        search.setEndpoint("");
        search.setSkip(false);

        when(embeddingClient.embed(any())).thenReturn(
                new AzureOpenAiEmbeddingService.EmbeddingVector(List.of(1.0), "m"));
        when(ingestRepo.save(any(RiskIngestRecord.class))).thenAnswer(inv -> {
            RiskIngestRecord r = inv.getArgument(0);
            TestReflection.setRiskIngestRecordId(r, 1L);
            return r;
        });

        assertThatThrownBy(() -> service.ingest(new IngestRequest("x", null, "passed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Azure AI Search is required");

        ArgumentCaptor<RiskIngestRecord> cap = ArgumentCaptor.forClass(RiskIngestRecord.class);
        verify(ingestRepo).save(cap.capture());
        verify(searchIngestService, never()).uploadIngestDocument(any(), any(), any(), any(), any());
        verify(activityLogService, never()).tryAppendFromApi(any(), any(), any(), any());
    }
}
