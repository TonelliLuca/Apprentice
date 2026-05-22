import express from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import { randomUUID } from 'crypto';

const app = express();
app.use(express.json());

let sseResponse = null;
// track subscriptions per UUID (RFC4122 string)
const subscribedUuids = new Set();
// queue notifications per-UUID while SSE is not connected
const pendingNotifications = new Map();
// timers map: id -> { name, timeoutId, seconds }
const timers = new Map();

function now() {
    return new Date().toISOString();
}

function log(...args) {
    console.log(now(), ...args);
}

// helper to enqueue a notification string for a uuid
function enqueueNotification(uuid, payload) {
    if (!pendingNotifications.has(uuid)) pendingNotifications.set(uuid, []);
    pendingNotifications.get(uuid).push(payload);
}

// flush pending notifications for all subscribed UUIDs (called when SSE connects)
function flushPendingNotifications() {
    if (!sseResponse) return;
    for (const [uuid, list] of pendingNotifications.entries()) {
        if (!subscribedUuids.has(uuid)) continue; // only deliver for explicitly subscribed UUIDs
        for (const payload of list) {
            log('[SSE][FLUSH] Sending queued payload for UUID:', uuid);
            try {
                sseResponse.write(`data: ${payload}\n\n`);
            } catch (e) {
                log('[SSE][FLUSH] Failed to write payload for UUID', uuid, e);
            }
        }
        pendingNotifications.delete(uuid);
    }
}

// -------------------------
// DOCUMENTATION (THE ARTIFACT MANUAL)
// -------------------------
const TIMER_MANUAL = `
# ARTIFACT SPECIFICATION: Temporal Timer System

**Artifact ID:** \`timer_artifact\`
**Category:** Chronometry / Process Control
**Version:** 2.0.0

---

## 1. Observable Properties
The artifact exposes the following state variables via the \`sse_variable\` channel.

| Property Name | Type | Value Range | Description |
| :--- | :--- | :--- | :--- |
| \`timer_status\` | \`String\` | \`RUNNING\`, \`COMPLETED\` | The lifecycle state of the requested timer. |

---

## 2. Usage Interface (Operations)
Agents interact with this artifact via the \`timer_tool\`.

### Operation: \`start\`
Initiates a countdown and **automatically subscribes** the agent to artifact events.

* **Signature:** \`timer_tool(action: "start", seconds: <Integer>, uuid: <String>)\`
* **Effect:**
    1. Subscribes the \`uuid\` to the SSE channel.
    2. Sets \`timer_status\` to \`RUNNING\`.
    3. Starts the internal countdown.
* **Asynchronous Behavior:** This action returns immediately. The process is **NON-BLOCKING**.

### Operation: \`check\`
Verifies artifact connectivity and subscription status without starting a timer.

* **Signature:** \`timer_tool(action: "check", uuid: <String>)\`
* **Effect:** Ensures the \`uuid\` is subscribed to SSE updates.

---

## 3. Operating Instructions (Protocol)
**PROTOCOL: ASYNCHRONOUS WAIT**

1.  **Action:** Call \`start\` with the desired duration (e.g., 5 seconds).
2.  **Wait Phase:** Immediately enter a \`WAITING_FOR_EVENT\` state.
    * **DO NOT** poll via \`check\`.
    * **DO NOT** expect a countdown (e.g., "4s left", "3s left"). The artifact is silent while running.
3.  **Completion:** Wait specifically for the SSE event \`timer_done\`.
    * Payload example: \`{"message": "Timer finished", "duration": 5}\`.
`;

// -------------------------
// TOOLS
// -------------------------

function createServer() {
const server = new McpServer({
    name: 'timer-artifact',
    version: '2.0.0'
});

// 1. MANUAL RETRIEVAL (Standard discovery tool)
server.tool(
    'manual_retrieval',
    {
        tool_name: z.string().optional()
    },
    async ({ tool_name }) => {
        log('[MANUAL] Manual requested for:', tool_name || "general");

        if (tool_name && tool_name.toLowerCase().includes("timer")) {
            return {
                content: [{ type: 'text', text: TIMER_MANUAL }]
            };
        }

        return {
            content: [{ type: 'text', text: "ERROR: Manual not found for tool '" + tool_name + "'. Available manuals: timerTool" }]
        };
    }
);

// 2. TIMER TOOL (The functional artifact)
server.tool(
    'timerTool',
    {
        action: z.enum(["subscribe", "set"]),
        seconds: z.number().optional(),
        name: z.string().optional(),
        uuid: z.string().uuid()
    },
    async ({ action, seconds, name, uuid }) => {

        log('[TOOL] Called timerTool', { action, seconds, name, uuid, sseConnected: !!sseResponse, subscribedForUuid: subscribedUuids.has(uuid) });

        // -------------------------
        // SUBSCRIBE (FOCUS ACTION)
        // -------------------------
        if (action === "subscribe") {
            subscribedUuids.add(uuid);
            log('[TOOL][SUBSCRIBE] UUID subscribed (Focus established):', uuid);

            const eventPayloadObj = {
                jsonrpc: "2.0",
                method: "notifications/message",
                params: {
                    uuid,
                    mcpType: "event",
                    event: {
                        key: "subscription-ack",
                        name: "subscription.started",
                        message: "Focus established. You will now receive signals via SSE."
                    }
                }
            };
            const eventPayload = JSON.stringify(eventPayloadObj);

            if (sseResponse) {
                try {
                    sseResponse.write(`data: ${eventPayload}\n\n`);
                } catch (e) {
                    enqueueNotification(uuid, eventPayload);
                }
            } else {
                enqueueNotification(uuid, eventPayload);
            }

            return {
                uuid,
                content: [{ type: 'text', text: "✅ FOCUS ESTABLISHED. Subscription active. Ready to set timers." }]
            };
        }

        // -------------------------
        // SET TIMER
        // -------------------------
        if (action === "set") {
            if (!seconds) {
                return { uuid, content: [{ type: 'text', text: "❌ Error: missing seconds." }] };
            }

            // Check Focus (Optional logic: enforce subscription)
            if (!subscribedUuids.has(uuid)) {
                log('[WARN] Request from unsubscribed UUID:', uuid);
                return { uuid, content: [{ type: 'text', text: "❌ Error: You must establish FOCUS first by subscribing before setting a timer." }] };
                // We allow it but warn in the log, or we could return an error telling them to subscribe first.
                // For now, we proceed but they might miss the event if SSE isn't connected.
            }

            const id = name ? String(name) : `timer-${randomUUID()}`;
            log('[TOOL][SET] Timer set', { id, seconds, uuid });

            const timeoutId = setTimeout(() => {
                log('[NODE] Timer expired', { id, seconds, uuid });

                const eventDataObj = {
                    jsonrpc: "2.0",
                    method: "notifications/message",
                    params: {
                        uuid,
                        mcpType: "event",
                        event: {
                            key: id,
                            name: "timer.finished",
                            message: `⏰ RING! Timer ${id} (${seconds}s) expired!`
                        }
                    }
                };
                const eventData = JSON.stringify(eventDataObj);

                // Delivery Logic
                if (sseResponse && subscribedUuids.has(uuid)) {
                    try {
                        sseResponse.write(`data: ${eventData}\n\n`);
                    } catch (e) {
                        enqueueNotification(uuid, eventData);
                    }
                } else if (subscribedUuids.has(uuid)) {
                    enqueueNotification(uuid, eventData);
                }

                timers.delete(id);
            }, seconds * 1000);

            timers.set(id, { name: name || id, timeoutId, seconds });

            // Variable Update Notification
            const varPayloadObj = {
                jsonrpc: "2.0",
                method: "notifications/message",
                params: {
                    uuid,
                    mcpType: "variable",
                    name: id,
                    value: { name: name || id, seconds, status: "RUNNING" }
                }
            };
            const varPayload = JSON.stringify(varPayloadObj);

            if (sseResponse && subscribedUuids.has(uuid)) {
                try { sseResponse.write(`data: ${varPayload}\n\n`); } catch (e) { enqueueNotification(uuid, varPayload); }
            } else if (subscribedUuids.has(uuid)) {
                enqueueNotification(uuid, varPayload);
            }

            return {
                uuid,
                content: [
                    { type: 'text', text: `⏳ Timer ${id} started (${seconds}s). Waiting for event...` }
                ]
            };
        }

        return { uuid, content: [{ type: 'text', text: "Unknown action" }] };
    }
);

    return server;
}

// -------------------------
// MCP ENDPOINT
// -------------------------
app.post('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
        enableJsonResponse: true
    });
    await createServer().connect(transport);
    await transport.handleRequest(req, res, req.body);
});

// -------------------------
// SSE ENDPOINT
// -------------------------
app.get('/sse', (req, res) => {
    log("[NODE] New SSE client connected");
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    sseResponse = res;
    res.write(": connected\n\n");
    flushPendingNotifications();

    req.on('close', () => {
        log("[NODE] SSE client disconnected");
        sseResponse = null;
    });
});

const PORT = 3001;
app.listen(PORT, () => {
    log(`🚀 Timer Artifact running on port ${PORT}`);
    log(`👉 Manuals available via 'manual_retrieval'`);
});