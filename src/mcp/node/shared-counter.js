import express from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';

const app = express();
app.use(express.json());

// -------------------------
// TOOL STATE
// -------------------------
const sseClients = new Set();
let sharedCounter = 1;
const focusedAgents = new Set();

function log(...args) { console.log(new Date().toISOString(), ...args); }

// -------------------------
// NOTIFICATION SYSTEM
// -------------------------

function sendSse(payload) {

    for (const client of sseClients) {
        try {
            client.write(`data: ${payload}\n\n`);
        } catch (e) {
            console.error("SSE Write Error, removing client", e);
            sseClients.delete(client);
        }
    }
}

function broadcastUpdate(triggerUuid, actionDescription) {
    log(`[BROADCAST] Triggered by ${triggerUuid} -> ${actionDescription}`);

    focusedAgents.forEach(targetUuid => {

        const varPayload = JSON.stringify({
            jsonrpc: "2.0",
            method: "notifications/message",
            params: {
                uuid: targetUuid,
                mcpType: "variable",
                name: "shared_counter",
                value: sharedCounter
            }
        });
        sendSse(varPayload);


        const eventPayload = JSON.stringify({
            jsonrpc: "2.0",
            method: "notifications/message",
            params: {
                uuid: targetUuid,
                mcpType: "event",
                event: {
                    key: "counter_update",
                    name: "counter.change",
                    message: `Counter changed.`,
                }
            }
        });
        sendSse(eventPayload);
    });
}

// -------------------------
// MANUAL CONTENT
// -------------------------
const MANUAL_CONTENT = `
# TOOL SPECIFICATION: SharedCounter

**Category:** Coordination Tool / Shared Memory
**Version:** 1.0.0
**Tool ID:** \`counterTool\`

## 1. Functional Description
This tool provides a synchronized shared memory space for multi-agent coordination. It acts as a **passive enabler**, allowing multiple agents to concurrently increment and observe a global counter to synchronize their collective actions.

---

## 2. Usage Interface

### 2.1 Observable Properties
Exposed via the global telemetry stream.

| Property | Type | Range | Description |
| :--- | :--- | :--- | :--- |
| \`shared_counter\` | Integer | \`1\` - \`∞\` | The global count value shared across all agents. Updated automatically via telemetry. |

### 2.2 Operations

* **Operation:** \`focus\`
  * *Description:* Establishes a cognitive link with the tool to enable perception of its state and events.
  * *Behavior:* **Process Initiator.** Registration is processed immediately. Completion of the stream binding is indicated by the \`focus.established\` signal.
  * *Preconditions:* None.
  * *Effects:* Subscribes the agent to the telemetry stream. The \`shared_counter\` variable becomes visible in the agent's working memory context.
  * *Payload:*
    \`\`\`json
    { "action": "focus", "uuid": "<ACTIVITY_UUID>" }
    \`\`\`

* **Operation:** \`inc\`
  * *Description:* Sends an impulse to increment the shared global state.
  * *Behavior:* **Latent.** The tool returns an acknowledgement for the request. State changes are propagated asynchronously via telemetry.
  * *Preconditions:* Agents are recommended to call \`focus\` to subscribe to telemetry before interacting. Calling \`inc\` without focus will result in an operational error.
  * *Effects:* Increments \`shared_counter\` by 1 and triggers a broadcast telemetry update to all focused agents.
  * *Payload:*
    \`\`\`json
    { "action": "inc", "uuid": "<ACTIVITY_UUID>" }
    \`\`\`

### 2.3 Signals
The tool emits the following asynchronous signals to notify agents of state transitions:

* **Signal:** \`focus.established\`
  * *Trigger:* Emitted automatically when the agent successfully registers for the telemetry stream.
  * *Payload:* \`{ "key": String, "name": String, "message": String }\`

* **Signal:** \`environment.change\`
  * *Trigger:* Emitted automatically whenever ANY agent increments the counter, alerting all subscribers of a state mutation.
  * *Payload:* \`{ "key": String, "name": String, "message": String }\`

---

## 3. Protocol & SAFETY

**ASYNCHRONOUS STATE MUTATION (Informational)**

1. **Recommended Prerequisite:**
   * Agents are recommended to call \`focus\` to subscribe to telemetry before interacting with the counter. This ensures they will receive updates about \`shared_counter\`.

2. **Execution Notes (Perception-Action Loop):**
   * Calling \`inc\` issues a request to increment the counter and returns an acknowledgement.
   * State changes are distributed asynchronously. Agents may choose to:
     - listen for the \`environment.change\` signal to confirm a mutation, or
     - observe the \`shared_counter\` telemetry for the new value.

3. **Concurrency Note:**
   * This is a shared environment. The \`shared_counter\` may change due to other agents' actions. Prefer authoritative telemetry updates over local assumptions about the counter value.
`;

// -------------------------
// TOOLS
// -------------------------

function createServer() {
const server = new McpServer({
    name: 'shared-counter-tool',
    version: '1.0.0'
});

server.tool(
    'manual_retrieval',
    "Retrieves the full content of a specific technical manual from the catalog.",
    { tool_name: z.string().optional() },
    async ({ tool_name }) => {
        console.log('[MANUAL] Manual requested for:', tool_name || "general");
        return { content: [{ type: 'text', text: MANUAL_CONTENT }] };
    }
);

server.tool(
    'counterTool',
    "Provides a shared global counter for multi-agent coordination. Agents can subscribe for updates or request increments.",
    {
        action: z.enum(["focus", "inc"]),
        uuid: z.string().uuid().describe("Your Agent UUID")
    },
    async ({ action, uuid }) => {

        // --- ACTION: FOCUS ---
        if (action === "focus") {
            const isNew = !focusedAgents.has(uuid);
            focusedAgents.add(uuid);
            log(`[TOOL] Focus request from ${uuid}. Is New? ${isNew}`);

            if (isNew) {

                const varPayload = JSON.stringify({
                    jsonrpc: "2.0",
                    method: "notifications/message",
                    params: {
                        uuid: uuid,
                        mcpType: "variable",
                        name: "shared_counter",
                        value: sharedCounter
                    }
                });
                sendSse(varPayload);

                const eventPayload = JSON.stringify({
                    jsonrpc: "2.0",
                    method: "notifications/message",
                    params: {
                        uuid: uuid,
                        mcpType: "event",
                        event: {
                            key: "focus_init",
                            name: "focus.established",
                            message: `Focus successful. Connected to shared counter.`
                        }
                    }
                });
                sendSse(eventPayload);

                return { content: [{ type: 'text', text: " Focus established. Synced." }] };
            }
            return { content: [{ type: 'text', text: "You are already focused on this artifact." }] };
        }

        // --- ACTION: INCREMENT ---
        if (action === "inc") {
            if (!focusedAgents.has(uuid)) {
                return { isError: true, content: [{ type: 'text', text: " Error: You must FOCUS first." }] };
            }

            log(`[TOOL] Increment request from ${uuid}. Current: ${sharedCounter} -> New: ${sharedCounter + 1}`);

            sharedCounter++;

            // Broadcast (Variable First -> Event Second)
            broadcastUpdate(uuid, "increment");

            return { content: [{ type: 'text', text: "⚡ Impulse sent. Waiting for event..." }] };
        }
    }
);

    return server;
}

// -------------------------
// SERVER
// -------------------------
app.post('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await createServer().connect(transport);
    await transport.handleRequest(req, res, req.body);
});

app.get('/sse', (req, res) => {
    log("SSE Client Connected");
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    sseClients.add(res);

    res.write(": connected\n\n");

    req.on('close', () => {
        log("SSE Client Disconnected");
        sseClients.delete(res);
    });
});

app.listen(3001, () => {
    log(`🚀 Shared Counter Artifact running on port 3001`);
});