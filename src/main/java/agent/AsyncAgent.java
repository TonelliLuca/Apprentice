package agent;

import agent.activity.Activity;
import agent.activity.ReasoningStep;
import agent.memory.AgentMemory;
import agent.memory.EpisodicMemory;
import agent.memory.ToolManual;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.commons.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AsyncAgent<T extends ReactBrain> {

    private final T reasoningBrain;
    private final T actionBrain;
    private final ChatModel model;
    private final Class<T> agentInterface;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AgentMemory agentMemory;
    private final Logger logger = LoggerFactory.getLogger(AsyncAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Activity> activityRegistry = new ConcurrentHashMap<>();
    private final String sseUrl;
    private final BlockingQueue<Activity> activityQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean loopRunning = new AtomicBoolean(true);
    private static final int WINDOW_SIZE = 7;

    private AsyncAgent(Builder<T> builder) {
        this.agentMemory = new AgentMemory();
        this.model = builder.model;
        this.agentInterface = builder.agentInterface;
        this.sseUrl = builder.sseUrl;
        this.reasoningBrain = AgenticServices.agentBuilder(agentInterface).chatModel(model).build();
        var actionBuilder = AgenticServices.agentBuilder(agentInterface).chatModel(model);
        if (builder.tools != null && builder.tools.length > 0) actionBuilder.tools(builder.tools);
        if (builder.mcpToolProvider != null) actionBuilder.toolProvider(builder.mcpToolProvider);
        this.actionBrain = actionBuilder.build();
        if (this.sseUrl != null && !this.sseUrl.isEmpty()) {
            startSseListener();
        }
        executor.submit(this::eventLoop);
    }

    public void request(String request) {
        if (request == null || request.isBlank()) return;
        Activity activity = new Activity(request);
        activityRegistry.put(activity.getUuid(), activity);
        activityQueue.offer(activity);
        logger.info("🚀 [AGENT] NEW GOAL: \"{}\" (ID: {})", request, activity.getUuid());
    }

    private void eventLoop() {
        logger.info("🚦 [SYSTEM] Agent Event Loop Started");
        while (loopRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Activity activity = activityQueue.poll(500, TimeUnit.MILLISECONDS);
                if (activity == null) continue;


                if (activity.hasEvents() &&
                        activity.getStatus() != Activity.Status.OBSERVATION &&
                        activity.getStatus() != Activity.Status.COMPLETED) {
                    logger.info("⚡ [GUARD] Pending events detected! Skipping to OBSERVATION.");
                    activity.setStatus(Activity.Status.OBSERVATION);
                    activityQueue.offer(activity);
                    continue;
                }
                Activity.Status status = activity.getStatus();
                String history = extractActivityHistoryMarkdown(activity, WINDOW_SIZE);
                String contextJson = buildContextMarkdown(activity);
                String progressTracker = extractProgressTrackerMarkdown(activity);

                switch (status) {
                    case SETUP -> {
                        logger.info("🔧 [SETUP] Assessing Manual Needs for Goal: '{}'", activity.getGoal());
                        String explicitInstruction = activity.getBelief("act_instruction") != null
                                ? activity.getBelief("act_instruction").asText()
                                : "No explicit instruction provided.";
                        // Retrieve the current manual catalog
                        List<String> catalog = agentMemory.searchManualCatalog(activity.getGoal(), 100);
                        String catalogView = catalog.isEmpty() ? "CATALOG EMPTY (Need Discovery)" : String.join("\n", catalog);

                        logger.debug(" [SETUP] calling the brain with catalog:\n{}, history:\n{}, context:\n{}, progress:\n{}, instruction:\n{}",
                                catalogView, history, contextJson, progressTracker, explicitInstruction);
                        String setupResult = this.reasoningBrain.setup( catalogView, activity.getOpenedManualsNames(), history, explicitInstruction);

                        // 1. Preliminary parsing to extract 'summary' (important for narration)
                        String summary = "Setup operations executed based on catalog.";
                        try {
                            JsonNode preRoot = objectMapper.readTree(cleanJson(setupResult));
                            if (preRoot.has("summary")) {
                                summary = preRoot.get("summary").asText();
                            }
                        } catch (Exception e) {
                            logger.warn("⚠️ [SETUP] Could not extract summary from JSON.");
                        }

                        // 2. Persist this setup step immediately in history so subsequent reasoning sees it
                        activity.addStep(new ReasoningStep("setup", activity.getGoal(), summary, activity.getBeliefsSnapshot()));

                        // 3. Execute mounting/unmounting logic
                        try {
                            JsonNode root = objectMapper.readTree(cleanJson(setupResult));

                            if (root.has("mount_tools")) {
                                for (JsonNode node : root.get("mount_tools")) {
                                    String toolName = node.asText();
                                    ToolManual manual = agentMemory.getManualByName(toolName);
                                    if (manual != null) {
                                        activity.openManual(manual);
                                        logger.info("📖 [SETUP] Mounted: {}", toolName);
                                    } else {
                                        logger.warn("⚠️ [SETUP] Requested manual '{}' not found in memory.", toolName);
                                    }
                                }
                            }

                            if (root.has("unmount_tools")) {
                                for (JsonNode node : root.get("unmount_tools")) {
                                    String toolName = node.asText();
                                    activity.closeManual(toolName);
                                    logger.info("📕 [SETUP] Unmounted: {}", toolName);
                                }
                            }

                            // Transition: after setup, return to REASONING with the updated context.
                            activity.setStatus(Activity.Status.REASONING);
                            activityQueue.offer(activity);

                        } catch (Exception e) {
                            logger.error("❌ [SETUP] Parse/Execution Error", e);
                            // Fallback: return to reasoning to avoid blocking; agent may retry or change strategy
                            activity.setStatus(Activity.Status.REASONING);
                            activityQueue.offer(activity);
                        }
                    }

                    case REASONING -> {
                        logger.info("🧠 [REASONING] Deciding next step for Goal: '{}'", activity.getGoal());
                        if (activity.hasEvents()) {
                            activity.setStatus(Activity.Status.OBSERVATION);
                            activityQueue.offer(activity);
                            break;
                        }
                        List<String> relevantMemories = agentMemory.retrieveRelevantMemories(activity.getGoal(), 2);
                        String memoriesText = "No relevant past memories found.";
                        if (!relevantMemories.isEmpty()) {
                            memoriesText = String.join("\n\n--- MEMORY ---\n", relevantMemories);
                            logger.info("🧠 Found {} relevant memories for reasoning.", relevantMemories.size());
                        }

                        List<String> catalog = agentMemory.searchManualCatalog(activity.getGoal(), 100);
                        String catalogView = catalog.isEmpty() ? "CATALOG EMPTY (Need Discovery)" : String.join("\n", catalog);
                        ensureMemoriesLoaded(activity);
                        logger.debug(" [REASONING] calling the brain with catalog:\n{}, history:\n{}, context:\n{}, progress:\n{}, memories:\n{}",
                                catalogView, history, contextJson, progressTracker, memoriesText);
                        String reasoningResult = this.reasoningBrain.reason( history, contextJson, progressTracker, activity.getOpenedManualsView(), catalogView, memoriesText);
                        activity.addStep(new ReasoningStep("reason", activity.getGoal(), reasoningResult, activity.getBeliefsSnapshot()));

                        try {
                            JsonNode resultNode = objectMapper.readTree(cleanJson(reasoningResult));
                            String decision = resultNode.has("decision") ? resultNode.get("decision").asText().toUpperCase() : "ACT";

                            if (resultNode.has("next_step_description")) {
                                String instruction = resultNode.get("next_step_description").asText();
                                logger.info("🧠 [STRATEGY] Locking explicit instruction: \"{}\"", instruction);
                                activity.setBelief("act_instruction", TextNode.valueOf(instruction));
                            }

                            logger.info("💡 [REASON] Decision: {} | Thought: ...", decision);

                            switch (decision) {
                                case "SETUP" -> { activity.setStatus(Activity.Status.SETUP); activityQueue.offer(activity); }
                                case "ACT" -> { activity.setStatus(Activity.Status.ACTION); activityQueue.offer(activity); }

                                default -> {
                                    logger.warn("❓ [REASON] Unknown decision '{}'. Defaulting to ACTION.", decision);
                                    activity.setStatus(Activity.Status.ACTION);
                                    activityQueue.offer(activity);
                                }
                            }
                        } catch (Exception e) {
                            activity.setStatus(Activity.Status.ACTION);
                            activityQueue.offer(activity);
                        }
                    }

                    case ACTION -> {

                        String explicitInstruction = activity.getBelief("act_instruction") != null
                                ? activity.getBelief("act_instruction").asText()
                                : "No explicit instruction provided.";

                        logger.info("🛠️ [ACTION] Executing: {}", explicitInstruction);

                        logger.debug(" [ACTION] calling the brain with history:\n{}, context:\n{}, progress:\n{}, instruction:\n{}",
                                history, contextJson, progressTracker, explicitInstruction);
                        String actionResultJson = this.actionBrain.act(explicitInstruction, contextJson, progressTracker, activity.getOpenedManualsView());
                        List<String> learnedNames = ingestManualsFromJson(actionResultJson);
                        boolean documentationLearned = !learnedNames.isEmpty();

                        String historyResult;
                        if (documentationLearned) {
                            historyResult = "SUCCESS: Manuals learned for: " + String.join(", ", learnedNames) +
                                    ". They are stored in CATALOG. Use SETUP phase to mount/read them.";
                        } else {
                            historyResult = actionResultJson;
                        }
                        activity.addStep(new ReasoningStep("act", activity.getGoal(), historyResult, activity.getBeliefsSnapshot()));

                        boolean expectEvent = false;
                        try {
                            JsonNode node = objectMapper.readTree(cleanJson(actionResultJson));
                            if (node.has("expect_event")) expectEvent = node.get("expect_event").asBoolean();
                        } catch (Exception e) {}

                        if (activity.hasEvents()) {
                            logger.info("⚡ [ACTION_END] Events arrived during execution. Skipping wait, going to OBSERVATION.");
                            activity.setStatus(Activity.Status.OBSERVATION);
                        } else if (documentationLearned) {
                            logger.info("📚 [FLOW_CONTROL] Manuals learned. Returning to OBSERVATION.");
                            activity.setStatus(Activity.Status.OBSERVATION);
                        } else if (expectEvent) {
                            logger.info("💤 [SUSPEND] Waiting for event...");
                            activity.setStatus(Activity.Status.WAITING_FOR_EVENT);
                        } else {
                            activity.setStatus(Activity.Status.OBSERVATION);
                        }
                        activityQueue.offer(activity);
                    }

                    case OBSERVATION -> {
                        logger.info("👁️ [OBSERVATION] Analyzing Events for Goal: '{}'", activity.getGoal());
                        List<JsonNode> eventsList = activity.consumeEvents();
                        String eventsJson = "[]";
                        try { eventsJson = objectMapper.writeValueAsString(eventsList); } catch (Exception e) {}
                        if(!eventsList.isEmpty()) logger.info("👀 [OBSERVATION] Processing {} incoming events.", eventsList.size());

                        ensureMemoriesLoaded(activity);
                        logger.debug(" [OBSERVATION] calling the brain with history:\n{}, context:\n{}, events:\n{}, progress:\n{}",
                                history, contextJson, eventsJson, progressTracker);
                        String obsResult = this.reasoningBrain.observe(activity.getGoal(), history, contextJson, eventsJson, progressTracker, activity.getOpenedManualsView(), activity.getHandledEventsToMarkdown());
                        try {
                            JsonNode obsNode = objectMapper.readTree(cleanJson(obsResult));
                            if (obsNode.has("new_progress")) activity.setBelief("goal_progress", TextNode.valueOf(obsNode.get("new_progress").asText()));
                        } catch (Exception e) {}

                        activity.addStep(new ReasoningStep("observe", activity.getGoal(), obsResult, activity.getBeliefsSnapshot(), eventsList));

                        if (parseCompleted(obsResult)) {
                            activity.setStatus(Activity.Status.COMPLETED);
                            activityQueue.offer(activity);
                        } else {
                            activity.setStatus(Activity.Status.REASONING);
                            activityQueue.offer(activity);
                        }
                    }

                    case COMPLETED -> {
                        logger.info("🎉 Activity {} completed. Starting REFLECTION & MEMORY STORAGE...", activity.getUuid());
                        String fullHistory = extractActivityHistory(activity, 100);

                        String reflectionJson = this.reasoningBrain.reflect(activity.getGoal(),
                                "COMPLETED",
                                fullHistory);
                        if (reflectionJson != null && !reflectionJson.isBlank()) {
                            try {
                                String cleanJson = cleanJson(reflectionJson);
                                JsonNode memNode = objectMapper.readTree(cleanJson);


                                String summary = memNode.has("summary") ? memNode.get("summary").asText() : "";
                                String outcome = memNode.has("outcome") ? memNode.get("outcome").asText() : "UNKNOWN";

                                List<String> procedure = new ArrayList<>();
                                if (memNode.has("successful_procedure")) {
                                    memNode.get("successful_procedure").forEach(n -> procedure.add(n.asText()));
                                }

                                EpisodicMemory memory = new agent.memory.EpisodicMemory(
                                        activity.getGoal(),
                                        outcome,
                                        summary,
                                        procedure
                                );

                                agentMemory.save(memory);

                            } catch (Exception e) {
                                logger.warn("Failed to save memory for activity {}", activity.getUuid(), e);
                            }
                        }

                        activityRegistry.remove(activity.getUuid());

                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { logger.error("Loop Error", e); }
        }
    }

    private List<String> ingestManualsFromJson(String jsonText) {
        List<String> savedNames = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(cleanJson(jsonText));
            if (root.has("learned_manuals") && root.get("learned_manuals").isArray()) {
                JsonNode manualsArray = root.get("learned_manuals");
                for (JsonNode manual : manualsArray) {
                    String toolName = manual.has("tool_name") ? manual.get("tool_name").asText() : "";
                    String content = manual.has("content") ? manual.get("content").asText() : "";
                    if (!toolName.isBlank() && !content.isBlank()) {
                        String normalizedName = toolName.trim().toLowerCase().replace(" ", "_");
                        agentMemory.ingestManual(new ToolManual(normalizedName,
                                manual.has("description") ? manual.get("description").asText() : "",
                                content));
                        savedNames.add(normalizedName);
                        logger.info("💾 [INGEST_SUCCESS] Saved: '{}'", normalizedName);
                    }
                }
            }
        } catch (Exception e) { logger.warn("⚠️ Ingest parse failed."); }
        return savedNames;
    }

    private void startSseListener() {
        logger.info("🎧 Starting Async SSE Listener on {}", sseUrl);


        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .header("Accept", "text/event-stream")
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.fromLineSubscriber(new Flow.Subscriber<String>() {
            private Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription sub) { this.sub = sub; sub.request(1); }
            @Override public void onNext(String line) {
                if (line.startsWith("data:")) handleMcpEvent(line.substring(5).trim());
                sub.request(1);
            }
            @Override public void onError(Throwable t) { logger.error("❌ SSE Error", t); }
            @Override public void onComplete() { logger.info("✅ SSE Stream Closed"); }
        }));
    }

    private void handleMcpEvent(String json) {
        try {
            if (json.isEmpty() || json.equals("[DONE]")) return;
            JsonNode root = objectMapper.readTree(json);
            JsonNode params = root.has("params") ? root.get("params") : root;
            String msgUuid = params.has("uuid") ? params.get("uuid").asText() : null;
            if (msgUuid == null) return;
            Activity targetActivity = activityRegistry.get(msgUuid);
            if (targetActivity == null) return;

            String mcpType = params.has("mcpType") ? params.get("mcpType").asText() : "";
            if ("variable".equalsIgnoreCase(mcpType)) {
                targetActivity.setBelief(params.get("name").asText(), params.get("value"));
                logger.info("🔁 Belief update: {} -> {}", params.get("name").asText(), params.get("value"));
            } else if ("event".equalsIgnoreCase(mcpType)) {
                JsonNode payload = params.has("event") ? params.get("event") : params;
                targetActivity.pushEvent(payload);
                logger.info("📥 Event received: {}", payload);
                if (targetActivity.getStatus() == Activity.Status.WAITING_FOR_EVENT) {
                    targetActivity.setStatus(Activity.Status.OBSERVATION);
                    activityQueue.offer(targetActivity);
                    logger.info("🔔 WAKING UP Activity {} -> Resumed to OBSERVATION", msgUuid);
                }
            }
        } catch (Exception e) { logger.error("MCP Event Error", e); }
    }



    private String cleanJson(String response) {
        if (response == null || response.isBlank()) return "{}";

        try {
            int start = response.indexOf("{");
            int end = response.lastIndexOf("}");

            if (start != -1 && end != -1 && end > start) {
                return response.substring(start, end + 1);
            }
        } catch (Exception e) {
            logger.warn("⚠️ [JSON_CLEAN_FAIL] Failed to extract JSON from: {}", response);
        }
        logger.info("🔍 [JSON_CLEAN] Returning original response as fallback: {}", response.trim());
        return response.trim();
    }
    private String buildContextJson(Activity activity) {
        try {
            Map<String,Object> c=new HashMap<>();
            c.put("uuid", activity.getUuid());
            c.put("environment", activity.getBeliefsSnapshot());
            return objectMapper.writeValueAsString(c);
        } catch(Exception e){return "{}";}
    }

    private String buildContextMarkdown(Activity activity) {
        StringBuilder sb=new StringBuilder();
        sb.append("- **Activity ID (UUID):** ").append(activity.getUuid()).append("\n\n");
        sb.append("```\n").append(activity.getBeliefsSnapshotToMarkdown()).append("\n```\n");
        return sb.toString();
    }

    private String extractProgressTracker(Activity a) { JsonNode n=a.getBelief("goal_progress"); return n!=null?n.asText():"(No plan)"; }
    private String extractProgressTrackerMarkdown(Activity a) {
        String progress=extractProgressTracker(a);
        StringBuilder sb=new StringBuilder();
        sb.append("```\n").append(progress).append("\n```\n");
        return sb.toString();
    }
    private String extractActivityHistory(Activity a, int w) {
        StringBuilder sb=new StringBuilder();
        List<ReasoningStep> h=a.getHistory();
        int s=Math.max(0,h.size()-w);
        for(int i=s;i<h.size();i++) sb.append(h.get(i).toJson()).append("\n");
        return sb.toString();
    }

    private String extractActivityHistoryMarkdown(Activity activity, int windowSize) {
        StringBuilder sb = new StringBuilder();
        List<ReasoningStep> history = activity.getHistory();
        int start = Math.max(0, history.size() - windowSize);

        for (int i = start; i < history.size(); i++) {
            ReasoningStep step = history.get(i);
            sb.append(step.toMarkdown()).append("\n");
        }
        return sb.toString();
    }




    private boolean parseCompleted(String obsResult) {
        if (obsResult == null || obsResult.isBlank()) return false;

        try {
            String cleanObs = cleanJson(obsResult);
            JsonNode node = objectMapper.readTree(cleanObs);

            if (node.has("completed")) {
                JsonNode completedNode = node.get("completed");
                if (completedNode.isBoolean()) {
                    return completedNode.asBoolean();
                }
                if (completedNode.isTextual()) {
                    return Boolean.parseBoolean(completedNode.asText());
                }
            }

            if (node.has("result")) {
                JsonNode resultNode = node.get("result");
                if (resultNode.has("completed")) {
                    return resultNode.get("completed").asBoolean(false);
                }
            }

        } catch (Exception e) {

            logger.warn("⚠️ Could not parse JSON for completion check. Keeping activity alive. Response: {}", obsResult);
        }

        return false;
    }
    private void ensureMemoriesLoaded(Activity a) {
        if(!a.areMemoriesLoaded()) {
            a.addMemories(agentMemory.retrieveRelevantMemories(a.getGoal(), 2));
            a.setMemoriesLoaded(true);
        }
    }

    public T brain () { return this.reasoningBrain; }
    public T actionBrain () { return this.actionBrain; }

    public static class Builder<T extends ReactBrain> {
        private ChatModel model;
        private Class<T> agentInterface;
        private Object[] tools;
        private ArrayList<Document> documents;
        private McpToolProvider mcpToolProvider;
        private String sseUrl;
        public Builder<T> model(ChatModel model) { this.model = model; return this; }
        public Builder<T> agentInterface(Class<T> agentInterface) { this.agentInterface = agentInterface; return this; }
        public Builder<T> mcpToolProvider(McpToolProvider provider) { this.mcpToolProvider = provider; return this; }
        public Builder<T> sseUrl(String url) { this.sseUrl = url; return this; }
        public Builder<T> tools(Object... tools) { this.tools = tools; return this; }
        public Builder<T> documents(ArrayList<Document> documents) { this.documents = documents; return this; }
        public AsyncAgent<T> build() {
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(agentInterface, "agentInterface must not be null");
            return new AsyncAgent<>(this);
        }
    }
}

