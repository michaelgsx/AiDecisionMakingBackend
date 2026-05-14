package com.aidecision.backend.controller;

import com.aidecision.backend.dto.ActivityLogEntry;
import com.aidecision.backend.dto.ActivityLogRequest;
import com.aidecision.backend.dto.ActivityLogResponse;
import com.aidecision.backend.dto.ErrorResponse;
import com.aidecision.backend.service.ActivityLogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/audit")
public class ActivityLogController {

    private final ActivityLogService service;

    public ActivityLogController(ActivityLogService service) {
        this.service = service;
    }

    @PostMapping("/log")
    public ResponseEntity<?> append(@Valid @RequestBody ActivityLogRequest req) {
        try {
            ActivityLogResponse res = service.append(req);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/log")
    public ResponseEntity<List<ActivityLogEntry>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/log/user/{userId}")
    public ResponseEntity<List<ActivityLogEntry>> getByUser(@PathVariable String userId) {
        return ResponseEntity.ok(service.getByUser(userId));
    }

    @GetMapping("/log/transaction/{transactionId}")
    public ResponseEntity<List<ActivityLogEntry>> getByTransaction(@PathVariable String transactionId) {
        return ResponseEntity.ok(service.getByTransaction(transactionId));
    }

    @GetMapping("/log/verify/{userId}")
    public ResponseEntity<Map<String, Object>> verifyChain(@PathVariable String userId) {
        boolean valid = service.verifyChain(userId);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "chainValid", valid
        ));
    }
}
