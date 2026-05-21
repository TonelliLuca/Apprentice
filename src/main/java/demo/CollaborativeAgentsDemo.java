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
 * Standalone demo: two collaborative agents (EVEN and ODD) sharing a counter.
 * Prerequisites: MCP server running on localhost:3001 (counter server)
 * Run with: ./gradlew runCollaborativeDemo
 */
public class CollaborativeAgentsDemo {

    static final int NUMBER_TO_REACH = 5;

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

    private static AsyncAgent<ReactBrain> createAgent(OpenAiChatModel model) {
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

        return new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();
    }

    private static void printHistory(String name, Activity activity) {
        System.out.println("\n📜 FULL HISTORY — AGENT " + name);
        List<ReasoningStep> history = activity.getHistory();
        for (ReasoningStep step : history) {
            System.out.println("[" + step.getTimestamp() + "] ACTION: " + step.getAction());
            System.out.println("   INPUT:   " + step.getInput());
            System.out.println("   RESULT:  " + step.getResult());
            System.out.println("   BELIEFS: " + step.getBeliefsSnapshot());
            if (!step.getEvents().isEmpty()) {
                System.out.println("   EVENTS:  " + step.getEvents());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("localhost", 3001)) {
            System.out.println("✅ MCP server reachable on port 3001.");
        } catch (Exception e) {
            System.out.println("❌ MCP server not running on port 3001. Start the counter server first.");
            return;
        }

        System.out.println("\n🤝 COLLABORATIVE AGENTS DEMO: EVEN & ODD COUNTER 🤝");
        System.out.println("Target counter value: " + NUMBER_TO_REACH);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("GPT_API_KEY"))
                .modelName("gpt-4o")
                .temperature(0.3)
                .build();

        AsyncAgent<ReactBrain> agentEven = createAgent(model);
        AsyncAgent<ReactBrain> agentOdd  = createAgent(model);

        String missionEven = "You are the EVEN agent: find the counter and increment it only if the number is EVEN, continue until it exceeds " + NUMBER_TO_REACH + ".";
        String missionOdd  = "You are the ODD agent: find the counter and increment it only if the number is ODD, continue until it exceeds " + NUMBER_TO_REACH + ".";

        Activity activityEven = new Activity(missionEven);
        Activity activityOdd  = new Activity(missionOdd);

        injectActivity(agentEven, activityEven);
        injectActivity(agentOdd,  activityOdd);

        long start       = System.currentTimeMillis();
        long maxDuration = 120_000;
        boolean evenDone = false;
        boolean oddDone  = false;

        System.out.println("⏱️ Simulation running (max " + maxDuration / 1000 + "s)...");

        while (System.currentTimeMillis() - start < maxDuration) {
            if (activityEven.isCompleted()) evenDone = true;
            if (activityOdd.isCompleted())  oddDone  = true;
            if (evenDone && oddDone) {
                System.out.println("✅ Both agents completed!");
                break;
            }
            Thread.sleep(2000);
        }

        System.out.println("\n📊 FINAL REPORT");
        System.out.println("Agent EVEN completed: " + evenDone);
        System.out.println("Agent ODD  completed: " + oddDone);
        System.out.println("Final counter (EVEN view): " + activityEven.getBelief("shared_counter"));
        System.out.println("Final counter (ODD  view): " + activityOdd.getBelief("shared_counter"));

        printHistory("EVEN", activityEven);
        printHistory("ODD",  activityOdd);

        if (!evenDone || !oddDone) {
            System.out.println("\n❌ Simulation timed out — agents did not reach " + NUMBER_TO_REACH + " in time.");
        } else {
            int finalValue = activityEven.getBelief("shared_counter") != null
                    ? activityEven.getBelief("shared_counter").asInt() : 0;
            if (finalValue >= NUMBER_TO_REACH) {
                System.out.println("\n✅ SUCCESS: Counter reached " + finalValue + " through collaboration.");
            } else {
                System.out.println("\n⚠️  Counter only reached " + finalValue + " (expected >= " + NUMBER_TO_REACH + ").");
            }
        }
    }
}
