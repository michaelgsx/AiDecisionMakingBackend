package com.aidecision.backend.controller;

import com.aidecision.backend.dto.AssessRequest;
import com.aidecision.backend.dto.AssessResponse;
import com.aidecision.backend.service.AssessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rag")
public class AssessController {

    private final AssessService assessService;

    public AssessController(AssessService assessService) {
        this.assessService = assessService;
    }

    @PostMapping("/assess")
    public ResponseEntity<AssessResponse> assess(@RequestBody AssessRequest req) {
        return ResponseEntity.ok(assessService.assess(req));
    }
}
