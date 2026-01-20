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
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class CollaborativeActivityTest {
    final static int NUMBER_TO_REACH = 5;

    OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("GPT_API_KEY"))
//            .baseUrl("http://langchain4j.dev/demo/openai/v1")
//            .apiKey("demo")
            .modelName("gpt-4o-mini")
            .temperature(0.3)
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


    @Test
    void test_OddEven_Collaboration() throws Exception {
        try (Socket s = new Socket("localhost", 3001)) {} catch (Exception e) {
            System.out.println("⚠️ Skipping: Counter Server not running on port 3001.");
            return;
        }

        System.out.println("🤝 STARTING COLLABORATION TEST: EVEN & ODD AGENTS 🤝");

        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:3001/mcp")
                .logRequests(false)
                .logResponses(false)
                .build();
        transport.start(new CollaborativeAgentsTest.NoOpHandler(transport));

        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();

        McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

        AsyncAgent<ReactBrain> asyncAgent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:3001/sse")
                .build();


        String missionEven = "You are the EVEN activity: find the counter and increment it only if the number is EVEN, continue until it exceeds " + NUMBER_TO_REACH + ".";

        String missionOdd = "You are the ODD activity: find the counter and increment it only if the number is ODD, continue until it exceeds " + NUMBER_TO_REACH + ".";

        Activity activityEven = new Activity(missionEven);
        Activity activityOdd = new Activity(missionOdd);

        injectActivity(asyncAgent, activityEven);
        injectActivity(asyncAgent, activityOdd);

        long startTime = System.currentTimeMillis();
        long maxDuration = 120000; // 2 minuti max

        boolean evenDone = false;
        boolean oddDone = false;

        System.out.println("⏱️ Simulation running...");

        while (System.currentTimeMillis() - startTime < maxDuration) {
            if (activityEven.isCompleted()) evenDone = true;
            if (activityOdd.isCompleted()) oddDone = true;

            if (evenDone && oddDone) {
                System.out.println("✅ Both agents completed their tasks!");
                break;
            }

            JsonNode evenCounter = activityEven.getBelief("shared_counter");
            JsonNode oddCounter = activityOdd.getBelief("shared_counter");

            Thread.sleep(2000);
        }

        System.out.println("\n📊 FINAL REPORT");
        System.out.println("Agent Even Completed: " + evenDone);
        System.out.println("Agent Odd Completed: " + oddDone);

        JsonNode finalValEven = activityEven.getBelief("shared_counter");
        JsonNode finalValOdd = activityOdd.getBelief("shared_counter");

        System.out.println("Final Counter (Even's View): " + finalValEven);
        System.out.println("Final Counter (Odd's View): " + finalValOdd);

        // --- PRINT HISTORY SNIPPETS ---
        printFullHistory("EVEN", activityEven);
        printFullHistory("ODD", activityOdd);

        if (!evenDone || !oddDone) {
            fail("❌ Simulation timed out. Agents failed to collaborate to reach " + NUMBER_TO_REACH);
        }

        int result = finalValEven != null ? finalValEven.asInt() : 0;
        assertTrue(result >= NUMBER_TO_REACH, "Counter should be at least " + NUMBER_TO_REACH);
    }


    private void printFullHistory(String name, Activity activity) {
        System.out.println("\n📜 FULL HISTORY FOR AGENT " + name);
        List<agent.activity.ReasoningStep> history = activity.getHistory();
        for (agent.activity.ReasoningStep step : history) {
            System.out.println("[" + step.getTimestamp() + "] ACTION: " + step.getAction());
            System.out.println("   INPUT: " + step.getInput());
            System.out.println("   RESULT: " + step.getResult());
            System.out.println("   BELIEFS SNAPSHOT: " + step.getBeliefsSnapshot());
            if (!step.getEvents().isEmpty()) {
                System.out.println("   EVENTS: " + step.getEvents());
            }
        }
    }
}
