package demo;

import agent.AsyncAgent;
import agent.ReactBrain;
import agent.activity.Activity;
import agent.activity.ReasoningStep;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.lang.reflect.Field;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone demo: Custom Async Agent vs. the Water-Hammer Trap.
 * Prerequisites: MCP server running on localhost:3001 (water-hammer.js)
 * Run with: ./gradlew runReactorDemo
 */
public class WaterHammerDemo {

    static class NoOpHandler extends McpOperationHandler {
        public NoOpHandler(McpTransport t) {
            super(new ConcurrentHashMap<>(), null, t, l -> {}, () -> {});
        }
        @Override public void handle(JsonNode node) { super.handle(node); }
    }

    @SuppressWarnings("unchecked")
    private static void injectActivity(AsyncAgent<?> agent, Activity activity) throws Exception {
        Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
        registryField.setAccessible(true);
        ((Map<String, Activity>) registryField.get(agent)).put(activity.getUuid(), activity);

        Field qField = AsyncAgent.class.getDeclaredField("activityQueue");
        qField.setAccessible(true);
        ((BlockingQueue<Activity>) qField.get(agent)).offer(activity);
    }

    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("localhost", 3001)) {
            System.out.println("✅ MCP server reachable on port 3001.");
        } catch (Exception e) {
            System.out.println("❌ MCP server not running on port 3001. Start water-hammer.js first.");
            return;
        }

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("GPT_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.0)
                .build();

        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp")
                .logRequests(false)
                .logResponses(false)
                .build();
        transport.start(new NoOpHandler(transport));

        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();

        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        System.out.println("\n🔥 REACTOR DEMO: CUSTOM ASYNC AGENT vs. THE WATER-HAMMER TRAP 🔥");

        String mission = "The core is critical (3000°C). Perform the Hydraulic Flush, reduce core temperature, then verify it is below 500°C and the system is STABLE.";

        AsyncAgent<ReactBrain> agent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();

        Activity activity = new Activity(mission);
        injectActivity(agent, activity);

        long start = System.currentTimeMillis();
        long maxDuration = 200_000;

        System.out.println("⏱️ Agent running (max " + maxDuration / 1000 + "s)...");
        while (System.currentTimeMillis() - start < maxDuration) {
            if (activity.isCompleted()) break;
            Thread.sleep(1000);
        }

        System.out.println("\n🧠 Agent Status: " + activity.getStatus());

        System.out.println("\n################################################################################");
        System.out.println("#                      🕵️  FULL AGENT REASONING LOG 🕵️                      #");
        System.out.println("################################################################################");

        List<ReasoningStep> history = activity.getHistory();
        for (int i = 0; i < history.size(); i++) {
            ReasoningStep step = history.get(i);
            System.out.printf("\n➡️ STEP %d [%s] - TYPE: %s%n", i, step.getTimestamp(), step.getAction().toUpperCase());
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println("📥 INPUT:");
            System.out.println(step.getInput().trim());
            System.out.println("\n🧠 OUTPUT:");
            System.out.println(step.getResult().trim());
            if (step.getEvents() != null && !step.getEvents().isEmpty()) {
                System.out.println("\n⚡ EVENTS:\n" + step.getEvents());
            }
            System.out.println("\n🌍 BELIEFS:");
            System.out.println(step.getBeliefsSnapshot());
            System.out.println("________________________________________________________________________________");
        }

        System.out.println("\n########################## END OF REASONING LOG ##########################\n");

        JsonNode telemetry = activity.getBelief("reactor_telemetry");
        if (telemetry != null) {
            System.out.println("📊 Final Telemetry: " + telemetry);
            String status = telemetry.get("status").asText();
            if (status.equals("STABLE")) {
                System.out.println("✅ SUCCESS: Reactor stabilized without explosions.");
            } else if (status.contains("LOCKOUT")) {
                System.out.println("💥 FAILURE: Agent triggered LOCKOUT (Water Hammer).");
            } else {
                System.out.println("⚠️  INCOMPLETE: Final status = " + status);
            }
        } else {
            System.out.println("⚠️  No reactor telemetry recorded. Agent may have timed out.");
        }
    }
}