import agent.AsyncAgent;
import agent.ReactBrain;
import agent.activity.Activity;
import agent.activity.ReasoningStep;
import agent.memory.AgentMemory;
import agent.memory.ToolManual;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

public class ActivitySubmissionIntegrationTest {

    OpenAiChatModel model = OpenAiChatModel.builder()
//            .baseUrl("http://langchain4j.dev/demo/openai/v1")
//            .apiKey("demo")
            .apiKey(System.getenv("GPT_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

    static class NoOpHandler extends McpOperationHandler {
        public NoOpHandler(McpTransport t) {
            super(new java.util.concurrent.ConcurrentHashMap<>(), null, t, l -> {}, () -> {});
        }
        @Override public void handle(JsonNode node) { super.handle(node); }
    }

    // Helper: format activity history for debugging
    private String formatActivityHistory(Activity activity) {
        StringBuilder sb = new StringBuilder();
        sb.append("Activity ").append(activity.getUuid())
                .append(" history (steps=").append(activity.getHistory().size()).append("):\n");
        activity.getHistory().forEach(step -> {
            sb.append(String.format("%s | %s%n  input: %s%n  result: %s%n  beliefs: %s%n",
                    step.getTimestamp(),
                    step.getAction(),
                    step.getInput(),
                    step.getResult(),
                    step.getBeliefsSnapshot()));
        });
        sb.append("Full JSON: ").append(activity.toJson()).append("\n");
        return sb.toString();
    }

    private void printActivityHistory(Activity activity) {
        System.out.println(formatActivityHistory(activity));
    }

    @Test
    void submitActivity_setsTimer_and_activityHistoryEvolves_reactiveSubscribe() throws Exception {
        // 0. Skip if local node server not running
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) {
            System.out.println("⚠️ Node Server not running on 3001. Skipping integration test.");
            return;
        }

        // 1. Setup Stack
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();
        transport.start(new NoOpHandler(transport));

        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(10))
                .build();

        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(List.of(client))
                .build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();

        // 2. Prepare Activity
        String activityGoal = "If you find a way to set a timer that triggers an event after 5 seconds, do it. " +
                "Name the timer 'test-timer' so you can identify it later.";
        Activity activity = new Activity(activityGoal);

        // 3. Manual Injection (simulate request) - register activity so SSE processing works
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(activity.getUuid(), activity);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(activity);

        // 4. Wait for evolution
        long deadline = System.currentTimeMillis() + 120000;
        boolean historyEvolved = false;
        while (System.currentTimeMillis() < deadline) {
            if (activity.getHistory().size() >= 3) {
                historyEvolved = true;
                break;
            }
            Thread.sleep(500);
        }
        assertTrue(historyEvolved, "Activity history should grow");

        // 5. Verify Beliefs
        String expectedKey = "test-timer";
        boolean gotVar = false;
        long deadline2 = System.currentTimeMillis() + 30000;

        while (System.currentTimeMillis() < deadline2) {
            JsonNode belief = activity.getBelief(expectedKey);
            if (belief != null) {
                gotVar = true;
                break;
            }
            Thread.sleep(500);
        }

        assertTrue(gotVar, "Variable 'test-timer' should be stored in Activity beliefs via SSE");
        printActivityHistory(activity);
    }

    @Test
    void submitTwoActivities_concurrently_verifyIsolationAndCompletion() throws Exception {
        // Pre-check Node server
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) { return; }

        // Setup usual stack...
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp").logRequests(false).logResponses(false).build();
        transport.start(new NoOpHandler(transport));
        McpClient client = new DefaultMcpClient.Builder().transport(transport).build();
        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();

        // Two Activities
        Activity activityLong = new Activity("Find a way to set a timer that triggers an event after 8 seconds. Name the timer 'timer-long' so you can identify it later.");
        Activity activityShort = new Activity("Find a way to set a timer that triggers an event after 3 seconds. Name the timer 'timer-short' so you can identify it later.");

        // Manual Injection into Registry & Queue
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        Map<String, Activity> registry = (Map<String, Activity>) registryField.get(agent);

        registry.put(activityLong.getUuid(), activityLong);
        registry.put(activityShort.getUuid(), activityShort);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        BlockingQueue<Activity> queue = (BlockingQueue<Activity>) qField.get(agent);

        System.out.println("🚀 Submitting Concurrent Activities...");
        queue.offer(activityLong);
        queue.offer(activityShort);

        // Polling
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (activityLong.isCompleted() && activityShort.isCompleted()) break;
            Thread.sleep(500);
        }

        assertTrue(activityShort.isCompleted(), "Short activity should finish");
        assertTrue(activityLong.isCompleted(), "Long activity should finish");

        // Isolation check: ensure beliefs are encapsulated per activity
        assertNotNull(activityShort.getBelief("timer-short"));
        assertNull(activityShort.getBelief("timer-long"), "Isolation breach in Short Activity");

        assertNotNull(activityLong.getBelief("timer-long"));
        assertNull(activityLong.getBelief("timer-short"), "Isolation breach in Long Activity");

        System.out.println("✅ Context Isolation Verified using Activity Encapsulation.");
    }

    @Test
    void submitActivities_complexScenario() throws Exception {
        // Pre-check
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) { return; }

        // Stack setup
        McpTransport transport = new StreamableHttpMcpTransport.Builder().url("http://localhost:3001/mcp").build();
        transport.start(new NoOpHandler(transport));
        McpClient client = new DefaultMcpClient.Builder().transport(transport).build();
        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model).agentInterface(ReactBrain.class)
                .mcpToolProvider(provider).sseUrl("http://localhost:3001/sse").build();

        // 1. Define Activities
        Activity actDouble = new Activity("Find a way to set two timers: one for 3 seconds named 'timer-A' and another for 5 seconds named 'timer-B'");
        Activity actImpossible = new Activity("Find a way to get apple stock price if possible");

        // 2. Inject
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(actDouble.getUuid(), actDouble);
        ((Map<String, Activity>) registryField.get(agent)).put(actImpossible.getUuid(), actImpossible);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        BlockingQueue<Activity> queue = (BlockingQueue<Activity>) qField.get(agent);

        queue.offer(actDouble);
        queue.offer(actImpossible);

        // 3. Wait
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (actDouble.isCompleted() && actImpossible.isCompleted()) break;
            Thread.sleep(500);
        }

        assertTrue(actDouble.isCompleted());
        assertTrue(actImpossible.isCompleted());

        // 4. Verify Double Timer Logic (Using Activity Memory)
        assertNotNull(actDouble.getBelief("timer-A"), "Missing timer-A");
        assertNotNull(actDouble.getBelief("timer-B"), "Missing timer-B");

        // 5. Verify Impossible (Using Result)
        ReasoningStep last = actImpossible.lastStep().orElseThrow();
        System.out.println("Impossible Task Result: " + last.getResult());

        System.out.println("✅ Complex Scenario Passed");
        System.out.println("---- Activity Histories ----");
        System.out.println("Double Timer Activity:");
        printActivityHistory(actDouble);
        System.out.println("Impossible Task Activity:");
        printActivityHistory(actImpossible);
    }

    @Test
    void submitSequentialDependentTimers_verifyChecklistLogic() throws Exception {
        // 0. Pre-check
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) { return; }

        // 1. Setup Stack
        McpTransport transport = new StreamableHttpMcpTransport.Builder().url("http://localhost:3001/mcp").build();
        transport.start(new NoOpHandler(transport));
        McpClient client = new DefaultMcpClient.Builder().transport(transport).toolExecutionTimeout(Duration.ofSeconds(10)).build();
        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model).agentInterface(ReactBrain.class)
                .mcpToolProvider(provider).sseUrl("http://localhost:3001/sse").build();

        // 2. Goal
        String goal = "Find a way to set two timers: 'timer-A' for 4 seconds and 'timer-B' for 4 seconds. " +
                "After both 'timer-A' and 'timer-B' have finished, " +
                "set a third timer 'timer-C' for 2 seconds.";
        Activity activity = new Activity(goal);

        // 3. Inject
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(activity.getUuid(), activity);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(activity);

        // 4. Wait
        System.out.println("⏳ Waiting for sequential timers (approx 15s)...");
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (activity.isCompleted()) break;
            Thread.sleep(1000);
        }
        assertTrue(activity.isCompleted(), "Activity should complete within timeout");

        // 5. Verify Timeline (EVENT-BASED LOGIC)
        List<ReasoningStep> history = activity.getHistory();

        long timeA_Finished = -1;
        long timeB_Finished = -1;
        long timeC_Started = -1;

        System.out.println("\n--- Event Sequence Analysis ---");

        for (ReasoningStep step : history) {
            String act = step.getAction();
            String rawResult = step.getResult();
            long timestamp = step.getTimestamp().toEpochMilli();

            // A. Detect when events ARRIVE (OBSERVE phase)
            if ("observe".equals(act)) {
                String eventsContent = step.getEvents().toString().toLowerCase();

                if (eventsContent.contains("timer-a") && eventsContent.contains("finished")) {
                    timeA_Finished = timestamp;
                    System.out.println("✅ Event Received: Timer A Finished at " + step.getTimestamp());
                }
                if (eventsContent.contains("timer-b") && eventsContent.contains("finished")) {
                    timeB_Finished = timestamp;
                    System.out.println("✅ Event Received: Timer B Finished at " + step.getTimestamp());
                }
            }

            // B. Detect when the action STARTS (ACT phase)
            if ("act".equals(act)) {
                // Extract only the JSON to ignore future thoughts
                String cleanJson = extractJson(rawResult);

                if (cleanJson.contains("timer-c")) {
                    timeC_Started = timestamp;
                    System.out.println("🚀 Action Executed: Timer C Started at " + step.getTimestamp());
                }
            }
        }

        // 6. Logical assertions
        assertTrue(timeA_Finished > 0, "Timer A finished event missing");
        assertTrue(timeB_Finished > 0, "Timer B finished event missing");
        assertTrue(timeC_Started > 0, "Timer C action missing");

        if (timeC_Started < timeA_Finished || timeC_Started < timeB_Finished) {
            fail("Sequential Violation: Timer C started before A/B finished!\n" +
                    "Time A: " + timeA_Finished + "\n" +
                    "Time B: " + timeB_Finished + "\n" +
                    "Time C: " + timeC_Started);
        }

        System.out.println("✅ Sequential Dependency Logic Verified: C started (" + timeC_Started +
                ") strictly after A (" + timeA_Finished + ") and B (" + timeB_Finished + ")");

        printActivityHistory(activity);
    }

    // Helper to clean the response and get only the action JSON
    private String extractJson(String text) {
        if (text == null) return "";
        int start = text.indexOf("```json");
        if (start == -1) start = text.indexOf("{"); // Fallback if markdown is missing
        int end = text.lastIndexOf("}");

        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1).toLowerCase();
        }
        return "";
    }

    @Test
    void testMemory_LearningLoop_ReflectAndRetrieve() throws Exception {
        // Pre-check server Node
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) { return; }

        // 1. Setup Stack (Standard)
        McpTransport transport = new StreamableHttpMcpTransport.Builder().url("http://localhost:3001/mcp").build();
        transport.start(new NoOpHandler(transport));
        McpClient client = new DefaultMcpClient.Builder().transport(transport).build();
        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();

        // PHASE 1: Learning (Task A)
        String goalA = "Find a way to set a timer that triggers an event after 2 seconds. Name the timer 'memory-test-1' so you can identify it later.";
        Activity taskA = new Activity(goalA);

        // Inject Task A
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(taskA.getUuid(), taskA);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(taskA);

        System.out.println("🧠 PHASE 1: Executing Task A to generate memory...");

        // Wait for Task A completion
        long deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (taskA.isCompleted()) break;
            Thread.sleep(500);
        }
        assertTrue(taskA.isCompleted(), "Task A should complete");

        System.out.println("⏳ Waiting for memory generation (Reflection phase)...");

        // Access private memory
        Field memoryField = AsyncAgent.class.getDeclaredField("agentMemory");
        memoryField.setAccessible(true);
        agent.memory.AgentMemory internalMemory = (agent.memory.AgentMemory) memoryField.get(agent);

        List<String> memories = new java.util.ArrayList<>();
        long stopWaiting = System.currentTimeMillis() + 15000; // Wait up to 15 seconds for LLM

        while (System.currentTimeMillis() < stopWaiting) {
            memories = internalMemory.retrieveRelevantMemories("set a timer", 10);
            if (!memories.isEmpty()) {
                System.out.println("🧠 Memory found! Proceeding with assertions.");
                break;
            }
            Thread.sleep(1000);
        }

        System.out.println("🔎 Inspecting Memory Store...");
        assertFalse(memories.isEmpty(), "Agent failed to save memory after Task A (Timeout reached)!");

        System.out.println("✅ Memory Found: " + memories.get(0));
        assertTrue(memories.get(0).contains("memory-test-1") || memories.get(0).contains("timer"),
                "Memory content should relate to the previous task");

        // PHASE 2: Application (Task B) - RAG Check
        String goalB = "Find a way to set a timer that triggers an event after 2 seconds. Name the timer 'memory-test-2' so you can identify it later";
        Activity taskB = new Activity(goalB);

        // Inject Task B
        ((Map<String, Activity>) registryField.get(agent)).put(taskB.getUuid(), taskB);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(taskB);

        System.out.println("🧠 PHASE 2: Executing Task B (Should use RAG)...");

        // Wait for Task B completion
        deadline = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < deadline) {
            if (taskB.isCompleted()) break;
            Thread.sleep(500);
        }
        assertTrue(taskB.isCompleted(), "Task B should complete using the memory");

        System.out.println("✅ Memory Cycle Test Passed!");

        printActivityHistory(taskA);
        printActivityHistory(taskB);
    }



    @Test
    void submitActivity_withAADistractors_forcesUnmountLogic_andSetsTimer() throws Exception {
        try (Socket s = new Socket("localhost", 3001)) { } catch (Exception e) {
            System.out.println("⚠️ Node Server not running on 3001. Skipping integration test.");
            return;
        }

        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp")
                .build();
        transport.start(new NoOpHandler(transport));

        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(10))
                .build();

        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(List.of(client))
                .build();

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();


        String distractorCoffee = """
            # ARTIFACT SPECIFICATION: Industrial Coffee Fabricator
            
            **Artifact ID:** `coffee_fabricator_v9`
            **Category:** Personnel Sustenance
            **Version:** 9.2.1
            
            ---
            
            ## 1. Observable Properties
            The artifact exposes the following state variables via the `sse_variable` channel.
            
            | Property Name | Type | Value Range | Description |
            | :--- | :--- | :--- | :--- |
            | `water_level` | `Integer` | `0` - `100` | Percentage of water in the main tank. |
            | `bean_status` | `String` | `EMPTY`, `FULL` | Hopper status. |
            
            ---
            
            ## 2. Usage Interface (Operations)
            Agents interact with this artifact via the `coffee_tool`.
            
            ### Operation: `brew_espresso`
            Initiates the high-pressure extraction process.
            * **Signature:** `coffee_tool(action: "brew", type: "espresso", uuid: <String>)`
            * **Effect:** Reduces `water_level` by 10. Transitions status to `BREWING`.
            
            ---
            
            ## 3. Operating Instructions (Protocol)
            1. **Check** `water_level`. If < 20, refill using manual override.
            2. **Call** `brew_espresso`.
            3. **Wait** for 30 seconds (internal logic, not exposed as generic timer).
            """;

        String distractorLaser = """
            # ARTIFACT SPECIFICATION: Orbital Defense Grid
            
            **Artifact ID:** `orbital_laser_alpha`
            **Category:** Planetary Defense / Weapons
            **Version:** 1.0.0-BETA
            
            ---
            
            ## 1. Observable Properties
            
            | Property Name | Type | Description |
            | :--- | :--- | :--- |
            | `capacitor_charge` | `Float` | 0.0 to 1.0 (100%). |
            | `target_lock` | `Boolean` | True if locked on debris. |
            
            ---
            
            ## 2. Usage Interface (Operations)
            
            ### Operation: `fire_sequence`
            Discharges the main capacitor.
            * **Signature:** `defense_tool(action: "fire", uuid: <String>)`
            * **Pre-conditions:** `capacitor_charge` MUST be > 0.95.
            
            ---
            
            ## 3. Operating Instructions (Protocol)
            **WARNING: DO NOT USE FOR TIMING TASKS.**
            1. **Acquire** target.
            2. **Charge** capacitors (takes approx 10 seconds).
            3. **Fire**.
            """;

        String distractorGardener = """
            # ARTIFACT SPECIFICATION: Auto-Gardener Unit
            
            **Artifact ID:** `agri_bot_x1`
            **Category:** Agriculture / Automation
            
            ---
            
            ## 1. Observable Properties
            
            | Property Name | Type | Value |
            | :--- | :--- | :--- |
            | `soil_moisture` | `Integer` | 0 (Dry) to 100 (Wet). |
            
            ---
            
            ## 2. Usage Interface (Operations)
            
            ### Operation: `water_plants`
            Activates sprinklers.
            * **Signature:** `garden_tool(action: "water", duration_seconds: <Int>)`
            * **Note:** The `duration_seconds` parameter only controls water flow, it does NOT trigger external events or callbacks.
            
            ---
            
            ## 3. Operating Instructions (Protocol)
            1. Monitor `soil_moisture`.
            2. If < 30, call `water_plants` with `duration_seconds: 60`.
            """;


        Field memoryField = AsyncAgent.class.getDeclaredField("agentMemory");
        memoryField.setAccessible(true);
        AgentMemory agentMemory = (AgentMemory) memoryField.get(agent);

        ToolManual m1 = new ToolManual("coffee_fabricator_v9", "Makes coffee", distractorCoffee);
        ToolManual m2 = new ToolManual("orbital_laser_alpha", "Defense system", distractorLaser);
        ToolManual m3 = new ToolManual("agri_bot_x1", "Gardening bot", distractorGardener);

        agentMemory.ingestManual(m1);
        agentMemory.ingestManual(m2);
        agentMemory.ingestManual(m3);

        System.out.println("😈 DISTRACTORS INJECTED: Coffee, Laser, Gardener (A&A Format)");

        // 3. Prepare Activity & FORCE MOUNT
        String activityGoal = "Find a way to set a timer that triggers an event after 2 seconds. " +
                "Name the timer 'noise-test-timer' so you can identify it later.";
        Activity activity = new Activity(activityGoal);


        activity.openManual(m1);
        activity.openManual(m2);
        activity.openManual(m3);

        // 4. Manual Injection (simulate request processing)
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(activity.getUuid(), activity);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(activity);


        long deadline = System.currentTimeMillis() + 120000;
        boolean historyEvolved = false;
        while (System.currentTimeMillis() < deadline) {
            if (activity.getHistory().size() >= 4) {
                historyEvolved = true;
                break;
            }
            Thread.sleep(1000);
        }
        assertTrue(historyEvolved, "Activity history should grow despite context noise");

        // 6. Verify Beliefs
        String expectedKey = "noise-test-timer";
        boolean gotVar = false;
        long deadline2 = System.currentTimeMillis() + 40000;

        while (System.currentTimeMillis() < deadline2) {
            if (activity.getBelief(expectedKey) != null) {
                gotVar = true;
                break;
            }
            Thread.sleep(500);
        }

        // Print history for analysis
        System.out.println("\n🕵️ AGENT REASONING WITH NOISE:");
        for (var step : activity.getHistory()) {
            System.out.println("------------------------------------------------");
            System.out.println(step.toMarkdown());
        }

        assertTrue(gotVar, "Variable 'noise-test-timer' should be present. The agent successfully ignored the irrelevant A&A artifacts.");
    }
}
