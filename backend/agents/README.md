# QUANTUM AI Agent Engine — Foundation

This directory contains the first isolated implementation of the QUANTUM AI agent-orchestration foundation.

## Modules present

- `AgentController`: registers named agent handlers and coordinates task execution.
- `TaskPlanningEngine`: validates task graphs, dependencies, duplicate IDs and cycles.
- `ToolCallingEngine`: explicit allow-list for injected tools; sensitive tools can require explicit approval.
- `AutonomousExecutionLoop`: bounded task execution, dependency handling, retry limits, cancellation and event reporting.
- `MultiAgentCommunication`: bounded in-process message bus for registered agents.
- `SharedMemory`: bounded in-memory namespaced records. Persistence is not enabled by this module.
- `SelfCorrectionLoop`: bounded retry/repair callback flow; it does not edit source files by itself.
- `AgentTestingFramework`: contract-test runner for registered agent handlers.

## Run contract tests

From the `backend/` directory:

```sh
node --test agents/agent-engine.test.js
```

The isolated contract suite has been executed by GitHub Actions: 11 tests passed, 0 failed. See [Agent Engine CI](https://github.com/egalego-sudo/Quantum-AI/actions/runs/44891336403). This is not evidence of end-to-end autonomous behavior.

## Security boundaries

- No arbitrary shell execution, dynamic code evaluation, unrestricted file access or general-purpose URL fetching is exposed.
- Tools must be explicitly registered by the host backend.
- Tools marked `requiresApproval` reject calls unless the host supplies `approved: true`.
- The execution loop has task-count/step/retry/time bounds.
- Memory and message history are bounded and in-process only.
- Production actions, device settings, deployments, payments, destructive writes and external app operations must remain behind explicit user approval in the host application.

## Not yet integrated / not verified

This is an isolated foundation, not yet wired to the Android Action Core, model-backed planning, a persistent database, external app connectors or a production API route. The 11 passing tests cover this module's contract behavior only. Autonomous multi-agent operation is not complete.

Next integration stages:
1. Add a schema-validated, authenticated agent-plan API route.
2. Connect model-produced plans only after deterministic schema validation.
3. Add durable, opt-in memory with retention and deletion controls.
4. Add app-side plan preview, progress display and confirmation gates.
5. Add CI, security, load and Android integration tests.
