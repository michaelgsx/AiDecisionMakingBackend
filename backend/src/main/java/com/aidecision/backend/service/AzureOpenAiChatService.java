package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.dto.SimilarRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Azure OpenAI chat deployment: reads current case + similar search hits and returns label + rationale.
 */
@Service
public class AzureOpenAiChatService {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiChatService.class);

    private static final int MAX_SNIPPET_CHARS = 800;
    private static final int MAX_USER_BODY_CHARS = 100_000;

    private static final String SYSTEM_PROMPT = """
            You are a risk operations assistant. You receive (1) the CURRENT CASE: notes plus merged risk metadata JSON, \
            and (2) SIMILAR HISTORICAL CASES retrieved from an Azure AI Search index (each has an id, similarity score, and snippet).

            Respond with a single JSON object and no other text. The JSON must have exactly two string fields:
            - "label": exactly one of "passed", "rejected", "frozen" (lowercase), aligned with how comparable historical cases were decided when evidence supports it.
            - "reason": 2–6 sentences in English explaining how the current case and the similar cases support your label. Quote concrete patterns from the snippets when possible.

            If there are no similar cases, decide from the current case alone. If evidence conflicts, prefer a conservative outcome ("rejected" or "frozen" over "passed"). \
            Do not invent facts not present in the provided text.""";

    private final AzureOpenAiProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AzureOpenAiChatService(AzureOpenAiProperties props, ObjectMapper mapper, RestClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    public record LabelDecision(String label, String reason) {}

    /**
     * Calls the configured chat deployment with the current case text/metadata and similar records.
     */
    public LabelDecision classifyWithSimilar(
            String caseNotes,
            String mergedMetadataJson,
            List<SimilarRecord> similarRecords) {

        if (!props.chatConfigured()) {
            throw new IllegalStateException(
                    "Azure OpenAI chat is not configured. Set AZURE_OPENAI_CHAT_DEPLOYMENT (and endpoint/api-key), "
                            + "or set AZURE_OPENAI_SKIP_CHAT=true to skip.");
        }

        String userBody = buildUserContent(caseNotes, mergedMetadataJson, similarRecords);
        if (userBody.length() > MAX_USER_BODY_CHARS) {
            log.warn("Truncating assess chat user content from {} to {} chars", userBody.length(), MAX_USER_BODY_CHARS);
            userBody = userBody.substring(0, MAX_USER_BODY_CHARS);
        }

        String base = props.getEndpoint().replaceAll("/+$", "");
        String deploy = props.getChatDeployment();
        String version = props.getEffectiveChatApiVersion();
        URI uri = URI.create(base + "/openai/deployments/" + deploy + "/chat/completions?api-version=" + version);

        ObjectNode root = mapper.createObjectNode();
        root.put("temperature", 0.25);
        root.put("max_tokens", 900);
        ObjectNode fmt = mapper.createObjectNode();
        fmt.put("type", "json_object");
        root.set("response_format", fmt);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT.trim());
        messages.add(sys);
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", userBody);
        messages.add(user);
        root.set("messages", messages);

        String body;
        try {
            body = mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chat request", e);
        }

        String raw;
        try {
            raw = http.post()
                    .uri(uri)
                    .header("api-key", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                String err = "";
                                try {
                                    err = new String(res.getBody().readAllBytes());
                                } catch (Exception ignored) { }
                                throw new IllegalStateException(
                                        "Azure OpenAI chat failed: HTTP " + res.getStatusCode() + " " + err);
                            })
                    .body(String.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Azure OpenAI chat request failed: " + e.getMessage(), e);
        }

        return parseLabelDecision(raw);
    }

    private String buildUserContent(String caseNotes, String mergedMetadataJson, List<SimilarRecord> similar) {
        String notes = caseNotes == null ? "" : caseNotes.trim();
        String meta = mergedMetadataJson == null ? "{}" : mergedMetadataJson.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("## CURRENT CASE\n\n");
        sb.append("### Case notes\n").append(notes.isEmpty() ? "(none)" : notes).append("\n\n");
        sb.append("### Merged risk metadata (JSON)\n```json\n");
        sb.append(meta.isEmpty() ? "{}" : meta);
        sb.append("\n```\n\n");

        sb.append("## SIMILAR INDEXED CASES (Azure AI Search)\n\n");
        if (similar == null || similar.isEmpty()) {
            sb.append("(No similar indexed cases were returned for this query.)\n");
        } else {
            int i = 1;
            for (SimilarRecord r : similar) {
                sb.append("### ").append(i++).append(". ");
                if (r.id() != null && !r.id().isBlank()) {
                    sb.append("id=").append(r.id());
                } else {
                    sb.append("id=(unknown)");
                }
                if (r.score() != null) {
                    sb.append(" · similarity_score=").append(r.score());
                }
                sb.append("\n");
                String snip = r.snippet() == null ? "" : r.snippet().trim();
                if (snip.length() > MAX_SNIPPET_CHARS) {
                    snip = snip.substring(0, MAX_SNIPPET_CHARS) + "…";
                }
                sb.append(snip).append("\n\n");
            }
        }
        return sb.toString();
    }

    private LabelDecision parseLabelDecision(String rawResponse) {
        try {
            JsonNode root = mapper.readTree(rawResponse);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            content = stripMarkdownCodeFence(content);

            JsonNode obj = mapper.readTree(content);
            String label = textField(obj, "label").toLowerCase(Locale.ROOT);
            String reason = textField(obj, "reason");

            if (!label.equals("passed") && !label.equals("rejected") && !label.equals("frozen")) {
                throw new IllegalStateException("Model returned invalid label: " + label);
            }
            if (reason.isBlank()) {
                throw new IllegalStateException("Model returned empty reason");
            }
            return new LabelDecision(label, reason.trim());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse chat completion as label JSON", e);
        }
    }

    private static String textField(JsonNode obj, String key) {
        if (obj == null || !obj.isObject()) return "";
        JsonNode n = obj.get(key);
        if (n == null || n.isNull()) return "";
        return n.asText("").trim();
    }

    private static String stripMarkdownCodeFence(String s) {
        String t = s == null ? "" : s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl > 0) {
            t = t.substring(firstNl + 1);
        }
        int fence = t.lastIndexOf("```");
        if (fence >= 0) {
            t = t.substring(0, fence).trim();
        }
        return t.trim();
    }
}
