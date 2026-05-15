package com.aidecision.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads {@code user_id} and {@code transaction_id} from merged metadata JSON (same keys as ingest features).
 */
public final class MetadataUserRefs {

    public record UserTxn(String userId, String transactionId) {}

    private MetadataUserRefs() {}

    public static UserTxn parse(String mergedMetadataJson, ObjectMapper mapper) {
        if (mergedMetadataJson == null || mergedMetadataJson.isBlank()) {
            return new UserTxn(null, null);
        }
        try {
            JsonNode n = mapper.readTree(mergedMetadataJson.trim());
            if (!n.isObject()) {
                return new UserTxn(null, null);
            }
            String userId = textOrNull(n.get("user_id"));
            String txnId = textOrNull(n.get("transaction_id"));
            return new UserTxn(userId, txnId);
        } catch (Exception e) {
            return new UserTxn(null, null);
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        String s = n.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
