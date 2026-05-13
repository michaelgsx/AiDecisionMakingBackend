package com.aidecision.backend.controller;

import com.aidecision.backend.dto.ErrorResponse;
import com.aidecision.backend.dto.IngestRequest;
import com.aidecision.backend.dto.IngestResponse;
import com.aidecision.backend.service.IngestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rag")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@Valid @RequestBody IngestRequest req) {
        boolean hasText = req.text() != null && !req.text().isBlank();
        boolean hasMeta = req.metadata() != null && !req.metadata().isBlank();
        if (!hasText && !hasMeta) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Provide at least one of text or metadata"));
        }

        try {
            IngestResponse res = ingestService.ingest(req);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }
}
