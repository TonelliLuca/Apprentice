import time
import threading
import json
import math
import requests
import asyncio
import os
import random
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse
from contextlib import asynccontextmanager

# Rate limiter (global, 1 call/sec)
import queue
import concurrent.futures

class RateLimiter:
    """
    Executes submitted callables with at least `min_interval` seconds between calls.
    Returns a concurrent.futures.Future for each submission.
    """
    def __init__(self, min_interval: float = 1.0):
        self.min_interval = float(min_interval)
        self._q = queue.Queue()
        self._thread = threading.Thread(target=self._worker, daemon=True)
        self._thread.start()

    def submit(self, func, *args, **kwargs) -> concurrent.futures.Future:
        fut = concurrent.futures.Future()
        self._q.put((func, args, kwargs, fut))
        return fut

    def _worker(self):
        while True:
            func, args, kwargs, fut = self._q.get()
            try:
                res = func(*args, **kwargs)
                fut.set_result(res)
            except Exception as e:
                fut.set_exception(e)
            time.sleep(self.min_interval)

# Instantiate global rate limiter
rate_limiter = RateLimiter(min_interval=1.0)

# Audit helpers (safe, minimal)
AUDIT_LOG_FILE = os.path.join(os.path.dirname(__file__), "api_calls.log")
_audit_lock = threading.Lock()

def _mask_headers(headers):
    try:
        if not headers:
            return {}
        masked = dict(headers)
        for key in ("Authentication", "Authorization", "X-API-Key", "api-key", "token"):
            if key in masked and masked[key]:
                masked[key] = "***"
        return masked
    except Exception:
        return {}

def _append_audit_entry(entry: dict):
    try:
        with _audit_lock:
            with open(AUDIT_LOG_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        pass

# Rate-limited HTTP wrappers
def rl_call(method, *args, **kwargs):
    """Call requests.<method> through the rate limiter and append an audit entry."""
    func = getattr(requests, method)
    future_timeout = kwargs.pop('future_timeout', 15)

    url = args[0] if args else kwargs.get('url', '')
    headers = _mask_headers(kwargs.get('headers'))
    data_payload = None
    if 'json' in kwargs:
        data_payload = {"json": kwargs['json']}
    elif 'data' in kwargs:
        try:
            data_payload = {"data": kwargs['data']}
        except Exception:
            data_payload = {"data": str(kwargs['data'])}

    submitted_at = time.time()

    fut = rate_limiter.submit(func, *args, **kwargs)
    try:
        resp = fut.result(timeout=future_timeout)
        body_preview = None
        try:
            body_preview = resp.json()
        except Exception:
            txt = getattr(resp, 'text', '')
            body_preview = txt[:500]

        entry = {
            "ts": submitted_at,
            "method": method.upper(),
            "url": url,
            "headers": headers,
            "payload": data_payload,
            "status": resp.status_code,
            "response": body_preview
        }
        _append_audit_entry(entry)
        return resp
    except Exception as e:
        entry = {
            "ts": submitted_at,
            "method": method.upper(),
            "url": url,
            "headers": headers,
            "payload": data_payload,
            "error": str(e)
        }
        _append_audit_entry(entry)
        raise

def rl_get(url, **kwargs):
    return rl_call('get', url, **kwargs)

def rl_post(url, **kwargs):
    return rl_call('post', url, **kwargs)

def rl_put(url, **kwargs):
    return rl_call('put', url, **kwargs)

def rl_delete(url, **kwargs):
    return rl_call('delete', url, **kwargs)

# Configuration
BASE_URL = "https://api.interactions.ics.unisg.ch/cherrybot"
TOKEN_FILE = "token.txt"
POLLING_INTERVAL = 5.0

OPERATOR_DATA = {
    "name": "Luca Tonelli",
    "email": "luca.tonelli11@studio.unibo.it"
}

# Headers
def get_auth_headers(token):
    return {
        "Authentication": token,
        "Content-Type": "application/json",
        "Accept": "application/json"
    }

# Tools definitions
TOOLS_DEFINITIONS = [
    {
        "name": "manual_retrieval",
        "description": "Retrieves the documentation/manual for a specific tool or system.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "tool_name": {"type": "string", "description": "The name of the tool to look up (e.g. 'robot_control', 'camera_control')"}
            },
            "required": ["tool_name"]
        }
    },
    {
        "name": "camera_control",
        "description": "Computer Vision System. Detects objects in the workspace.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["locate_cube"], "description": "Action to perform."},
                "uuid": {"type": "string", "description": "The activity UUID for event tracking"}
            },
            "required": ["action", "uuid"]
        }
    },
    {
        "name": "robot_control",
        "description": "Controls the industrial robot arm (Movement & Gripper). EXECUTE 'login' FIRST. Actions are async.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["login", "move", "gripper"], "description": "The action to perform. 'move' for arm, 'gripper' for fingers."},
                "uuid": {"type": "string", "description": "The activity UUID for event tracking"},
                "x": {"type": "number", "description": "Target X coordinate (for 'move')"},
                "y": {"type": "number", "description": "Target Y coordinate (for 'move')"},
                "z": {"type": "number", "description": "Target Z coordinate (for 'move')"},
                "roll": {"type": "number", "description": "Target Roll rotation (for 'move')"},
                "pitch": {"type": "number", "description": "Target Pitch rotation (for 'move')"},
                "yaw": {"type": "number", "description": "Target Yaw rotation (for 'move')"},
                "value": {"type": "integer", "description": "Gripper opening in mm (0-800) (for 'gripper')"}
            },
            "required": ["action", "uuid"]
        }
    }
]

# Robot manual
ROBOT_MANUAL = """ # INDUSTRIAL ROBOT ARM CONTROL MANUAL (v1.5)

## 1. SYSTEM OVERVIEW
This tool controls a 6-axis industrial robot arm equipped with a parallel gripper and a Computer Vision system.
**CRITICAL**: Physical actions (`move`, `gripper`) are **ASYNCHRONOUS**. Wait for events.

---

## 2. SETUP (Required First)
**Action**: `login` (Tool: `robot_control`)
- **Description**: Authenticates with the robot controller. Must be executed once.

---

## 3. COMPUTER VISION (Object Detection)
**Tool**: `camera_control` | **Action**: `locate_cube`
- **Description**: Activates the camera sensors.
- **Effect**: Before calling this, the `cube` position is UNKNOWN (hidden from telemetry). Calling this action **starts the real-time streaming** of the cube's coordinates into the `robot_telemetry` variable.
- **Usage**: Call this FIRST to populate your context with the target's location, this function does NOT return coordinates directly and doesnt generate an event.
- **Behavior**: Returns "Cube location streaming started. Check telemetry."

---

## 4. ROBOT ARM (Movement)
**Tool**: `robot_control` | **Action**: `move`
- **Parameters**: `x`, `y`, `z` (Required), `roll`, `pitch`, `yaw` (Optional).
- **Behavior**: Returns "Moving..." after the moving action is completed you will receive the event : `robot.movement_finished`.

---

## 5. GRIPPER (Pick & Place)
**Tool**: `robot_control` | **Action**: `gripper`
- **Parameters**: `value` (0=Closed, 800=Open).
- **Behavior**: Returns "Gripper Moving...". **WAIT FOR EVENT**: `robot.gripper_finished`.

### IMPORTANT: "NO CHANGE" SCENARIO
If the gripper is ALREADY at the target value (e.g. you send 0 and it is already 0), the system will send the `robot.gripper_finished` event **IMMEDIATELY**.
- **CRITICAL**: Do NOT ignore this event just because the telemetry number didn't change.
- **RULE**: If you receive `robot.gripper_finished`, the action is SUCCESSFUL. Proceed to the next step immediately. DO NOT WAIT.

- **Physics**: To pick up the cube:
    1. MOVE the robot arm to the cube's coordinates (from `robot_telemetry.cube`) and if the gripper is already closed, OPEN it (value > 790).
    2. CLOSE the gripper (value < 10).
    3. **VERIFICATION**: After gripping, check `robot_telemetry`. If the `cube` coordinates move WITH the `position` of the robot, the pick was successful.

"""

# Global state
class RobotState:
    def __init__(self):
        self.token = None
        self.active_uuid = None
        self.login = False

        # Robot telemetry cache
        self.last_tcp = None
        self.last_gripper = None

        # Arm movement state
        self.target_pos = None
        self.is_moving = False

        # Gripper state
        self.target_gripper = None
        self.is_gripping = False

        # Cube state (physics simulation)
        self.cube_pos = {
            "x": 300,
            "y": 0,
            "z": 400
        }
        self.is_carrying_cube = False
        self.streaming_cube = False

        print(f"🎲 [SIMULATION] Cube spawned at: {self.cube_pos}")

        self.queue = asyncio.Queue()

        # Load token from disk if present
        if os.path.exists(TOKEN_FILE):
            try:
                with open(TOKEN_FILE, "r") as f:
                    t = f.read().strip()
                    if t:
                        self.token = t
                        print(f"🔄 [INIT] Token found: {t[:6]}...")
            except:
                pass

    def set_token(self, token):
        self.token = token
        with open(TOKEN_FILE, "w") as f:
            f.write(token)
        print(f"💾 [INIT] Token saved: {token[:6]}...")

    def set_target(self, target, uuid):
        self.target_pos = target
        self.active_uuid = uuid
        self.is_moving = True

    def set_gripper_target(self, value, uuid):
        self.target_gripper = value
        self.active_uuid = uuid
        self.is_gripping = True

    def clear_token(self):
        self.token = None
        self.login = False
        self.last_tcp = None
        if os.path.exists(TOKEN_FILE):
            os.remove(TOKEN_FILE)
            print("🗑️ [SYSTEM] Token file removed (session expired).")

state = RobotState()
loop = None

# Utilities
def calculate_distance(pos1, pos2):
    if not pos1 or not pos2:
        return 9999.0
    dx = pos1.get('x', 0) - pos2.get('x', 0)
    dy = pos1.get('y', 0) - pos2.get('y', 0)
    dz = pos1.get('z', 0) - pos2.get('z', 0)
    return math.sqrt(dx*dx + dy*dy + dz*dz)

# Worker 1: Robot API polling
def robot_api_worker():
    print(f"🤖 [WORKER-API] Started communication with {BASE_URL}")
    while True:
        time.sleep(POLLING_INTERVAL)

        if not state.token:
            state.last_tcp = None
            continue

        try:
            headers = get_auth_headers(state.token)

            resp_tcp = rl_get(f"{BASE_URL}/tcp", headers=headers, timeout=10, future_timeout=12)

            resp_grip = rl_get(f"{BASE_URL}/gripper", headers=headers, timeout=10, future_timeout=12)
            if resp_grip.status_code != 200:
                print(f"❌ [API-ERROR] Status Gripper: {resp_grip.status_code} | Body: {resp_grip.text}")
                continue
            gripper_val = resp_grip.json()

            if resp_tcp.status_code == 200:
                coords = resp_tcp.json().get("coordinate", {})

                if not coords:
                    print(f"⚠️ [API-WARNING] 200 OK but 'coordinate' was empty. Response: {resp_tcp.json()}")

                state.last_tcp = coords
                state.last_gripper = gripper_val
                print(f"📡 [API] TCP: {coords} | Gripper: {gripper_val}")

                # Movement finished event
                if state.is_moving and state.target_pos:
                    dist = calculate_distance(coords, state.target_pos.get("coordinate"))
                    if dist < 10.0:
                        print(f"✅ [ARM] Arrived (Dist: {dist:.1f}mm)")
                        state.is_moving = False
                        evt = {"jsonrpc": "2.0", "method": "notifications/message", "params": {"uuid": state.active_uuid, "mcpType": "event", "event": {"name": "robot.movement_finished", "message": "Arm Arrived."}}}
                        if loop:
                            asyncio.run_coroutine_threadsafe(state.queue.put(json.dumps(evt)), loop)

                # Gripper finished event
                if state.is_gripping and state.target_gripper is not None:
                    if abs(gripper_val - state.target_gripper) < 5:
                        print(f"✅ [GRIPPER] Set (Val: {gripper_val})")
                        state.is_gripping = False
                        evt = {"jsonrpc": "2.0", "method": "notifications/message", "params": {"uuid": state.active_uuid, "mcpType": "event", "event": {"name": "robot.gripper_finished", "message": "Gripper Ready."}}}
                        if loop:
                            asyncio.run_coroutine_threadsafe(state.queue.put(json.dumps(evt)), loop)

            elif resp_tcp.status_code in [401, 403]:
                print(f"⚠️ [API] Token expired ({resp_tcp.status_code}). Resetting.")
                state.clear_token()

            else:
                print(f"❌ [API-ERROR] Status TCP: {resp_tcp.status_code} | Body: {resp_tcp.text}")

        except Exception as e:
            print(f"❌ [API-EXCEPTION] Error: {e}")

# Worker 2: Physics & telemetry
def physics_telemetry_worker():
    print(f"🎲 [WORKER-PHYSICS] Started (Cube & Telemetry)")
    while True:
        time.sleep(POLLING_INTERVAL)

        current_robot_pos = state.last_tcp
        current_gripper_val = state.last_gripper

        # Attach/detach cube based on distance and gripper state
        if current_robot_pos and current_gripper_val is not None:
            if state.is_carrying_cube:
                state.cube_pos = current_robot_pos.copy()
                if current_gripper_val > 20:
                    state.is_carrying_cube = False
                    print(f"📦 [PHYSICS] Cube DROPPED at {state.cube_pos}!")
            else:
                dist_cube = calculate_distance(current_robot_pos, state.cube_pos)
                if current_gripper_val < 10 and dist_cube < 15:
                    state.is_carrying_cube = True
                    print("📦 [PHYSICS] Cube ATTACHED!")

        status_str = "IDLE"
        if state.is_moving:
            status_str = "MOVING"
        if state.is_gripping:
            status_str = "GRIPPING"
        if not state.login:
            status_str = "OFFLINE"

        telemetry_data = {
            "status": status_str,
            "is_login_done": state.login,
            "update timestamp": int(time.time() * 1000)
        }

        if current_robot_pos:
            telemetry_data["position"] = current_robot_pos
            telemetry_data["gripper"] = current_gripper_val
        else:
            telemetry_data["position"] = {"x": 0, "y": 0, "z": 0}
            telemetry_data["gripper"] = 0

        if state.streaming_cube:
            telemetry_data["cube"] = state.cube_pos

        # Route telemetry to the correct activity via active_uuid
        if state.active_uuid or state.streaming_cube:
            uid = state.active_uuid if state.active_uuid else "stream-only"

            msg = {
                "jsonrpc": "2.0",
                "method": "notifications/message",
                "params": {
                    "uuid": uid,
                    "mcpType": "variable",
                    "name": "robot_telemetry",
                    "value": telemetry_data
                }
            }
            if loop:
                asyncio.run_coroutine_threadsafe(state.queue.put(json.dumps(msg)), loop)

# Startup token cleanup
def cleanup_existing_token_on_start():
    """
    If a local token file exists at startup, revoke it via DELETE /operator/{token},
    remove the local file, and clear in-memory token/login state to avoid polling with a stale token.
    """
    if os.path.exists(TOKEN_FILE):
        try:
            with open(TOKEN_FILE, "r", encoding="utf-8") as f:
                t = f.read().strip()
        except Exception as e:
            print(f"⚠️ [STARTUP] Failed reading token file: {e}. Removing file.")
            try:
                os.remove(TOKEN_FILE)
            except Exception:
                pass
            try:
                state.clear_token()
            except Exception:
                pass
            return

        if t:
            print(f"🧹 [STARTUP] Found token {t[:6]}... Revoking on server and cleaning up.")
            try:
                headers = get_auth_headers(t)
                url = f"{BASE_URL}/operator/{t}"
                resp = rl_delete(url, headers=headers, future_timeout=10)
                print(f"🧾 [STARTUP] DELETE {url} -> {resp.status_code}")
            except Exception as e:
                print(f"⚠️ [STARTUP] Token revoke DELETE failed: {e}")
        else:
            print("⚠️ [STARTUP] Empty token file.")

        try:
            os.remove(TOKEN_FILE)
            print("🗑️ [STARTUP] Local token file removed.")
        except Exception as e:
            print(f"⚠️ [STARTUP] Failed to remove token file: {e}")
        finally:
            try:
                state.clear_token()
            except Exception:
                pass

# Server lifecycle
@asynccontextmanager
async def lifespan(app: FastAPI):
    global loop
    loop = asyncio.get_running_loop()
    cleanup_existing_token_on_start()
    threading.Thread(target=robot_api_worker, daemon=True).start()
    threading.Thread(target=physics_telemetry_worker, daemon=True).start()
    yield

app = FastAPI(lifespan=lifespan)

@app.get("/sse")
async def sse_endpoint(request: Request):
    async def event_generator():
        yield {"data": json.dumps({"type": "connection_established"})}
        while True:
            if await request.is_disconnected():
                break
            data = await state.queue.get()
            yield {"data": data}
    return EventSourceResponse(event_generator())

@app.post("/mcp")
async def handle_mcp(request: Request):
    try:
        body_bytes = await request.body()
        if not body_bytes:
            return JSONResponse(status_code=400, content={"error": "Empty body"})
        data = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"error": "Invalid JSON"})

    method = data.get("method")
    params = data.get("params") or {}
    req_id = data.get("id")

    if method == "initialize":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"protocolVersion": "2024-11-05", "capabilities": {}, "serverInfo": {"name": "robot", "version": "1.3"}}}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS_DEFINITIONS}}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}

    if method == "tools/call":
        if isinstance(params, list):
            params = {}
        tool = params.get("name")
        args = params.get("arguments", {})
        print(f"🛠️ [EXEC] {tool} args={args}")

        if tool == "manual_retrieval":
            return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": ROBOT_MANUAL}]}}

        if tool == "camera_control":
            action = args.get("action")
            uuid = args.get("uuid")

            # Route telemetry to the current activity
            state.active_uuid = uuid

            if action == "locate_cube":
                state.streaming_cube = True
                print(f"📷 [CAMERA] Streaming ON for UUID: {uuid}")
                return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": "Cube location streaming started. Check telemetry."}]}}

        if tool == "robot_control":
            action = args.get("action")
            uuid = args.get("uuid")
            state.active_uuid = uuid

            if action == "login" and not state.login:
                print("🔑 [LOGIN] Request received...")
                if state.token:
                    try:
                        r = rl_put(f"{BASE_URL}/initialize", headers=get_auth_headers(state.token), future_timeout=10)
                        if r.status_code in [200, 202, 204]:
                            return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": "✅ Robot already active."}]}}
                        else:
                            print(f"⚠️ [LOGIN] Initialize returned {r.status_code}: {getattr(r,'text','')}\nHeaders: {r.headers}")
                    except Exception as e:
                        print(f"❌ [LOGIN] Initialize exception: {e}")
                    state.clear_token()

                try:
                    resp = rl_post(f"{BASE_URL}/operator", json=OPERATOR_DATA, future_timeout=15)
                    if resp.status_code == 403:
                        print(f"❌ [LOGIN] Operator rejected (403): {getattr(resp,'text','')}")
                        return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": "❌ Robot Occupied."}}

                    token = None
                    if "Location" in resp.headers:
                        token = resp.headers["Location"].split("/")[-1]
                    elif resp.content:
                        try:
                            token = resp.json().get("token")
                        except Exception as e:
                            print(f"⚠️ [LOGIN] JSON parse failed: {e}. Raw: {getattr(resp,'text','')[:200]}")

                    if token:
                        state.set_token(token)
                        try:
                            init_resp = rl_put(f"{BASE_URL}/initialize", headers=get_auth_headers(token), future_timeout=10)
                            if init_resp.status_code not in [200,202,204]:
                                print(f"⚠️ [LOGIN] Initialize after token returned {init_resp.status_code}: {getattr(init_resp,'text','')}")
                        except Exception as e:
                            print(f"❌ [LOGIN] Initialize after token exception: {e}")
                        state.login = True
                        return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": "✅ Login OK."}]}}

                    print(f"❌ [LOGIN] No token in operator response. Status {resp.status_code}. Body: {getattr(resp,'text','')[:500]}")
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": "❌ Login Failed"}}
                except Exception as e:
                    print(f"❌ [LOGIN] Exception during operator call: {e}")
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": str(e)}}

            if action == "move":
                if not state.token:
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": "LOGIN REQUIRED."}}
                try:
                    target = {
                        "target": {
                            "coordinate": {"x": float(args.get("x",300)), "y": float(args.get("y",0)), "z": float(args.get("z",400))},
                            "rotation": {"roll": 180, "pitch": 0, "yaw": 0}
                        },
                        "speed": 50
                    }
                    print(f"➡️ [MOVE] Moving to: {target['target']}")
                    r = rl_put(f"{BASE_URL}/tcp/target", json=target, headers=get_auth_headers(state.token), future_timeout=20)
                    if r.status_code == 200:
                        state.set_target(target["target"], uuid)
                        return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": "Movement initiated in background."}]}}
                    print(f"❌ [MOVE] Error: {r.status_code} {r.text}")
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": f"API Error: {r.text}"}}
                except Exception as e:
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": str(e)}}

            if action == "gripper":
                if not state.token:
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": "LOGIN REQUIRED."}}
                try:
                    val = int(args.get("value", 20))
                    r = rl_put(f"{BASE_URL}/gripper", json=val, headers=get_auth_headers(state.token), future_timeout=15)
                    if r.status_code == 200:
                        state.set_gripper_target(val, uuid)
                        return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": "Gripper starting movement in background."}]}}
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": f"Gripper Error: {r.text}"}}
                except Exception as e:
                    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32000, "message": str(e)}}

    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": "Method not found"}}

if __name__ == "__main__":
    import uvicorn
    cleanup_existing_token_on_start()
    print(f"🚀 MCP Server for {BASE_URL}")
    uvicorn.run(app, host="0.0.0.0", port=8000, http="h11")
