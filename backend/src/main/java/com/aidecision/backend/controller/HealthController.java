package com.aidecision.backend.controller;

import com.aidecision.backend.dto.ErrorResponse;
import com.aidecision.backend.dto.HealthResponse;
import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final EntityManager em;

    public HealthController(EntityManager em) {
        this.em = em;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            em.createNativeQuery("SELECT 1").getSingleResult();
            return ResponseEntity.ok(new HealthResponse(true, "azure-sql"));
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body(new ErrorResponse("Database unavailable: " + e.getMessage()));
        }
    }
}
