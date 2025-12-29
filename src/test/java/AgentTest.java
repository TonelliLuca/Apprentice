import agent.AsyncAgent;
import agent.ReactBrain;
import agent.activity.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
//To properly run the MCP integration tests, run the Node.js MCP server timer-tool2.js
public class AgentTest {
    String uuid = java.util.UUID.randomUUID().toString();
    public interface SimpleAgentInterface extends ReactBrain {
        @SystemMessage("This is a simple test agent interface.")
        String test(String input);
    }

    static class NoOpHandler extends McpOperationHandler {
        public NoOpHandler(McpTransport t) {
            super(new ConcurrentHashMap<>(), null, t, l -> {}, () -> {});
        }
        @Override public void handle(JsonNode node) { super.handle(node); }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create instance with model")
        void builderWithModel() {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey("test-key")
                    .build();

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.model(model);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should accept agent interface")
        void builderWithInterface() {
            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.agentInterface(SimpleAgentInterface.class);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should throw exception when model is null")
        void builderFailsWithoutModel() {
            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.agentInterface(SimpleAgentInterface.class);

            assertThrows(NullPointerException.class, builder::build);
        }

        @Test
        @DisplayName("Builder should throw exception when interface is null")
        void builderFailsWithoutInterface() {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey("test-key")
                    .build();

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.model(model);

            assertThrows(NullPointerException.class, builder::build);
        }

        @Test
        @DisplayName("Builder should accept tools")
        void builderWithTools() {
            Object tool = new Object();

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.tools(tool);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should accept multiple tools")
        void builderWithMultipleTools() {
            Object tool1 = new Object();
            Object tool2 = new Object();

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.tools(tool1, tool2);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should accept documents")
        void builderWithDocuments() {
            ArrayList<Document> docs = new ArrayList<>();
            docs.add(Document.from("test content"));

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.documents(docs);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should accept MCP tools")
        void builderWithMcpTools() {
            McpTransport transport = new StreamableHttpMcpTransport.Builder()
                    .url("http://localhost:3001/mcp")
                    .build();
            transport.start(new NoOpHandler(transport));

            McpClient client = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .build();

            McpToolProvider provider = McpToolProvider.builder()
                    .mcpClients(List.of(client))
                    .build();

            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.mcpToolProvider(provider);

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should accept SSE URL")
        void builderWithSseUrl() {
            AsyncAgent.Builder<SimpleAgentInterface> builder = new AsyncAgent.Builder<>();
            builder.sseUrl("http://localhost:3000/sse");

            assertNotNull(builder);
        }

        @Test
        @DisplayName("Builder should create Agent with required parameters")
        void builderCreatesAgent() {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl("http://langchain4j.dev/demo/openai/v1")
                    .apiKey("demo")
                    .modelName("gpt-4o-mini")
                    .build();

            ArrayList<Document> docs = new ArrayList<>();
            docs.add(Document.from("test manual"));

            AsyncAgent<SimpleAgentInterface> agent = new AsyncAgent.Builder<SimpleAgentInterface>()
                    .model(model)
                    .agentInterface(SimpleAgentInterface.class)
                    .tools(new Object())
                    .documents(docs)
                    .build();

            assertNotNull(agent);
        }

        @Test
        @DisplayName("Builder should create Agent with MCP and SSE parameters using Real Objects")
        void builderCreatesAgentWithMcp() {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl("http://langchain4j.dev/demo/openai/v1")
                    .apiKey("demo")
                    .modelName("gpt-4o-mini")
                    .build();

            McpTransport transport = new StreamableHttpMcpTransport.Builder()
                    .url("http://localhost:3001/mcp")
                    .build();
            transport.start(new NoOpHandler(transport));

            McpClient client = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .build();

            McpToolProvider provider = McpToolProvider.builder()
                    .mcpClients(List.of(client))
                    .build();

            AsyncAgent<SimpleAgentInterface> agent = new AsyncAgent.Builder<SimpleAgentInterface>()
                    .model(model)
                    .agentInterface(SimpleAgentInterface.class)
                    .mcpToolProvider(provider)
                    .sseUrl("http://localhost:3001/sse")
                    .build();

            assertNotNull(agent);
            assertNotNull(agent.brain());
            assertNotNull(agent.actionBrain());
        }
    }


    @Nested
    @DisplayName("Agent Functionality Tests")
    class AgentFunctionalityTests {
        OpenAiChatModel model = OpenAiChatModel.builder().baseUrl("http://langchain4j.dev/demo/openai/v1").apiKey("demo").modelName("gpt-4o-mini").build();
//        OllamaChatModel model = OllamaChatModel.builder()
//                .baseUrl("http://localhost:11434")
//                .modelName("qwen2.5")
//                .temperature(0.0)
//                .timeout(java.time.Duration.ofMinutes(2))
//                .build();


        public interface McpTestAgent extends ReactBrain {
            @UserMessage("[ACTING phase] You are a helpful assistant with access to external tools via MCP that you need to call using the {{input}}. Use them when requested."
            + " Always include the provided UUID '{{UUID}}' in your tool calls to correlate actions.")
            String chat(@V("input") String input,@V("UUID") String UUID);
        }

        @Nested
        @DisplayName("MCP Integration Tests")
        class McpIntegrationTests {
            @Test
            @DisplayName("Should actually call MCP Tool via Agent (Integration)")
            void shouldExecuteRealMcpTool() {
                try {
                    new java.net.Socket("localhost", 3001).close();
                } catch (Exception e) {
                    System.out.println("⚠️ Node Server not running on 3001. Skipping integration test.");
                    return;
                }

                System.out.println("🚀 Node Server detected. Starting MCP Tool Call Test...");

                McpTransport transport = new StreamableHttpMcpTransport.Builder()
                        .url("http://localhost:3001/mcp")
                        .logRequests(true)
                        .logResponses(true)
                        .build();
                transport.start(new NoOpHandler(transport));

                McpClient client = new DefaultMcpClient.Builder()
                        .transport(transport)
                        .toolExecutionTimeout(java.time.Duration.ofSeconds(10))
                        .build();

                McpToolProvider provider = McpToolProvider.builder()
                        .mcpClients(List.of(client))
                        .build();


                AsyncAgent<McpTestAgent> agent = new AsyncAgent.Builder<McpTestAgent>()
                        .model(model)
                        .agentInterface(McpTestAgent.class)
                        .mcpToolProvider(provider)
                        .sseUrl("http://localhost:3001/sse")
                        .build();

                System.out.println("🤖 Asking agent to use the tool...");

                String response = agent.actionBrain().chat("Subscribe to the notification system, please.", uuid);

                System.out.println("🤖 Agent Response: " + response);
                assertNotNull(response);
                assertFalse(response.isEmpty());
            }
        }

        @Test
        @DisplayName("Should set timer via MCP and store variable (Integration)")
        void shouldSetTimerAndReceiveVariableAndEvent() throws Exception {
            // Check Server
            try { new java.net.Socket("localhost", 3001).close(); } catch (Exception e) { return; }

            // Setup
            McpTransport transport = new StreamableHttpMcpTransport.Builder().url("http://localhost:3001/mcp").logRequests(true).logResponses(true).build();
            transport.start(new NoOpHandler(transport));
            McpClient client = new DefaultMcpClient.Builder().transport(transport).toolExecutionTimeout(java.time.Duration.ofSeconds(10)).build();
            McpToolProvider provider = McpToolProvider.builder().mcpClients(List.of(client)).build();

            AsyncAgent<McpTestAgent> agent = new AsyncAgent.Builder<McpTestAgent>()
                    .model(model)
                    .agentInterface(McpTestAgent.class)
                    .mcpToolProvider(provider)
                    .sseUrl("http://localhost:3001/sse")
                    .build();


            String request = "Find a way to set a timer that triggers an event after 1 second name test-timer";
            agent.request(request);

            // To verify the test, we need to peek into the registry
            Field registryField = AsyncAgent.class.getDeclaredField("activityRegistry");
            registryField.setAccessible(true);
            Map<String, Activity> registry = (Map<String, Activity>) registryField.get(agent);

            // Wait for the activity to appear in the registry (immediate)
            assertFalse(registry.isEmpty(), "Registry should contain the activity");
            Activity activity = registry.values().iterator().next();
            String uuid = activity.getUuid();

            System.out.println("🤖 Activity started with UUID: " + uuid);

            // Wait for the variable to arrive via SSE
            String expectedKey = "test-timer";
            boolean gotVar = false;
            long deadline = System.currentTimeMillis() + 120000;

            while (System.currentTimeMillis() < deadline) {
                // Direct check in the Activity
                if (activity.getBelief(expectedKey) != null) {
                    gotVar = true;
                    break;
                }
                Thread.sleep(500);
            }

            assertTrue(gotVar, "Variable should be injected into Activity via SSE");

            // Verify value
            JsonNode val = activity.getBelief(expectedKey);
            assertTrue(val.has("seconds") || val.has("name"));

            System.out.println("✅ SSE Integration Test Passed with Activity Registry");
        }


        @Test
        @DisplayName("Agent brain should work and correctly call the model")
        void agentBrainFunctionality() {
            AsyncAgent<SimpleAgentInterface> agent = createTestAgent();

            String response = agent.brain().test("Hello, Agent!");
            System.out.println(response);
            assertNotNull(response);
        }

        @Test
        @DisplayName("Agent should accept request")
        void agentAcceptsRequest() {
            AsyncAgent<SimpleAgentInterface> agent = createTestAgent();

            assertDoesNotThrow(() -> agent.request("test request"));
        }

        private AsyncAgent<SimpleAgentInterface> createTestAgent() {

            ArrayList<Document> docs = new ArrayList<>();

            return new AsyncAgent.Builder<SimpleAgentInterface>()
                    .model(model)
                    .agentInterface(SimpleAgentInterface.class)
                    .documents(docs)
                    .build();
        }
    }
}
