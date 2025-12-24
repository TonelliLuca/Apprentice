package agent.activity;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.internal.Json;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReasoningStep {
    private final Instant timestamp;
    private final String action;
    private final String input;
    private final String result;
    private final Map<String, Object> beliefsSnapshot;
    private final List<JsonNode> events;

    public ReasoningStep(String action, String input, String result, Map<String, Object> beliefsSnapshot, List<JsonNode> events) {
        this.timestamp = Instant.now();
        this.action = action;
        this.input = input;
        this.result = result;
        this.beliefsSnapshot = beliefsSnapshot == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(beliefsSnapshot));
        this.events = events;
    }

    public ReasoningStep(String action, String input, String result, Map<String, Object> beliefsSnapshot) {
        this.timestamp = Instant.now();
        this.action = action;
        this.input = input;
        this.result = result;
        this.beliefsSnapshot = beliefsSnapshot == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(beliefsSnapshot));
        this.events = Collections.emptyList();
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getAction() {
        return action;
    }

    public String getInput() {
        return input;
    }

    public String getResult() {
        return result;
    }

    public List<JsonNode> getEvents(){ return events; }

    public Map<String, Object> getBeliefsSnapshot() {
        return beliefsSnapshot;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
          .append("\"timestamp\":\"").append(timestamp).append("\",")
          .append("\"action\":\"").append(escape(action)).append("\",")
          .append("\"input\":\"").append(escape(input)).append("\",")
          .append("\"result\":\"").append(escape(result)).append("\",")
          .append("\"beliefs\":").append(beliefsToJson()).append(",")
            .append("\"events\":[").append(events).append("]")
          .append("}");
        return sb.toString();
    }

    private String beliefsToJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : beliefsSnapshot.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            String valStr = String.valueOf(e.getValue());
            sb.append(valStr);
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }
}