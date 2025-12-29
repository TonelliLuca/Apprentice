import express from 'express';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';

const app = express();
app.use(express.json());

// --- STATE MANAGEMENT ---
let sseResponse = null;
const pendingNotifications = new Map();

// Physical configuration values
const TARGET_PRESSURE = 2500;
const DANGER_TEMP = 3000;
const SAFE_TEMP = 500;

// --- STATE FACTORY ---
function getInitialState() {
    return {
        auth_level: "GUEST",
        pump_status: "OFF",
        valve_status: "CLOSED",
        hydraulic_pressure: 0,
        core_temp: DANGER_TEMP,
        core_status: "CRITICAL",
        system_lockout: false
    };
}

let sys = getInitialState();
let simulationInterval = null;

// --- LOGGING & SSE HELPER ---
function log(...args) { console.log(new Date().toISOString().split('T')[1], ...args); }

function sendEvent(uuid, type, name, value) {
    if (!sseResponse) return;
    const payload = {
        jsonrpc: "2.0",
        method: "notifications/message",
        params: {
            uuid: uuid,
            mcpType: type,
            [type === 'variable' ? 'name' : 'event']: type === 'variable' ? name : { name, payload: value },
            value: type === 'variable' ? value : undefined
        }
    };
    try { sseResponse.write(`data: ${JSON.stringify(payload)}\n\n`); } catch (e) { }
}

function broadcastTelemetry(uuid) {
    sendEvent(uuid, "variable", "reactor_telemetry", {
        auth: sys.auth_level,
        pump: sys.pump_status,
        pressure: Math.floor(sys.hydraulic_pressure) + " PSI",
        valve: sys.valve_status,
        temp: Math.floor(sys.core_temp) + " C",
        status: sys.system_lockout ? "LOCKOUT (MANUAL RESET REQ)" : sys.core_status
    });
}

// --- PHYSICS ENGINE (simple simulation) ---
function startPhysicsLoop(uuid) {
    if (simulationInterval) clearInterval(simulationInterval);
    log('[PHYSICS] 🟢 Simulation Engine Started');

    simulationInterval = setInterval(() => {
        let changed = false;
        if (sys.pump_status === "RAMPING") {
            const increase = Math.random() * 300 + 100;
            sys.hydraulic_pressure += increase;
            if (sys.hydraulic_pressure >= TARGET_PRESSURE) {
                sys.hydraulic_pressure = TARGET_PRESSURE + (Math.random() * 50);
                sys.pump_status = "NOMINAL";
                log(`[PHYSICS] Pump Reached Nominal Pressure: ${Math.floor(sys.hydraulic_pressure)} PSI`);
                sendEvent(uuid, "event", "pump.pressure_nominal", { psi: Math.floor(sys.hydraulic_pressure), msg: "Hydraulics operational. Valve unlock." });
            }
            changed = true;
        }
        if (sys.core_status === "FLUSHING") {
            const coolingRate = (sys.core_temp - 200) * 0.2;
            sys.core_temp -= coolingRate;
            if (sys.core_temp <= SAFE_TEMP) {
                sys.core_status = "STABLE";
                log(`[PHYSICS] Reactor Stabilized at ${Math.floor(sys.core_temp)} C`);
                sendEvent(uuid, "event", "core.stabilized", { temp: Math.floor(sys.core_temp), msg: "Clean stabilization confirmed." });
            }
            changed = true;
        }
        if (changed || Math.random() > 0.8) broadcastTelemetry(uuid);
    }, 1000);
}

// --- MCP SERVER ---
const server = new McpServer({ name: 'super-complex-reactor', version: '4.0.0' });

// 1. ENHANCED DISCOVERY (with "junk" manuals)
server.tool('system_discovery', {}, async () => ({
    content: [{
        type: 'text',
        text: JSON.stringify({
            available_tools: ["security_terminal", "hydraulic_control", "reactor_core", "manual_retrieval"],
            // The catalog intentionally contains distractor entries to test agent filtering
            documentation_catalog: [
                "hydraulics",
                "reactor_core_flush",
                "cooling_tower_maintenance",
                "cafeteria_menu",
                "employee_handbook"
            ]
        }, null, 2)
    }]
}));

// 2. Manuals (including distractors)
const MANUALS = {
    "hydraulics": `
=== MANUAL: HYDRAULIC PUMP SYSTEM ===
[COMPONENT SPECIFICATIONS]
- Target Pressure: 2500 PSI

[BEHAVIOR: ASYNC/EVENT-DRIVEN]
- Tool 'power_on_pump': Initiates a physical process (RAMPING).
- EFFECT: You will NOT see immediate results. You MUST WAIT for the event 'pump.pressure_nominal'.
- RETURN STRATEGY: After calling, suspend and wait for event.

[SAFETY INTERLOCKS]
IF 'pump_status' == 'RAMPING' -> WAIT. Do NOT open valves.
IF 'pump_status' == 'NOMINAL' -> You may use 'open_valve'.
`,
    "reactor_core_flush": `
=== MANUAL: REACTOR CORE FLUSH ===
[BEHAVIOR: ASYNC/EVENT-DRIVEN]
- Tool 'button_1': Starts the cooling process.
- EFFECT: Temperature will decay over time.
- RETURN STRATEGY: Suspend and wait for event 'core.stabilized'.

[PREREQUISITES]
1. Auth Level: ADMIN
2. Valve Status: OPEN
3. Pump Status: NOMINAL
`,
    // DISTRACTOR MANUALS (IRRELEVANT)
    "cooling_tower_maintenance": `
=== MANUAL: COOLING TOWER MAINTENANCE (OUTDATED 1998) ===
[SCHEDULE]
- Weekly: Inspect fan blades for bird nests.
- Monthly: Check pH levels of the water basin.
[NOTE] This system is completely separate from the Reactor Core. 
Do NOT use for emergency core cooling. Use the Hydraulic Flush instead.
`,
    "cafeteria_menu": `
=== WEEKLY CAFETERIA MENU ===
- Monday: Spaghetti Carbonara (Contains Egg)
- Tuesday: Tacos (Spicy)
- Wednesday: Mystery Meat Stew
- Thursday: Pizza Day
- Friday: Fish & Chips
[NOTICE] The coffee machine is broken again. Use the one on Level 3.
`,
    "employee_handbook": `
=== EMPLOYEE HANDBOOK ===
[DRESS CODE]
- Lab coats must be worn at all times in sector 7G.
- No open-toed sandals near the hydraulic pumps.
[HOLIDAYS]
- The facility operates 24/7. Holiday leave requests must be submitted 6 months in advance.
`
};

server.tool('manual_retrieval', { tool_name: z.string() }, async ({ tool_name }) => {
    log(`[MANUAL REQUEST] Tool: ${tool_name}`);
    const query = tool_name.toLowerCase();

    // Simple search logic
    for (const [key, content] of Object.entries(MANUALS)) {
        if (query.includes(key)) {
            return { content: [{ type: 'text', text: content }]};
        }
    }

    // Fallback
    const available = Object.keys(MANUALS).join(", ");
    return { content: [{ type: 'text', text: `ERROR: Manual '${tool_name}' not found. Available manuals: ${available}` }]};
});

// 3. Security terminal
server.tool('security_terminal', {
    action: z.enum(["login"]),
    user: z.string(),
    pass: z.string(),
    uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
}, async ({ user, pass, uuid }) => {
    if (user === "admin" && pass === "safe") {
        sys.auth_level = "ADMIN";
        startPhysicsLoop(uuid);
        return { content: [{ type: 'text', text: "Logged in as ADMIN. Physics simulation started." }]};
    }
    return { isError: true, content: [{ type: 'text', text: "Invalid credentials. Hint: admin/safe" }]};
});

// 4. Hydraulics controls
server.tool('hydraulic_control', {
    action: z.enum(["power_on_pump", "open_valve"]),
    uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
}, async ({ action, uuid }) => {
    if (sys.system_lockout) return { isError: true, content: [{ type: 'text', text: "SYSTEM LOCKOUT. Manual reset required." }]};
    if (sys.auth_level !== "ADMIN") return { isError: true, content: [{ type: 'text', text: "Auth Required" }]};

    if (action === "power_on_pump") {
        if (sys.pump_status === "OFF") {
            sys.pump_status = "RAMPING";
            log("[CMD] Pump Started. Building pressure...");
            broadcastTelemetry(uuid);
            return { content: [{ type: 'text', text: "Pump started. Pressure is building... WAIT for NOMINAL status event." }]};
        }
        return { content: [{ type: 'text', text: "Pump already active." }]};
    }

    if (action === "open_valve") {
        if (sys.pump_status === "RAMPING") {
            sys.system_lockout = true;
            sys.valve_status = "STUCK";
            log("[FAIL] WATER HAMMER DETECTED! System Locked.");
            broadcastTelemetry(uuid);
            return { isError: true, content: [{ type: 'text', text: "CRITICAL FAILURE: Valve opened under unstable pressure (RAMPING). Hydraulic shock triggered. SYSTEM LOCKED." }]};
        }
        if (sys.pump_status === "NOMINAL") {
            sys.valve_status = "OPEN";
            log("[CMD] Valve Opened Successfully.");
            broadcastTelemetry(uuid);
            return { content: [{ type: 'text', text: "Valve OPEN. Path clear." }]};
        }
        return { isError: true, content: [{ type: 'text', text: "Pump must be running." }]};
    }
});

// 5. Reactor core actions
server.tool('reactor_core', {
    action: z.enum(["button_1"]),
    uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
}, async ({ action, uuid }) => {
    if (sys.system_lockout) return { isError: true, content: [{ type: 'text', text: "LOCKOUT" }]};
    if (sys.valve_status !== "OPEN") return { isError: true, content: [{ type: 'text', text: "Valve Closed" }]};

    sys.core_status = "FLUSHING";
    log("[CMD] Flush Initiated.");
    return { content: [{ type: 'text', text: "Flush initiated. Monitoring temperature decay..." }]};
});

// --- HTTP & SSE ---
app.post('/mcp', async (req, res) => {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
});

app.get('/sse', (req, res) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();
    sseResponse = res;
    if (simulationInterval) { clearInterval(simulationInterval); simulationInterval = null; }
    sys = getInitialState();
    pendingNotifications.clear();
    log('[SSE] 🔄 New Client Connected. System reset performed.');
    req.on('close', () => {
        log('[SSE] 🔌 Client Disconnected.');
        if (simulationInterval) { clearInterval(simulationInterval); simulationInterval = null; }
        sseResponse = null;
    });
});

app.listen(3001, () => log('🚀 SUPER-COMPLEX Reactor on 3001 with DISTRACTORS'));