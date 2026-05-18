package com.aidecision.backend.service;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.dto.AiAssessDecision;
import com.aidecision.backend.dto.AiAssessEvidence;
import com.aidecision.backend.dto.AiAssessEvidenceItem;
import com.aidecision.backend.dto.AiAssessReasoning;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Azure OpenAI chat deployment: reads current case + similar search hits and returns label + rationale.
 */
@Service
public class AzureOpenAiChatService {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiChatService.class);

    private static final int MAX_SNIPPET_CHARS = 800;
    private static final int MAX_META_IN_CHAT = 16_384;
    private static final int MAX_CONTENT_IN_CHAT = 24_000;
    private static final int MAX_READABLE_IN_CHAT = 14_000;
    private static final int MAX_USER_BODY_CHARS = 120_000;

    private static final String SYSTEM_PROMPT = """
            You are a senior fraud / AML / compliance risk analyst. You ONLY see what is pasted in the user message: \
            the CURRENT CASE (notes + merged risk metadata JSON) and SIMILAR HISTORICAL CASES from Azure AI Search \
            (each with similarity_score, review_outcome, metadata_json, indexed_content, case_notes, etc.). \
            Your entire substantive analysis must appear inside the JSON object below — there is no other channel.

            Output: a single JSON object, no markdown fences, no prose outside JSON. Required keys:
            - "label" (string): exactly one of "passed", "rejected", "frozen" (lowercase).
            - "reasoning" (object): five string fields (plain text; line breaks and bullets allowed):
              - "retrieval_and_scores": How many similar cases; each similarity_score; fusion-rank context; evidence \
            strength (strong / moderate / weak).
              - "feature_comparison": For every meaningful TOP-LEVEL key in CURRENT CASE metadata (skip empty, technical, \
            or noise keys like assessQueryAt): per similar case, compare values (same / close / different / missing). \
            Quote or paraphrase actual values; name the highest-impact risk features.
              - "narrative_alignment": Compare current case_notes to each similar case_notes.
              - "historical_decisions": Per similar row, review_outcome and what the pattern implies for the current case.
              - "synthesis": Tie sections to your label; residual uncertainty or what would change your mind.
            - "evidence" (object): citeable facts backing the label (do not repeat the whole reasoning narrative):
              - "summary" (string): one sentence on overall evidence strength.
              - "items" (array, at least 1 when the user message has case data): each object:
                - "kind" (string): "similar_case" | "current_feature" | "narrative"
                - "record_id" (string, optional): for similar_case — must match a record_id from the user message
                - "similarity_score" (number, optional): for similar_case
                - "review_outcome" (string, optional): for similar_case — passed | rejected | frozen
                - "field" (string, optional): metadata key for current_feature
                - "value" (string, optional): metadata value for current_feature
                - "claim" (string, required): how this fact supports your label
                - "quote" (string, optional): short excerpt from notes/metadata/indexed content
                - "supports_label" (string, required): passed | rejected | frozen — which label this item supports
            Optional keys:
            - "confidence" (number): your confidence in the label, from 0.0 to 1.0.
            - "key_risk_factors" (array of strings): short bullet phrases for the top drivers (max 8 items).

            Length: when similar cases exist and metadata is non-trivial, each reasoning field should be substantive \
            (roughly 60–120 English words per field, or proportional Chinese). Shorter only if the user message is sparse.

            Rules:
            - Never invent keys, numbers, or outcomes not present in the user message.
            - If there are zero similar cases, say so in retrieval_and_scores and decide from the CURRENT CASE only.
            - If metadata is thin, lean on indexed_content and case_notes and state that explicitly.
            - If evidence conflicts or is ambiguous, prefer "rejected" or "frozen" over "passed".
            - Language: write reasoning fields in Chinese if the case notes are clearly Chinese; otherwise English.""";

    private final AzureOpenAiProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AzureOpenAiChatService(AzureOpenAiProperties props, ObjectMapper mapper, RestClient http) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
    }

    /** @deprecated Use {@link AiAssessDecision}; kept for test migration. */
    @Deprecated
    public record LabelDecision(String label, String reason) {}

    /**
     * Calls the configured chat deployment with the current case text/metadata and similar records.
     */
    public AiAssessDecision classifyWithSimilar(
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
        root.put("temperature", 0.15);
        root.put("max_tokens", 4096);
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

        return parseAssessDecision(raw);
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
                    sb.append("record_id=").append(r.id());
                } else {
                    sb.append("record_id=(unknown)");
                }
                if (r.recordId() != null && !r.recordId().isBlank() && !r.recordId().equals(r.id())) {
                    sb.append(" · index_recordId=").append(r.recordId());
                }
                if (r.score() != null) {
                    sb.append(" · similarity_score=").append(r.score());
                }
                if (r.reviewOutcome() != null && !r.reviewOutcome().isBlank()) {
                    sb.append(" · review_outcome=").append(r.reviewOutcome());
                }
                sb.append("\n\n");

                if (r.readableText() != null && !r.readableText().isBlank()) {
                    sb.append("#### Human-readable summary\n```\n");
                    String rt = r.readableText().trim();
                    if (rt.length() > MAX_READABLE_IN_CHAT) {
                        rt = rt.substring(0, MAX_READABLE_IN_CHAT) + "\n…";
                    }
                    sb.append(rt).append("\n```\n\n");
                }

                if (r.caseNotes() != null && !r.caseNotes().isBlank()) {
                    sb.append("#### case_notes\n");
                    String cn = r.caseNotes().trim();
                    if (cn.length() > MAX_SNIPPET_CHARS * 4) {
                        cn = cn.substring(0, MAX_SNIPPET_CHARS * 4) + "…";
                    }
                    sb.append(cn).append("\n\n");
                }

                if (r.metadataJson() != null && !r.metadataJson().isBlank()) {
                    sb.append("#### metadata_json (use keys for feature comparison)\n```json\n");
                    String mj = r.metadataJson().trim();
                    if (mj.length() > MAX_META_IN_CHAT) {
                        mj = mj.substring(0, MAX_META_IN_CHAT) + "\n…";
                    }
                    sb.append(mj).append("\n```\n\n");
                }

                if (r.content() != null && !r.content().isBlank()) {
                    sb.append("#### indexed_content (full blob used for hybrid retrieval)\n```\n");
                    String c = r.content().trim();
                    if (c.length() > MAX_CONTENT_IN_CHAT) {
                        c = c.substring(0, MAX_CONTENT_IN_CHAT) + "\n…";
                    }
                    sb.append(c).append("\n```\n\n");
                }

                String snip = r.snippet() == null ? "" : r.snippet().trim();
                if (!snip.isBlank()) {
                    sb.append("#### one_line_snippet\n");
                    if (snip.length() > MAX_SNIPPET_CHARS) {
                        snip = snip.substring(0, MAX_SNIPPET_CHARS) + "…";
                    }
                    sb.append(snip).append("\n\n");
                }
            }
        }

        sb.append("""
                \n---\nTASK (repeat for yourself before answering):\n\
                Return ONLY the JSON object with "label", "reasoning" (five fields), and "evidence" (summary + items). \
                Optionally include "confidence" and "key_risk_factors". Evidence items must cite real values from the \
                user message only.\n""");
        return sb.toString();
    }

    private AiAssessDecision parseAssessDecision(String rawResponse) {
        try {
            JsonNode root = mapper.readTree(rawResponse);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            content = stripMarkdownCodeFence(content);

            JsonNode obj = mapper.readTree(content);
            return parseAssessDecisionBody(obj);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse chat completion as assess decision JSON", e);
        }
    }

    /** Package-visible for unit tests. */
    AiAssessDecision parseAssessDecisionBody(JsonNode obj) {
        String label = textField(obj, "label").toLowerCase(Locale.ROOT);
        if (!label.equals("passed") && !label.equals("rejected") && !label.equals("frozen")) {
            throw new IllegalStateException("Model returned invalid label: " + label);
        }

        AiAssessReasoning reasoning = parseReasoning(obj);
        AiAssessEvidence evidence = parseEvidence(obj.get("evidence"));
        Double confidence = parseConfidence(obj.get("confidence"));
        List<String> keyRiskFactors = parseKeyRiskFactors(obj.get("key_risk_factors"));

        return new AiAssessDecision(label, confidence, keyRiskFactors, reasoning, evidence);
    }

    private AiAssessEvidence parseEvidence(JsonNode n) {
        if (n == null || !n.isObject()) {
            return AiAssessEvidence.empty();
        }
        String summary = textField(n, "summary");
        List<AiAssessEvidenceItem> items = parseEvidenceItems(n.get("items"));
        return new AiAssessEvidence(summary, items);
    }

    private static List<AiAssessEvidenceItem> parseEvidenceItems(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<AiAssessEvidenceItem> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String claim = textField(item, "claim");
            if (claim.isBlank()) {
                continue;
            }
            String supports = textField(item, "supports_label").toLowerCase(Locale.ROOT);
            if (!supports.equals("passed") && !supports.equals("rejected") && !supports.equals("frozen")) {
                supports = "";
            }
            Double score = null;
            JsonNode scoreNode = item.get("similarity_score");
            if (scoreNode != null && scoreNode.isNumber()) {
                score = scoreNode.asDouble();
            }
            out.add(new AiAssessEvidenceItem(
                    textField(item, "kind"),
                    textField(item, "record_id"),
                    score,
                    textField(item, "review_outcome"),
                    textField(item, "field"),
                    textField(item, "value"),
                    claim,
                    textField(item, "quote"),
                    supports));
        }
        return List.copyOf(out);
    }

    private AiAssessReasoning parseReasoning(JsonNode obj) {
        JsonNode reasoningNode = obj.get("reasoning");
        if (reasoningNode != null && reasoningNode.isObject()) {
            AiAssessReasoning r = new AiAssessReasoning(
                    textField(reasoningNode, "retrieval_and_scores"),
                    textField(reasoningNode, "feature_comparison"),
                    textField(reasoningNode, "narrative_alignment"),
                    textField(reasoningNode, "historical_decisions"),
                    textField(reasoningNode, "synthesis"));
            if (!r.toFormattedText().isBlank()) {
                validateReasoningPresent(r);
                return r;
            }
        }

        String legacyReason = textField(obj, "reason");
        if (!legacyReason.isBlank()) {
            return legacyReasoningFromMonolith(legacyReason);
        }

        throw new IllegalStateException("Model returned empty reasoning (missing reasoning object and reason)");
    }

    private static void validateReasoningPresent(AiAssessReasoning r) {
        if (r.synthesis() == null || r.synthesis().isBlank()) {
            throw new IllegalStateException("Model returned empty reasoning.synthesis");
        }
    }

    /** Maps pre-schema responses that used a single "reason" string. */
    private static AiAssessReasoning legacyReasoningFromMonolith(String reason) {
        return new AiAssessReasoning("", "", "", "", reason.trim());
    }

    private static Double parseConfidence(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isNumber()) {
            return null;
        }
        double v = n.asDouble();
        if (v < 0 || v > 1 || Double.isNaN(v)) {
            return null;
        }
        return v;
    }

    private static List<String> parseKeyRiskFactors(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (item != null && item.isTextual()) {
                String s = item.asText("").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
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
