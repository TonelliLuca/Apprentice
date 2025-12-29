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

const server = new McpServer({
    name: 'timer-artifact',
    version: '2.0.0'
});

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
const MANUAL_CONTENT = `
=== MANUAL: TIMER ARTIFACT ===
Type: Async/Event-Driven Tool
Tool Name: 'timerTool'

[PROTOCOL & BEHAVIOR]
1. **FOCUS REQUIRED (Subscribe):** - Before interacting, you MUST establish a connection context.
   - Action: Call 'timerTool' with parameter { "action": "subscribe" }.
   - Effect: Enables the reception of asynchronous events (SSE). Without this, you are "blind" to the results.

2. **OPERATION (Set):** is dependent on having FOCUS so refer to step 1 before proceeding.
   - TO USE THIS FUNCTION YOU MUST HAVE FOCUS ESTABLISHED.
   - Action: Call 'timerTool' with { "action": "set", "seconds": <number>, "name": <optional_string> }.
   - Behavior: The tool initiates a countdown in the background.
   - Immediate Return: Confirming the timer has started (NOT that it has finished).

3. **OBSERVATION (Events):**
   - You MUST WAIT for the asynchronous event to confirm completion.
   - Event Name: 'timer.finished'
   - Payload: Contains the message "⏰ RING! ...".

[SAFETY & ERRORS]
- Do not set 0 seconds.
- Ensure UUID is consistent across calls.
`;

// -------------------------
// TOOLS
// -------------------------

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
                content: [{ type: 'text', text: MANUAL_CONTENT }]
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

// -------------------------
// MCP ENDPOINT
// -------------------------
app.post('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
        enableJsonResponse: true
    });
    await server.connect(transport);
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