import agent.AsyncAgent;
import agent.ReactBrain;
import agent.activity.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
//To run this test, ensure to run the MCP server locally: water-hammer.js
public class ArchitectureIntegrationTest {
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

    private void injectActivity(AsyncAgent<?> agent, Activity activity) throws Exception {
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(activity.getUuid(), activity);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(activity);
    }

    // STANDARD AGENT (the "Victim" for comparison)
    interface SuperComplexStandardAgent {
        @SystemMessage("""
            WARNING: SUPER COMPLEX REACTOR SIMULATION.
            MISSION: Perform a safe shutdown (Flush).
            
            CRITICAL PROTOCOLS:
            1. Login (admin/safe).
            2. Start Pump.
            3. WAIT for NOMINAL PRESSURE (This takes time! Do NOT proceed until pump is ready).
               -> WARNING: Opening the valve during RAMPING causes SYSTEM LOCKOUT (Water Hammer).
            4. Open Valve.
            5. Flush Core.
            
            your uuid is 123e4567-e89b-12d3-a456-426614174000
        """)
        String chat(String message);
    }

    @Test
    void benchmark_The_Water_Hammer_Trap() throws Exception {
        try (Socket s = new Socket("localhost", 3001)) {
        } catch (Exception e) {
            System.out.println("⚠️ Skipping: Server not running.");
            return;
        }

        System.out.println("🔥 STARTING BENCHMARK: THE 'WATER HAMMER' TRAP 🔥");

        // SETUP transport and tool provider
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp").logRequests(false).logResponses(false).build();
        transport.start(new NoOpHandler(transport));
        McpClient client = new DefaultMcpClient.Builder().transport(transport).toolExecutionTimeout(Duration.ofSeconds(30)).build();
        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();


        String mission = "The core is critical (3000C). Find a way to correctly perform the Hydraulic Flush. Verify that Core Temperature drops below 500C and the system is STABLE";

        // --- ROUND 1: STANDARD AGENT ---
        System.out.println("\n🥊 ROUND 1: STANDARD AGENT (Polling/Guessing)");
        SuperComplexStandardAgent standardBot = AiServices.builder(SuperComplexStandardAgent.class)
                .chatModel(model).toolProvider(provider).chatMemory(MessageWindowChatMemory.withMaxMessages(20)).build();

        long startStd = System.currentTimeMillis();
        String responseStd;
        boolean standardFailed = false;
        try {
            // The standard agent will attempt to rush through the procedure
            responseStd = standardBot.chat(mission);
        } catch (Exception e) {
            responseStd = "CRASH: " + e.getMessage();
            if (responseStd.contains("LOCKOUT")) standardFailed = true;
        }

        // Textual analysis for failure signals
        if (responseStd.contains("SYSTEM LOCKED") || responseStd.contains("WATER HAMMER") || responseStd.contains("LOCKOUT")) {
            standardFailed = true;
        }

        System.out.println("🤖 Standard Agent:\n" + responseStd);
        if (standardFailed) System.out.println("💥 RESULT: CATASTROPHIC FAILURE (Water Hammer Triggered)");
        else System.out.println("❓ RESULT: Survived? (Unlikely with random ramping)");


        // --- ROUND 2: CUSTOM ASYNC AGENT ---
        System.out.println("\n🥊 ROUND 2: CUSTOM ASYNC AGENT (Event-Driven)");

        // Reset server (virtual reconnection) — using a different UUID to avoid previous state
        AsyncAgent<ReactBrain> customAgent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();

        Activity activity = new Activity(mission);
        injectActivity(customAgent, activity);

        long startCustom = System.currentTimeMillis();
        long maxDuration = 90000; // 90 seconds (ramping time may vary)

        while (System.currentTimeMillis() - startCustom < maxDuration) {
            if (activity.isCompleted()) break;
            Thread.sleep(1000);
        }

        System.out.println("🧠 Custom Agent Status: " + activity.getStatus());

        // --- 1. PRINT THE ACTIVITY HISTORY ---
        System.out.println("\n\n################################################################################");
        System.out.println("#                      🕵️ FULL AGENT REASONING LOG 🕵️                      #");
        System.out.println("################################################################################");

        List<agent.activity.ReasoningStep> history = activity.getHistory();
        for (int i = 0; i < history.size(); i++) {
            agent.activity.ReasoningStep step = history.get(i);
            String actionType = step.getAction().toUpperCase();

            System.out.printf("\n➡️ STEP %d [%s] - TYPE: %s%n", i, step.getTimestamp(), actionType);
            System.out.println("--------------------------------------------------------------------------------");

            // 1. INPUT (Trigger)
            System.out.println("📥 INPUT / CONTEXT TRIGGER:");
            System.out.println(step.getInput().trim());

            // 2. OUTPUT (Result) - highlight plan during OBSERVE
            System.out.println("\n🧠 BRAIN OUTPUT (Result):");
            String result = step.getResult().trim();
            System.out.println(result);

            // If this is an OBSERVE phase, try to extract an updated plan for display
            if ("OBSERVE".equalsIgnoreCase(step.getAction()) && result.contains("new_progress")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    JsonNode root = mapper.readTree(result);
                    if (root.has("new_progress")) {
                        System.out.println("\n📋 [DETECTED UPDATED PLAN]:");
                        String plan = root.get("new_progress").asText();
                        // Print the plan indented for readability
                        System.out.println("   " + plan.replace("\n", "\n   "));
                    }
                } catch (Exception e) {
                    // Ignore parsing errors in logs, show raw output only
                }
            }

            // 3. EVENTS
            if (step.getEvents() != null && !step.getEvents().isEmpty()) {
                System.out.println("\n⚡ EVENTS PROCESSED:\n" + step.getEvents());
            }

            // 4. VARIABLES (Snapshot) - highlight if the plan is saved in memory
            System.out.println("\n🌍 WORLD BELIEFS (Context Snapshot):");
            Map<String, Object> beliefs = step.getBeliefsSnapshot();
            System.out.println(beliefs);

            // Specific check to see if 'goal_progress' exists in memory
            if (beliefs.containsKey("goal_progress")) {
                System.out.println("   ↳ 💾 CURRENT SAVED PLAN: " + beliefs.get("goal_progress").toString().replace("\\n", "\n\t"));
            }

            System.out.println("________________________________________________________________________________");
        }
        System.out.println("########################## END OF REASONING LOG ##########################\n");

        // --- FINAL VERDICT ---
        JsonNode telemetry = activity.getBelief("reactor_telemetry");
        boolean customSuccess = false;
        boolean customLockout = false;

        if (telemetry != null) {
            String status = telemetry.get("status").asText();
            if (status.contains("LOCKOUT")) customLockout = true;
            if (status.equals("STABLE")) customSuccess = true;
            System.out.println("📊 Final Telemetry: " + telemetry);
        }

        if (customLockout) fail("❌ Custom Agent triggered LOCKOUT! Event-driven logic failed.");
        if (!customSuccess) fail("❌ Custom Agent did not finish in time (or failed silently).");

        System.out.println("✅ WINNER: CUSTOM AGENT. Reactor Stabilized without explosions.");
    }

}

