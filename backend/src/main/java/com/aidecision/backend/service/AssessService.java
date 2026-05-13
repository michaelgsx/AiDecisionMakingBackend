package com.aidecision.backend.service;

import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.dto.SimilarRecord;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssessService {

    /**
     * Placeholder: replace with RAG pipeline / model inference when ready.
     */
    public AssessResponse assess(AssessRequest req) {
        return new AssessResponse(
                "low",
                "Placeholder: replace /rag/assess with your RAG / model pipeline.",
                List.of()
        );
    }
}
