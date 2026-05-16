package com.aidecision.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Natural-language fields (email / chat) for textVector embedding. */
public final class TextFeatureSupport {

    private static final String[] TEXT_KEYS = {"email_trace", "conversation_trace"};

    private TextFeatureSupport() {}

    public static String buildTextBlob(String caseNotes, String metadataJson, ObjectMapper mapper) {
        List<String> parts = new ArrayList<>();
        String notes = caseNotes == null ? "" : caseNotes.trim();
        if (!notes.isBlank()) {
            parts.add("[Case notes]\n" + notes);
        }
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                JsonNode root = mapper.readTree(metadataJson);
                if (root.isObject()) {
                    for (String key : TEXT_KEYS) {
                        JsonNode n = root.get(key);
                        if (n == null || n.isNull()) {
                            continue;
                        }
                        String text = n.asText("").trim();
                        if (text.isBlank()) {
                            continue;
                        }
                        String label = "email_trace".equals(key) ? "Email" : "Conversation";
                        parts.add("[" + label + "]\n" + text);
                    }
                }
            } catch (Exception ignored) {
                // metadata not JSON — skip NL extraction
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }
}
