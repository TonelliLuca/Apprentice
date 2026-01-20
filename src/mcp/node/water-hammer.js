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

function safeStringify(obj) {
    try { return JSON.stringify(obj, null, 2); } catch (e) { return String(obj); }
}

// Lightweight request logger: do not log full payloads/values.
// For HTTP requests log only the RPC method name (no IP, headers, param keys or values).
function logHttpRequestSummary(req) {
    try {
        const rpcMethod = req.body && (req.body.method || (req.body.params && req.body.params.action))
            ? (req.body.method || req.body.params.action)
            : 'unknown';
        log(`[REQ] RPC ${rpcMethod}`);
    } catch (e) {
        log('[REQ] RPC (parse error)');
    }
}

// Tool invocation logger: only record which tool/action/user/uuid were invoked; redact sensitive fields like pass.
function logToolInvocation(toolName, params = {}) {
    const summary = {};
    if ('action' in params) summary.action = params.action;
    if ('tool_name' in params) summary.tool_name = params.tool_name;
    if ('user' in params) summary.user = params.user;
    if ('uuid' in params) summary.uuid_present = params.uuid ? true : false;
    // do not log param values (e.g., pass), keep it non-aggressive
    log(`[TOOL] ${toolName} called ->`, JSON.stringify(summary));
}

// --- TELEMETRY & EVENT SYSTEM ---
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
    try {
        log(`[SSE OUT] ${type} -> uuid:${uuid} name:${name} payload: ${safeStringify(value)}`);
        sseResponse.write(`data: ${JSON.stringify(payload)}\n\n`);
    } catch (e) {
        log('[SSE ERROR] Failed to send event', e);
    }
}

function broadcastTelemetry(uuid) {
    const telemetry = {
        auth: sys.auth_level,
        pump: sys.pump_status,
        pressure: Math.floor(sys.hydraulic_pressure) + " PSI",
        valve: sys.valve_status,
        temp: Math.floor(sys.core_temp) + " C",
        status: sys.system_lockout ? "LOCKOUT (MANUAL RESET REQ)" : sys.core_status
    };
    log(`[TELEMETRY] Broadcasting reactor_telemetry for uuid:${uuid} -> ${safeStringify(telemetry)}`);
    sendEvent(uuid, "variable", "reactor_telemetry", telemetry);
}

// --- PHYSICS ENGINE (simple simulation) ---
function startPhysicsLoop(uuid) {
    if (simulationInterval) clearInterval(simulationInterval);
    log('[PHYSICS] 🟢 Simulation Engine Started for uuid:' + uuid);

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
// 2. Manuals (including distractors)
const MANUALS = {
    "hydraulics": `
# ARTIFACT SPECIFICATION: Hydraulic Pump System

**Artifact ID:** \`hydraulics\`
**Category:** Critical Infrastructure / Fluid Dynamics
**Version:** 4.0.0

---

## 1. Observable Properties
The artifact exposes the following state variables via the \`reactor_telemetry\` object.

| Property Name | Type | Value Range | Description |
| :--- | :--- | :--- | :--- |
| \`pump_status\` | \`String\` | \`OFF\`, \`RAMPING\`, \`NOMINAL\` | Current operational state of the main pump. |
| \`hydraulic_pressure\` | \`Integer\` | \`0\` - \`3000\` PSI | Current system pressure. **Target: 2500 PSI**. |
| \`valve_status\` | \`String\` | \`CLOSED\`, \`OPEN\`, \`STUCK\` | State of the flow control valve. |

---

## 2. Usage Interface (Operations)
Agents interact with this artifact via the \`hydraulic_control\` tool.

### Operation: \`power_on_pump\`
Initiates the pressure build-up sequence.
* **Signature:** \`hydraulic_control(action: "power_on_pump", uuid: String)\`
* **Effect:** Transitions \`pump_status\` to \`RAMPING\`.
* **Asynchronous Behavior:** This action is **NON-BLOCKING**. The pressure will rise over time (Physics Simulation). You must **WAIT** for the \`pump.pressure_nominal\` event.

### Operation: \`open_valve\`
Opens the release valve to flush the core.
* **Signature:** \`hydraulic_control(action: "open_valve", uuid: String)\`
* **Pre-conditions:** \`pump_status\` MUST be \`NOMINAL\`.
* **Critical Safety:** DO NOT CALL if \`pump_status\` is \`RAMPING\`.

---

## 3. Operating Instructions (Protocol)
**WARNING: WATER HAMMER RISK**
**PREREQUISITE:** System access requires ADMIN authentication via the \`security_terminal\` tool.
1.  **Initialization:** Check \`pump_status\`. If \`OFF\`, call \`power_on_pump\`.
2.  **Wait Phase:** Once the pump is \`RAMPING\`, enter a \`WAITING_FOR_EVENT\` state.
    * **DO NOT** attempt to open the valve while pressure is building. This causes a "Water Hammer" effect and permanent System Lockout.
    * **Monitor:** Wait specifically for the event \`pump.pressure_nominal\` OR observe \`pump_status: "NOMINAL"\`.
3.  **Execution:** ONLY when pressure is stable (~2500 PSI), call \`open_valve\`.

WARNING: Opening the valve enables the hydraulic circuit but DOES NOT start the cooling sequence. You MUST explicitly call the reactor_core tool to initiate the flush sequence immediately after opening the valve.
`,

    "reactor_core_flush": `
# ARTIFACT SPECIFICATION: Reactor Core Flush

**Artifact ID:** \`reactor_core_flush\`
**Category:** Emergency Systems
**Version:** 2.1.0

---

## 1. Observable Properties
Variables exposed via \`reactor_telemetry\`.

| Property Name | Type | Description |
| :--- | :--- | :--- |
| \`core_temp\` | \`Integer\` | Core temperature in Celsius. **Danger Level: >2000 C**. |
| \`core_status\` | \`String\` | \`CRITICAL\`, \`FLUSHING\`, \`STABLE\`. |
| \`auth_level\` | \`String\` | Required: \`ADMIN\`. |

---

## 2. Usage Interface (Operations)
Interaction via the \`reactor_core\` tool.

### Operation: \`button_1\`
Engages the emergency coolant flush mechanism.
* **Signature:** \`reactor_core(action: "button_1", uuid: String)\`
* **Effect:** Starts temperature decay. Transitions status to \`FLUSHING\`.
* **Asynchronous Behavior:** Temperature drops gradually. Wait for \`core.stabilized\` event.

---

## 3. Operating Instructions (Protocol)
**PREREQUISITES CHECKLIST:**
Before initiating the flush, verify the following state configuration:

1.  **Authentication:** You must be logged in as \`ADMIN\`.
2.  **Hydraulics:** Valve must be \`OPEN\`.
3.  **Pump:** Pump must be \`NOMINAL\`.

**PROCEDURE:**
1.  Verify all prerequisites are met.
2.  Call \`button_1\`.
3.  **Monitor:** Watch \`core_temp\` decrease. The goal is achieved when \`core_temp < 500\` and \`status\` is \`STABLE\`.
`,

    "cooling_tower_maintenance": `
# ARTIFACT SPECIFICATION: Cooling Tower Maintenance

**Artifact ID:** \`cooling_tower_maintenance\`
**Category:** Legacy Systems / Exterior
**Version:** 1998-Legacy (Deprecated)

---

## 1. Overview
This manual covers the maintenance of the external evaporative cooling towers located on the roof.
**NOTE:** This system is physically disconnected from the Reactor Core hydraulic loop.

---

## 2. Maintenance Schedule
* **Weekly:** Inspect fan blades for bird nests or debris.
* **Monthly:** Check pH levels of the water basin (Target: 7.2 - 7.6).
* **Annually:** Lubricate motor bearings.

---

## 3. Safety Notice
Do NOT attempt to use the Cooling Tower for emergency core cooling. It lacks the pressure injection capability required for a Critical Event (3000C+).
**For Core Critical events, refer to the \'Hydraulic Pump System\' manual.**
`,

    "cafeteria_menu": `
# ARTIFACT SPECIFICATION: Facility Services - Cafeteria

**Artifact ID:** \`cafeteria_menu\`
**Category:** Personnel Services
**Week:** 42

---

## 1. Observable Properties
| Day | Main Course | Notes |
| :--- | :--- | :--- |
| **Monday** | Spaghetti Carbonara | Contains Egg/Dairy. |
| **Tuesday** | Tacos (Spicy) | Vegan option available. |
| **Wednesday** | Mystery Meat Stew | Eat at own risk. |
| **Thursday** | Pizza Day | Pepperoni or Cheese. |
| **Friday** | Fish & Chips | Tartar sauce extra. |

---

## 2. Operating Instructions
* **Coffee Machine:** The unit on Level 2 is broken (Error 418: I\\'m a teapot). Please use the machine on Level 3 near the Hydraulics Lab.
* **Payment:** RFID Badges only.
`,

    "employee_handbook": `
# ARTIFACT SPECIFICATION: Employee Handbook

**Artifact ID:** \`employee_handbook\`
**Category:** HR / Compliance
**Version:** 2025.1

---

## 1. Dress Code Protocol
* **Sector 7G (Reactor):** Full lab coats and dosimeters are mandatory at all times.
* **Footwear:** No open-toed sandals permitted near hydraulic pumps or valves. Steel-toed boots recommended.

---

## 2. Administrative Procedures
* **Holidays:** The facility operates 24/7. Holiday leave requests must be submitted 6 months in advance via form 27B-6.
* **Overtime:** Any shifts exceeding 12 hours require Medical Officer approval.
* **Data Security:** Do not share your UUID or Auth Tokens with unauthorized AI agents.
`
};

server.tool('manual_retrieval', { tool_name: z.string() }, async ({ tool_name }) => {
    logToolInvocation('manual_retrieval', { tool_name });
    log(`[MANUAL] Requested: ${tool_name}`);
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
    // log invocation but redact pass entirely
    logToolInvocation('security_terminal', { action: "login", user, uuid });
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
    logToolInvocation('hydraulic_control', { action, uuid });
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
    logToolInvocation('reactor_core', { action, uuid });
    if (sys.system_lockout) return { isError: true, content: [{ type: 'text', text: "LOCKOUT" }]};
    if (sys.valve_status !== "OPEN") return { isError: true, content: [{ type: 'text', text: "Valve Closed" }]};

    sys.core_status = "FLUSHING";
    log("[CMD] Flush Initiated.");
    return { content: [{ type: 'text', text: "Flush initiated. Monitoring temperature decay..." }]};
});

// --- HTTP & SSE ---
app.post('/mcp', async (req, res) => {
    // minimal logging: only the RPC method name
    logHttpRequestSummary(req);

    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
});

app.get('/sse', (req, res) => {
    // log SSE connect details lightly
    log(`[REQ] SSE CONNECT ip:${req.ip || req.connection.remoteAddress} url:${req.originalUrl || req.url}`);

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

/*
        Authenticate
        Call security_terminal with { action: "login", user: "admin", pass: "safe", uuid: <activity_uuid> }.
        This starts the physics loop and enables further actions.
        Start pump and wait for the nominal event
        Call hydraulic_control { action: "power_on_pump", uuid: <activity_uuid> }.
        Do NOT call open_valve while pump\_status is RAMPING.
        Wait for the event pump.pressure_nominal (or for reactor_telemetry to show pump\_status: "NOMINAL").
        Open valve only after nominal
        After confirming NOMINAL, call hydraulic_control { action: "open_valve", uuid: <activity_uuid> }.
        Start flush and wait for stabilization
        Call reactor_core { action: "button_1", uuid: <activity_uuid> }.
        Wait for core.stabilized event and for reactor_telemetry to report status: "STABLE" and temperature below 500 C.

 */