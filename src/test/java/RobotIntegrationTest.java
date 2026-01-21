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

import static org.junit.jupiter.api.Assertions.fail;

public class RobotIntegrationTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "true");
        System.setProperty("jdk.internal.httpclient.disableHttp2", "true");
    }

    OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("GPT_API_KEY"))
            .modelName("gpt-4o-mini") // O "gpt-4o" per maggiore intelligenza
            .temperature(0.0) // Meglio 0.0 per task deterministici
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
    void test_Pick_And_Place_Simulation() throws Exception {
        // 1. Preliminary check: Python server
        try (Socket s = new Socket("localhost", 8000)) {
            System.out.println("✅ Python server detected on port 8000.");
        } catch (Exception e) {
            System.out.println("⚠️ ERROR: Python MCP server is not running on localhost:8000.");
            return;
        }

        System.out.println("🤖 STARTING PICK & PLACE TEST 🤖");

        // 2. MCP configuration
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:8000/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();

        transport.start(new NoOpHandler(transport));

        McpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(60))
                .build();

        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(List.of(client))
                .build();

        // 3. Agent creation
        AsyncAgent<ReactBrain> robotAgent = new AsyncAgent.Builder<ReactBrain>()
                .model(model)
                .agentInterface(ReactBrain.class)
                .mcpToolProvider(provider)
                .sseUrl("http://localhost:8000/sse")
                .build();

        // 4. Define the "Pick and Place" mission
        String mission = """
            There is a cube in the environment find the location of the cube, pick it up and raise the cube to position X = 430, Y = 0, Z = 530.
            Remember if the gripper start in a closed position you need to open it first to be able to pick the cube.
        """;

        Activity activity = new Activity(mission);
        injectActivity(robotAgent, activity);

        System.out.println("🚀 Pick&Place mission sent: " + activity.getUuid());

        // 5. Waiting loop with SMART CHECK
        long startTime = System.currentTimeMillis();
        long maxDuration = 300000;
        boolean success = false;

        while (System.currentTimeMillis() - startTime < maxDuration) {
            if (activity.isCompleted()) {
                System.out.println("🎉 Agent marked the activity as COMPLETED.");
                success = true;
                break;
            }

            JsonNode telemetry = activity.getBelief("robot_telemetry");
            if (telemetry != null && telemetry.has("position")) {
                JsonNode pos = telemetry.get("position");
                JsonNode grip = telemetry.get("gripper");

                double currentZ = pos.has("z") ? pos.get("z").asDouble() : 0.0;
                int currentGripper = grip != null ? grip.asInt() : 800;

                if (currentZ > 515.0 && currentGripper < 10) {
                    System.out.println("\n🚀 SMART CHECK SUCCESS: Target state physically reached!");
                    System.out.println("   Position Z: " + currentZ + " | Gripper: " + currentGripper);
                    success = true;
                    break;
                }
            }

            Thread.sleep(1000);
            if (activity.getStatus() != null) {
                System.out.print("\r⏳ Agent Status: " + activity.getStatus() + " | Steps: " + activity.getHistory().size());
            }
        }
        System.out.println();

        // 6. Log analysis
        System.out.println("\n################ AGENT REASONING LOG ################");
        activity.getHistory().forEach(step -> {
            System.out.printf("➡️ [%s] ACTION: %s\n", step.getTimestamp(), step.getAction());
            System.out.println("   🧠 Result: " + step.getResult());
            if (step.getEvents() != null && !step.getEvents().isEmpty()) {
                System.out.println("   ⚡ EVENTS CAUGHT: " + step.getEvents());
            }
        });
        System.out.println("#####################################################");

        // 7. Final checks (Pick & Place asserts)
        if (!success && !activity.isCompleted()) {
            fail("❌ Timeout: Mission incomplete.");
        }

        JsonNode telemetry = activity.getBelief("robot_telemetry");
        System.out.println("\n📊 Final Telemetry: " + telemetry);

        if (telemetry != null) {
            JsonNode pos = telemetry.get("position");
            JsonNode grip = telemetry.get("gripper");

            // CHECK 1: Height (Z) - should have lifted the cube
            if (pos != null) {
                double z = pos.get("z").asDouble();
                if (z > 200.0) {
                    System.out.println("✅ TEST PASSED: Cube lifted (Z=" + z + ")");
                } else {
                    fail("⚠️ Warning: Arm is low (Z=" + z + "). It should be > 200.");
                }
            }

            // CHECK 2: Gripper - should be closed (or nearly closed)
            if (grip != null) {
                int gVal = grip.asInt();
                if (gVal < 100) {
                    System.out.println("✅ TEST PASSED: Gripper closed (Val=" + gVal + ")");
                } else {
                    fail("⚠️ Warning: Gripper open (Val=" + gVal + "). Did it drop the cube?");
                }
            }
        } else {
            fail("❌ No telemetry received.");
        }
    }
}