import express from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';

const app = express();
app.use(express.json());

// -------------------------
// ARTIFACT STATE
// -------------------------
const sseClients = new Set();
let sharedCounter = 1;
const focusedAgents = new Set();

const server = new McpServer({
    name: 'shared-counter-artifact',
    version: '1.0.0'
});

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
                    name: "environment.change",
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
# ARTIFACT SPECIFICATION: SharedCounter

**Artifact ID:** \`counterTool\`
**Category:** Coordination Artifact / Shared Memory
**Version:** 1.0.0

---

## 1. Observable Properties
The artifact exposes the following state variables directly into the agent's working memory context upon successful focus.

| Property Name | Type | Description |
| :--- | :--- | :--- |
| \`shared_counter\` | \`Integer\` | The global count value shared across all agents. Updated automatically via telemetry. |

---

## 2. Usage Interface (Operations)
Agents interact with the artifact via the \`counterTool\`. The behavior depends on the \`action\` parameter.

### Operation: \`focus\`
Establishes a cognitive link with the artifact, enabling perception of observable properties and events.

* **Signature:** \`counterTool(action: "focus", uuid: String)\`
* **Pre-conditions:** None.
* **Post-conditions:**
    * Agent is subscribed to updates.
    * \`shared_counter\` becomes visible in context.
    * Receives confirmation event \`focus.established\`.

### Operation: \`inc\`
Sends an impulse to increment the shared state.

* **Signature:** \`counterTool(action: "inc", uuid: String)\`
* **Pre-conditions:** Agent must have previously executed \`focus\`.
* **Effects:**
    * Increments \`shared_counter\` by 1.
    * Triggers broadcast update to all focused agents.

---

## 3. Operating Instructions (Protocol)
To ensure data consistency, agents **MUST** follow this perception-action loop:

1.  **Initialization:** Call \`focus\` immediately upon discovery.
2.  **Action:** When calling \`inc\`, the tool output is merely an acknowledgement ("Impulse sent"). **Do not assume the state has changed yet.**
3.  **Perception:** Wait for the \`environment.change\` event.
4.  **Verification:** Check the \`shared_counter\` variable in your context. It is guaranteed to be updated *before* the event arrives.

> **Note on Concurrency:** This is a shared environment. The counter may change due to other agents' actions. Always consult the \`shared_counter\` variable before decision making.
`;

// -------------------------
// TOOLS
// -------------------------

server.tool('manual_retrieval', { tool_name: z.string().optional() }, async ({ tool_name }) => {
    console.log('[MANUAL] Manual requested for:', tool_name || "general");
    return { content: [{ type: 'text', text: MANUAL_CONTENT }] };
});

server.tool('counterTool',
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

                return { content: [{ type: 'text', text: "✅ Focus established. Synced." }] };
            }
            return { content: [{ type: 'text', text: "You are already focused on this artifact." }] };
        }

        // --- ACTION: INCREMENT ---
        if (action === "inc") {
            if (!focusedAgents.has(uuid)) {
                return { isError: true, content: [{ type: 'text', text: "⛔ Error: You must FOCUS first." }] };
            }

            log(`[TOOL] Increment request from ${uuid}. Current: ${sharedCounter} -> New: ${sharedCounter + 1}`);

            sharedCounter++;

            // Broadcast (Variable First -> Event Second)
            broadcastUpdate(uuid, "increment");

            return { content: [{ type: 'text', text: "⚡ Impulse sent. Waiting for event..." }] };
        }
    }
);

// -------------------------
// SERVER
// -------------------------
app.post('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
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