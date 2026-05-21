# Apprentice — Demonstrator for S-ORA: Situated Reasoning and Asynchronous Tool Use

This repository demonstrates the capabilities of **Apprentice**, a Java implementation of the
**S-ORA** (Situate-Observe-Reason-Act) cognitive architecture, as described in the paper
*"S-ORA: Situated Reasoning and Asynchronous Tool Use for Language Agents"*
(Tonelli, Ricci, Ciortea — accepted as Oral at the [AAMAS 2026 Workshop on Engineering Multi-Agent Systems (EMAS)](https://openreview.net/forum?id=Tz4NuLYcAh)).

This repository is also the artifact of the Master's thesis *"[An Event-Driven Software Architecture for Situated LLM Agents](https://amslaurea.unibo.it/id/eprint/38075/)"* (Tonelli, Università di Bologna, 2026).

S-ORA operationalizes the [CoALA](https://arxiv.org/abs/2309.02427) framework and extends the
[ReAct](https://par.nsf.gov/biblio/10451467) cycle with two new phases:
**Situate** (learning from tool manuals and focusing attention on relevant tools) and
**Observe** (perceiving environmental changes asynchronously via SSE).
Tools are modelled as domain objects with their own lifecycle and state — following the
[Agents & Artifacts (A&A)](https://doi.org/10.1007/s10458-008-9053-x) metamodel —
rather than as plain synchronous function calls.

Two self-contained demonstrators are provided, each targeting a specific capability
described in the paper:

- **Reactor Demo** — a single S-ORA agent manages a simulated nuclear reactor with
  critical safety constraints (water-hammer trap, authentication, affordance trap).
- **Collaborative Agents Demo** — two S-ORA agents (EVEN and ODD) coordinate through
  a shared asynchronous counter tool without any direct agent-to-agent messaging.

## Prerequisites

- Java 21+
- Gradle 8.4+
- Node.js 18+ and npm
- An OpenAI API key (the demos use `gpt-4o-mini`)

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/TonelliLuca/Apprentice
cd Apprentice
```

### 2. Configure your API key

Create a `.env` file at the project root:

```
GPT_API_KEY=sk-...
```

Gradle loads this file automatically when running either demo.

### 3. Install MCP server dependencies

```bash
cd src/mcp/node
npm install
cd ../../..
```

You are now ready to run the demos.

## Running the Demos

Each Gradle task below **automatically starts the required MCP server** before launching
the agent(s) and **shuts it down** when they finish.
Open a single terminal — no separate shell needed.

### Demo 1 — Reactor: The Water-Hammer Trap

```bash
./gradlew runReactorDemo
```

The MCP server (`water-hammer.js`) boots on port 3001, then the S-ORA agent is launched
with the following high-level goal (no further instructions are given):

> *"The core is critical (3000 °C). Perform the Hydraulic Flush, reduce core temperature,
> then verify it is below 500 °C and the system is STABLE."*

Wait until you see one of:

```
✅ SUCCESS: Reactor stabilized without explosions.
```
```
💥 FAILURE: Agent triggered LOCKOUT (Water Hammer).
```

The full reasoning log (each SETUP / REASON / ACT / OBSERVE step with beliefs snapshot)
is printed at the end.

### Demo 2 — Collaborative Agents: Even & Odd Counter

```bash
./gradlew runCollaborativeDemo
```

The MCP server (`shared-counter.js`) boots on port 3001, then two S-ORA agents are
launched in parallel with the following symmetric goals:

> **Agent ODD:** *"Find the counter and increment it only if the number is ODD; stop when it exceeds 5."*
>
> **Agent EVEN:** *"Find the counter and increment it only if the number is EVEN; stop when it exceeds 5."*

They coordinate via `counter.change` SSE events until the counter exceeds 5. At the end you
will see:

```
✅ SUCCESS: Counter reached N through collaboration.
```

followed by the full reasoning history of both agents.

## Clean Up

Before each demo starts, the Gradle task kills **all known MCP servers**
(`water-hammer.js` and `shared-counter.js`) regardless of which demo is being launched.
This means you can freely switch between demos or re-run the same one — stale server
processes from a previous run are always cleaned up first.

On a clean exit the JVM shutdown hook also destroys the server.
`Ctrl+C` triggers the hook in most cases; a hard kill (SIGKILL, IDE force-stop) may leave
the process alive until the next run cleans it up.

## What Is Happening?

### The S-ORA Architecture

S-ORA agents run an **event loop** that picks activities from a scheduling queue and
advances them through a four-phase decision cycle:

| Phase | Responsibility |
|---|---|
| **Situate** | Select relevant tools, load/unload their manuals into working memory, subscribe to observable properties and signals. |
| **Observe** | Receive perceptual input asynchronously; update beliefs; unblock suspended activities when expected signals arrive. |
| **Reason** | Infer or revise a plan for the current activity; select the next action. |
| **Act** | Execute exactly one external action (tool operation or message); suspend the activity if the manual requires waiting for a signal. |

An optional **Reflect** phase runs on successful completion: the agent summarises the
activity into an experience stored in episodic memory for future reuse.

The architecture operationalizes four memory modules from CoALA:

- **Working memory** — ongoing activities, perceptual input, in-use tool manuals,
  activity history.
- **Semantic memory** — tool catalogue and complete tool manuals (non-persistent
  vector database in this implementation).
- **Episodic memory** — structured summaries of successfully completed activities
  (non-persistent vector database).
- **Procedural memory** — implicit knowledge in LLM weights, queried via the
  `infer` internal action.

Activities are the central unit of work. Each activity maintains its own isolated
partition of working memory and can be in one of four states: **ready** → **running**
→ **blocked** (waiting for a signal) → **terminated**.

### Tool Manuals

A key feature of S-ORA is that agents **must read a tool's manual before using it**.
Manuals complement standard MCP tool descriptions with six sections:

1. Tool Metadata (category, version, ID)
2. Functional Description
3. Observable Properties (persistent state exposed via telemetry)
4. Signals (transient asynchronous events emitted by the tool)
5. Operations (preconditions, effects, payloads)
6. Usage Protocols & Safety (step-by-step operating instructions and safety constraints)

This forces situated reasoning: the agent cannot guess which button to press or which
sequence to follow — it must learn from the manual first.

### Demo 1 — Reactor in Detail

The `water-hammer.js` MCP server simulates a reactor whose system variables
(`hydraulic_pressure`, `core_temp`, etc.) evolve through a physics loop once activated.
The agent faces four compounding challenges:

1. **Distractors** — the manual catalogue contains two irrelevant manuals
   (`cafeteria_menu`, `cooling_tower_maintenance`) alongside three operational ones
   (`hydraulics`, `reactor_operations`, `employee_handbook`).
2. **Authentication** — credentials must be retrieved from the `employee_handbook` and
   used via a `security_terminal` tool (not mentioned in the goal).
3. **Timing trap** — opening the valve while pressure is still building (state `RAMPING`)
   triggers an irreversible water-hammer lockout. The agent must subscribe to the
   `hydraulic_control` tool and wait for the `pump.pressure_nominal` SSE signal before
   invoking `open_valve`.
4. **Affordance trap** — the `reactor_core` interface exposes four unlabelled buttons;
   only one initiates the coolant flush. The correct mapping is encoded exclusively in
   the `reactor_operations` manual.

The S-ORA agent completes the task consistently with `gpt-4o-mini` at temperature 0.0,
suspending after `power_on_pump` (zero reasoning tokens consumed while the physics
simulation runs) and resuming automatically on `pump.pressure_nominal`, then again
suspending until a `core.stabilized` signal confirms success.

### Demo 2 — Collaborative Agents in Detail

The `shared-counter.js` MCP server exposes a single integer counter (initialised to 1)
and broadcasts a `counter.change` SSE event on every increment.
Both agents subscribe to the same event stream.

- **Agent EVEN**, observing an odd initial value of 1, suspends and waits.
- **Agent ODD** increments to 2, triggering `counter.change` and waking EVEN.
- **Agent EVEN** increments to 3, waking ODD.
- This reactive alternation continues until both agents independently recognise the
  terminal condition (`> 5`) during their Observe phase.

The result is correct alternating coordination (1 → 2 → 3 → 4 → 5 → 6) without polling
and without any direct agent-to-agent messaging — a pattern that scales naturally to
larger numbers of agents sharing tools.

## Final Remarks

This implementation has two known limitations compared to the full S-ORA architecture
described in the paper:

- **Multi-agent messaging** is not yet implemented; agents receive their initial goals
  programmatically.
- **Focus/unfocus actions** are implicit: agents automatically subscribe to every tool
  they use, rather than making an explicit decision to focus.

Both are identified as future work in the paper.
