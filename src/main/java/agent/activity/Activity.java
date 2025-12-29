package agent.activity;

import agent.memory.ToolManual;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Activity {
    private static final Logger logger = LoggerFactory.getLogger(Activity.class);

    private final UUID uuid;
    private final String goal;
    private volatile Status status;
    private final List<ReasoningStep> history = new CopyOnWriteArrayList<>();
    private final Map<String, JsonNode> beliefs = new ConcurrentHashMap<>();

    // WORKING MEMORY (Context Window Content)
    private final Map<String, String> openedManuals = new HashMap<>();

    private final List<JsonNode> incomingEvents = new CopyOnWriteArrayList<>();
    private final Set<String> loadedMemories = new LinkedHashSet<>();
    private boolean memoriesLoaded = false;

    public enum Status {
        SETUP, REASONING, ACTION, WAITING_FOR_EVENT, OBSERVATION, COMPLETED
    }

    public Activity(String goal) {
        this.uuid = UUID.randomUUID();
        this.goal = goal;
        this.status = Status.OBSERVATION;
    }

    // --- CONTEXT MANAGEMENT (Mount/Unmount) ---
    public void openManual(ToolManual manual) {
        if (!openedManuals.containsKey(manual.getToolName())) {
            logger.info("📖 [WORKING_MEMORY] MOUNTING Manual: '{}' (Injecting {} chars into Context)",
                    manual.getToolName(), manual.toFullManual().length());
            this.openedManuals.put(manual.getToolName(), manual.toFullManual());
        } else {
            logger.debug("📖 [WORKING_MEMORY] Manual '{}' is already mounted.", manual.getToolName());
        }
    }

    public void closeManual(String toolName) {
        if (openedManuals.containsKey(toolName)) {
            logger.info("📕 [WORKING_MEMORY] UNMOUNTING Manual: '{}' (Freeing Context)", toolName);
            this.openedManuals.remove(toolName);
        }
    }

    public String getOpenedManualsView() {
        if (openedManuals.isEmpty()) return "NO MANUALS LOADED (Working Memory Empty).";
        return String.join("\n\n", openedManuals.values());
    }

    public Set<String> getOpenedManualsNames() {
        return openedManuals.keySet();
    }

    public Optional<ReasoningStep> lastStep() { if (history.isEmpty()) return Optional.empty(); return Optional.of(history.get(history.size() - 1)); }
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
                .append("\"uuid\":\"").append(uuid).append("\",")
                .append("\"goal\":\"").append(escape(goal)).append("\",")
                .append("\"status\":\"").append(status).append("\",")
                .append("\"variables\":").append(beliefsToJson()).append(",")
                .append("\"history\":[");
        boolean first = true;
        for (ReasoningStep step : history) {
            if (!first) sb.append(",");
            first = false;
            sb.append(step.toJson());
        }
        sb.append("]}");
        return sb.toString();
    }
    private String beliefsToJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonNode> entry : beliefs.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":").append(entry.getValue().toString());
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }
    public void pushEvent(JsonNode event) { incomingEvents.add(event); }
    public List<JsonNode> consumeEvents() { List<JsonNode> c = new ArrayList<>(incomingEvents); incomingEvents.clear(); return c; }
    public boolean hasEvents() { return !incomingEvents.isEmpty(); }
    public void setBelief(String key, JsonNode value) { if (value != null) beliefs.put(key, value); }
    public JsonNode getBelief(String key) { return beliefs.get(key); }
    public Map<String, Object> getBeliefsSnapshot() { return new HashMap<>(beliefs); }
    public String getUuid() { return uuid.toString(); }
    public String getGoal() { return goal; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isCompleted() { return this.status == Status.COMPLETED; }
    public void addStep(ReasoningStep step) { if (step != null) history.add(step); }
    public List<ReasoningStep> getHistory() { return Collections.unmodifiableList(history); }
    public boolean areMemoriesLoaded() { return memoriesLoaded; }
    public void setMemoriesLoaded(boolean m) { this.memoriesLoaded = m; }
    public void addMemories(List<String> m) { if (m!=null) this.loadedMemories.addAll(m); }
    public String getMemoriesView() { return loadedMemories.isEmpty() ? "No memories." : String.join("\n\n", loadedMemories); }
}