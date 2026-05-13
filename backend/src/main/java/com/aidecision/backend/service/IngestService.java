package com.aidecision.backend.service;

import com.aidecision.backend.dto.IngestRequest;
import com.aidecision.backend.dto.IngestResponse;
import com.aidecision.backend.entity.RiskIngestRecord;
import com.aidecision.backend.repository.RiskIngestRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class IngestService {

    private final RiskIngestRecordRepository repo;
    private final ObjectMapper mapper;

    public IngestService(RiskIngestRecordRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public IngestResponse ingest(IngestRequest req) {
        String mergedMetadata = mergeMetadata(req.metadata(), req.reviewOutcome());

        RiskIngestRecord entity = new RiskIngestRecord();
        entity.setRecordUuid(UUID.randomUUID().toString());
        entity.setReviewOutcome(req.reviewOutcome());
        entity.setText(req.text() != null && !req.text().isBlank() ? req.text().trim() : null);
        entity.setMetadata(mergedMetadata);

        RiskIngestRecord saved = repo.save(entity);

        return new IngestResponse(
                true,
                saved.getId(),
                saved.getRecordUuid(),
                "Saved to Azure SQL (" + req.reviewOutcome() + ")"
        );
    }

    private String mergeMetadata(String raw, String reviewOutcome) {
        ObjectNode node;
        try {
            if (raw != null && !raw.isBlank()) {
                var parsed = mapper.readTree(raw.trim());
                if (parsed.isObject()) {
                    node = (ObjectNode) parsed;
                } else {
                    node = mapper.createObjectNode();
                    node.put("_previousMetadata", raw.trim());
                }
            } else {
                node = mapper.createObjectNode();
            }
        } catch (Exception e) {
            node = mapper.createObjectNode();
            if (raw != null && !raw.isBlank()) {
                node.put("_previousMetadata", raw.trim());
            }
        }
        node.put("reviewOutcome", reviewOutcome);
        node.put("reviewOutcomeAt", Instant.now().toString());
        return node.toString();
    }
}
