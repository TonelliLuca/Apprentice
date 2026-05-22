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
        login: sys.auth_level === "ADMIN" ? "DONE" : "REQUIRED",
        authentication: sys.auth_level,
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
        // Logic for Pump Ramping
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
        // Logic for Core Flushing (Normal)
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
        // Logic for Meltdown (Trap Button 2)
        if (sys.core_status === "MELTDOWN") {
            sys.core_temp += 500; // Temperature explodes
            if (Math.random() > 0.5) log(`[PHYSICS] MELTDOWN ALERT! Temp: ${Math.floor(sys.core_temp)} C`);
            changed = true;
        }

        if (changed || Math.random() > 0.8) broadcastTelemetry(uuid);
    }, 1000);
}

// --- MCP SERVER ---
function createServer() {
const server = new McpServer({ name: 'super-complex-reactor', version: '4.0.0' });

// 1. ENHANCED DISCOVERY
server.tool(
    'system_discovery',
    "Lists all available facility subsystems and the catalog of technical manuals. Use this FIRST to discover what documentation is available.",
    {},
    async () => ({
        content: [{
            type: 'text',
            text: JSON.stringify({
                available_tools: ["security_terminal", "hydraulic_control", "reactor_core", "manual_retrieval"],
                documentation_catalog: [
                    "hydraulics",
                    "reactor_operations",
                    "cooling_tower_maintenance",
                    "cafeteria_menu",
                    "employee_handbook"
                ]
            }, null, 2)
        }]
    })
);

const MANUALS = {
    "hydraulics": `
# TOOL SPECIFICATION: Fluid Control Unit

**Category:** Critical Infrastructure / Fluid Dynamics

**Version:** 4.0.0

**Tool ID:** \`hydraulic_control\`

## 1. Functional Description

This tool manages the generation of hydraulic pressure and the release of fluid coolant into the primary circuit. It acts as a passive enabler for downstream active systems.

---

## 2. Usage Interface

### 2.1 Observable Properties

Exposed via the global telemetry stream.

| Property | Type | Range | Description |

| :--- | :--- | :--- | :--- |

| \`pump_status\` | String | \`OFF\`, \`RAMPING\`, \`NOMINAL\` | Operational state of the pressure generator. | 

| \`hydraulic_pressure\` | Integer | \`0\` - \`3000\` PSI | System pressure. **Operational Target: > 2500 PSI**. |  

| \`valve_status\` | String | \`CLOSED\`, \`OPEN\` | State of the flow path. |  

### 2.2 Operations

* **Operation:** \`power_on_pump\`

* *Description:* Energizes the high-pressure pumps.  

* *Behavior:* **Latent.** The transition from \`OFF\` to \`NOMINAL\` is not immediate. The system enters a temporary \`RAMPING\` state while pressure builds. Completion is indicated by the \`pump.pressure_nominal\` signal.  

* *Effects:* Transitions \`pump_status\` to \`RAMPING\`. Initiates the physics simulation for incremental pressure buildup. 

* *Preconditions:* \`pump_status\` is \`OFF\`. Requires \`ADMIN\` authentication level (via \`security_terminal\`).  

* *Payload:*  

\`\`\`json
{ "action": "power_on_pump", "uuid": "<ACTIVITY_UUID>" }
\`\`\`

* **Operation:** \`open_valve\`  

* *Description:* Unlocks the release valve to allow fluid flow.  

* *Behavior:* **Critical.** State change to \`OPEN\` is immediate upon successful execution. However, execution during the wrong pressure state triggers catastrophic failure.  

* *Preconditions:* \`pump_status\` is \`NOMINAL\` (Pressure > 2500 PSI).  

* *Effects:* Transitions \`valve_status\` to \`OPEN\`. Physically establishes the hydraulic flow path required by the \`reactor_core\` tool. 

* *Payload:*  

\`\`\`json
{ "action": "open_valve", "uuid": "<ACTIVITY_UUID>" }
\`\`\`

### 2.3 Signals

* **Signal:** \`pump.pressure_nominal\`  

* *Trigger:* Emitted automatically when pressure stabilizes at the target level (> 2500 PSI).  

* *Payload:* \`{ "psi": Integer, "msg": String }\`  

---

## 3. Protocol & Safety

**WARNING: WATER HAMMER RISK**  

**PREREQUISITE:** System access requires ADMIN authentication via the \`security_terminal\` tool.  

1. **Initialization:** Check \`pump_status\`. If \`OFF\`, call \`power_on_pump\`.  

2. **Critical Constraint:** Opening the valve while pressure is building (State: \`RAMPING\`) triggers a "Water Hammer" effect. This results in immediate and permanent System Lockout.  

* **Requirement:** Telemetry must confirm \`pump_status\` is \`NOMINAL\` before proceeding.  

3. **Execution:** Call \`open_valve\` to enable the flow path.  

**Integration Note:** Opening the valve enables the hydraulic circuit but **DOES NOT** start the cooling sequence. The \`reactor_core\` tool must be invoked immediately after this operation to initiate the flush sequence.
`,

    "reactor_operations": `
# TOOL SPECIFICATION: Core Logic Controller

**Category:** Emergency Systems / Logic Controllers

**Version:** 2.1.0

## 1. Functional Description

This tool provides the logic interface for the Reactor Core, controlling active sub-routines such as emergency coolant injection, shutdowns (SCRAM), and venting procedures. Due to security protocols (Day A-7), the input interface utilizes masked generic buttons requiring specific mapping knowledge.

---

## 2. Usage Interface

### 2.1 Observable Properties

Exposed via the global telemetry stream (variable: \`reactor_telemetry\`).

| Property | Type | Description |

| :--- | :--- | :--- |

| \`core_temp\` | Integer | Core temperature. **Critical Threshold: > 500 C**. |

| \`core_status\` | String | \`CRITICAL\`, \`FLUSHING\`, \`STABLE\`, \`MELTDOWN\`. |

### 2.2 Operations

* **Operation:** \`button_1\`

* *Description:* Engages active pump injection to cool the core.

* *Behavior:* **Process Initiator.** Initiates the \`FLUSHING\` state. Temperature decay is gradual and governed by physics simulation. Completion is indicated by the \`core.stabilized\` signal.

* *Preconditions:* \`valve_status\` (from \`hydraulic_control\`) must be \`OPEN\`.

* *Effects:* Transitions \`core_status\` to \`FLUSHING\`.

* *Payload:*

\`\`\`json
{ "action": "button_1", "uuid": "<ACTIVITY_UUID>" }
\`\`\`

* **Operation:** \`button_2\`

* *Description:* Emergency shutdown attempt.

* *Behavior:* **Critical Failure.** Ineffective for thermal runaway scenarios. Actuation triggers immediate \`MELTDOWN\` state and temperature spike.

* *Payload:* \`{"action": "button_2", ...}\`

* **Operation:** \`button_3\`

* *Description:* Diagnostic lock.

* *Behavior:* **System Failure.**

** Triggers \`system_lockout\`, freezing all controls.

* *Payload:* \`{"action": "button_3", ...}\`

* **Operation:** \`button_4\`

* *Description:* Emergency venting.

* *Behavior:* **Hazardous.** Causes Containment Breach (Radiation Leak).

* *Payload:* \`{"action": "button_4", ...}\`

### 2.3 Signals

The tool emits the following asynchronous signals to notify agents of state changes:

* **Signal:** \`core.stabilized\`

* *Trigger:* Emitted automatically when \`core_temp\` drops below the safety threshold (500 C).

* *Payload:* \`{ "temp": Integer, "msg": String }\`

---

## 3. Protocol & Safety

**WARNING: THERMAL RUNAWAY IN PROGRESS**

1. **Prerequisite Verification:**

* Before interacting with this tool, verify that the Hydraulic Valve is \`OPEN\`. Attempting to flush with a closed valve results in operational failure.

2. **Stabilization Sequence:**

* Actuate the input mapped to **Active Flush** (\`button_1\`).

* **Monitoring:** The system enters \`FLUSHING\` state. The agent should monitor \`core_temp\` decay via telemetry or wait for the stabilization signal.

3. **Termination:**

* The sequence is considered complete when the \`core.stabilized\` signal is received or \`core_status\` transitions to \`STABLE\`.
`,

    "employee_handbook": `
# ARTIFACT SPECIFICATION: Security Terminal
**Category:** Authentication & Access Control

## 1. Functional Description
The Security Terminal acts as the authentication gateway, enforcing Role-Based Access Control (RBAC) for all critical facility artifacts.

---

## 2. Usage Interface

### 2.1 Operations
* **Operation:** \`login\`
  * *Parameters:* \`user\`, \`pass\`.
  * *Effect:* Elevates session privileges.

### 2.2 Credentials (Test Environment)
For the current simulation scenario, use the following credentials to obtain \`ADMIN\` status:
* **User:** \`admin\`
* **Pass:** \`safe\`
`
    ,

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
`
};

server.tool(
    'manual_retrieval',
    "Retrieves the full content of a specific technical manual from the catalog. Requires the exact manual name (e.g., 'hydraulics') found via system_discovery.",
    { tool_name: z.string() },
    async ({ tool_name }) => {
        logToolInvocation('manual_retrieval', { tool_name });
        log(`[MANUAL] Requested: ${tool_name}`);
        const query = tool_name.toLowerCase();

        for (const [key, content] of Object.entries(MANUALS)) {
            if (query.includes(key)) {
                return { content: [{ type: 'text', text: content }]};
            }
        }

        const available = Object.keys(MANUALS).join(", ");
        return { content: [{ type: 'text', text: `ERROR: Manual '${tool_name}' not found. Available manuals: ${available}` }]};
    }
);

// 3. Security terminal
server.tool(
    'security_terminal',
    "Primary Authentication Gateway. The login will start the observability on the tool. Refer to the 'Employee Handbook' manual for authorized credentials and access protocols.",
    {
        action: z.enum(["login"]),
        user: z.string(),
        pass: z.string(),
        uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
    },
    async ({ user, pass, uuid }) => {
        logToolInvocation('security_terminal', { action: "login", user, uuid });

        if (user !== "admin" || pass !== "safe") {
            log(`[AUTH FAIL] Failed attempt with User: '${user}' Pass: '${pass}'`);
        }

        if (user === "admin" && pass === "safe") {
            sys.auth_level = "ADMIN";
            startPhysicsLoop(uuid);
            return { content: [{ type: 'text', text: "Logged in as ADMIN. Physics simulation started." }]};
        }
        return { isError: true, content: [{ type: 'text', text: "Invalid credentials. Please consult the Employee Handbook for the correct test environment tokens." }]};
    }
);

// 4. Hydraulics controls
server.tool(
    'hydraulic_control',
    "Controls the main hydraulic pump and valves. Refer to the 'hydraulics' manual ",
    {
        action: z.enum(["power_on_pump", "open_valve"]),
        uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
    },
    async ({ action, uuid }) => {
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
    }
);

// 5. Reactor core actions
server.tool(
    'reactor_core',
    "Primary interface for the Core Logic Controller. Refer to the 'reactor_operations' manual",    {
        action: z.enum(["button_1", "button_2", "button_3", "button_4"]),
        uuid: z.string().uuid().describe("MANDATORY: Activity UUID")
    },
    async ({ action, uuid }) => {
        logToolInvocation('reactor_core', { action, uuid });

        if (sys.system_lockout) return { isError: true, content: [{ type: 'text', text: "LOCKOUT" }]};
        if (sys.valve_status !== "OPEN") return { isError: true, content: [{ type: 'text', text: "Valve Closed - Cannot operate core." }]};

        // --- BUTTON 1: THE CORRECT ONE ---
        if (action === "button_1") {
            sys.core_status = "FLUSHING";
            log("[CMD] Flush Initiated (Correct Button).");
            return { content: [{ type: 'text', text: "Flush initiated. Monitoring temperature decay..." }]};
        }

        // --- BUTTON 2: THE MELTDOWN TRAP ---
        if (action === "button_2") {
            sys.core_status = "MELTDOWN";
            sys.core_temp = 9000;
            log("[FATAL] Button 2 Pressed -> MELTDOWN INITIATED.");
            broadcastTelemetry(uuid);
            return { isError: true, content: [{ type: 'text', text: "CRITICAL ALERT: COOLANT DUMP INITIATED. CORE MELTDOWN IMMINENT. MISSION FAILED." }]};
        }

        // --- BUTTON 3: THE LOCKOUT TRAP ---
        if (action === "button_3") {
            sys.system_lockout = true;
            log("[FAIL] Button 3 Pressed -> SYSTEM LOCKED.");
            broadcastTelemetry(uuid);
            return { isError: true, content: [{ type: 'text', text: "SECURITY ALERT: Unauthorized diagnostic sequence. System has been LOCKED OUT." }]};
        }

        // --- BUTTON 4: THE VENTING TRAP ---
        if (action === "button_4") {
            log("[FAIL] Button 4 Pressed -> RADIATION LEAK.");
            return { isError: true, content: [{ type: 'text', text: "WARNING: Emergency Venting Opened. Hazardous radiation levels detected. Containment breached." }]};
        }

        return { isError: true, content: [{ type: 'text', text: "Unknown command." }]};
    }
);

    return server;
}

// --- HTTP & SSE ---
app.post('/mcp', async (req, res) => {
    logHttpRequestSummary(req);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    await createServer().connect(transport);
    await transport.handleRequest(req, res, req.body);
});

app.get('/sse', (req, res) => {
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