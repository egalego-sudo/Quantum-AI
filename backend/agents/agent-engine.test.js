import test from "node:test";
import assert from "node:assert/strict";
import {
  AgentController,
  AgentTestingFramework,
  AutonomousExecutionLoop,
  MultiAgentCommunication,
  SelfCorrectionLoop,
  SharedMemory,
  TaskPlanningEngine,
  ToolCallingEngine
} from "./agent-engine.js";

test("planner orders dependencies and rejects cycles", () => {
  const planner = new TaskPlanningEngine();
  const plan = planner.plan([
    { id: "second", agent: "worker", description: "second", dependsOn: ["first"] },
    { id: "first", agent: "worker", description: "first" }
  ]);
  assert.deepEqual(plan.map(task => task.id), ["first", "second"]);
  assert.throws(() => planner.plan([
    { id: "a", agent: "worker", description: "A", dependsOn: ["b"] },
    { id: "b", agent: "worker", description: "B", dependsOn: ["a"] }
  ]), /cycle/i);
});

test("planner rejects unknown dependencies and duplicate IDs", () => {
  const planner = new TaskPlanningEngine();
  assert.throws(() => planner.plan([{ id: "a", agent: "worker", description: "A", dependsOn: ["missing"] }]), /Unknown dependency/);
  assert.throws(() => planner.plan([
    { id: "a", agent: "worker", description: "A" },
    { id: "a", agent: "worker", description: "B" }
  ]), /unique ID/);
});

test("tool engine only calls registered tools and enforces approval", async () => {
  const tools = new ToolCallingEngine();
  tools.register("safe.read", async input => ({ value: input.value }), { inputSchema: input => typeof input.value === "string" });
  tools.register("danger.write", async () => "written", { requiresApproval: true });
  assert.deepEqual(await tools.call("safe.read", { value: "ok" }), { value: "ok" });
  await assert.rejects(() => tools.call("danger.write", {}), error => error.code === "APPROVAL_REQUIRED");
  assert.equal(await tools.call("danger.write", {}, { approved: true }), "written");
  await assert.rejects(() => tools.call("node.shell", {}), /not allow-listed/);
  await assert.rejects(() => tools.call("safe.read", { value: 1 }), /validation/);
});

test("shared memory is namespaced, replaceable and bounded", () => {
  const memory = new SharedMemory({ maxEntries: 2 });
  memory.remember({ key: "profile", namespace: "user", value: { language: "hi" }, tags: ["pref"] });
  memory.remember({ key: "profile", namespace: "project", value: { name: "Quantum AI" } });
  assert.deepEqual(memory.recall({ key: "profile", namespace: "user" }).value, { language: "hi" });
  memory.remember({ key: "profile", namespace: "user", value: { language: "en" } });
  assert.deepEqual(memory.recall({ key: "profile", namespace: "user" }).value, { language: "en" });
  memory.remember({ key: "latest", value: "bounded" });
  assert.equal(memory.snapshot().length, 2);
  // The project entry was the oldest and must have been evicted by the bound.
  assert.equal(memory.recall({ key: "profile", namespace: "project" }), null);
  assert.equal(memory.forget({ key: "profile", namespace: "project" }), false);
  // A still-retained entry can be explicitly forgotten.
  assert.equal(memory.forget({ key: "profile", namespace: "user" }), true);
});

test("message bus delivers cloned messages and rejects unknown recipients", async () => {
  const bus = new MultiAgentCommunication();
  bus.register("critic", async message => ({ accepted: message.payload.ok }));
  const result = await bus.send({ from: "planner", to: "critic", payload: { ok: true } });
  assert.deepEqual(result.result, { accepted: true });
  await assert.rejects(() => bus.send({ from: "planner", to: "missing", payload: {} }), /Unknown recipient/);
});

test("execution loop runs dependency order and reports truthful task states", async () => {
  const planner = new TaskPlanningEngine();
  const plan = planner.plan([
    { id: "b", agent: "worker", description: "B", dependsOn: ["a"] },
    { id: "a", agent: "worker", description: "A" }
  ]);
  const loop = new AutonomousExecutionLoop();
  const result = await loop.run(plan, { executeTask: async task => task.id.toUpperCase() });
  assert.equal(result.summary.completed, 2);
  assert.deepEqual(result.tasks.map(task => task.result), ["A", "B"]);
});

test("execution loop blocks downstream tasks after dependency failure", async () => {
  const plan = new TaskPlanningEngine().plan([
    { id: "a", agent: "worker", description: "A" },
    { id: "b", agent: "worker", description: "B", dependsOn: ["a"] }
  ]);
  const result = await new AutonomousExecutionLoop().run(plan, {
    maxRetriesPerTask: 0,
    executeTask: async () => { throw new Error("root failure"); }
  });
  assert.equal(result.summary.failed, 1);
  assert.equal(result.summary.blocked, 1);
});

test("self-correction retries only through the injected repair callback", async () => {
  const loop = new SelfCorrectionLoop({ maxRepairs: 1 });
  let calls = 0;
  const result = await loop.run({
    task: { value: 0 },
    execute: async task => {
      calls++;
      if (task.value !== 1) throw new Error("bad value");
      return "ok";
    },
    repair: async ({ task }) => ({ ...task, value: 1 }),
    validate: output => output === "ok"
  });
  assert.equal(result.status, "completed");
  assert.equal(calls, 2);
  assert.equal(result.history.length, 1);
});

test("controller wires registered agents to planning, execution, tools and memory", async () => {
  const controller = new AgentController();
  controller.tools.register("sum", async ({ a, b }) => a + b);
  controller.registerAgent("worker", async ({ kind, task }, context) => {
    if (kind === "test") return "ready";
    context.memory.remember({ key: task.id, value: task.description, namespace: "tasks" });
    return await context.tools.call("sum", { a: 2, b: 3 });
  }, { description: "Test worker", capabilities: ["compute"] });
  const result = await controller.run([{ id: "one", agent: "worker", description: "calculate" }]);
  assert.equal(result.summary.completed, 1);
  assert.equal(result.tasks[0].result, 5);
  assert.equal(controller.memory.recall({ key: "one", namespace: "tasks" }).value, "calculate");
  const tests = await new AgentTestingFramework(controller).runCases([{ name: "worker contract", agent: "worker", input: {}, expected: "ready" }]);
  assert.equal(tests.passed, 1);
});

test("execution loop enforces task timeout even when a handler ignores AbortSignal", async () => {
  const plan = new TaskPlanningEngine().plan([
    { id: "slow", agent: "worker", description: "slow task" }
  ]);
  const result = await new AutonomousExecutionLoop({ maxTaskTimeoutMs: 40 }).run(plan, {
    maxRetriesPerTask: 0,
    executeTask: async () => await new Promise(() => {})
  });
  assert.equal(result.summary.failed, 1);
  assert.match(result.tasks[0].error, /timed out/i);
});

test("execution loop runs explicitly parallel-safe tasks concurrently", async () => {
  const plan = new TaskPlanningEngine().plan([
    { id: "a", agent: "worker", description: "A", parallelSafe: true },
    { id: "b", agent: "worker", description: "B", parallelSafe: true }
  ]);
  let active = 0;
  let peak = 0;
  const result = await new AutonomousExecutionLoop().run(plan, {
    executeTask: async task => {
      active++;
      peak = Math.max(peak, active);
      await new Promise(resolve => setTimeout(resolve, 20));
      active--;
      return task.id;
    }
  });
  assert.equal(result.summary.completed, 2);
  assert.equal(peak, 2);
});
