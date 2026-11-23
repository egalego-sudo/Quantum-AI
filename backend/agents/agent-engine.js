import { randomUUID } from "node:crypto";

const DEFAULT_LIMITS = Object.freeze({
  maxTasks: 40,
  maxSteps: 80,
  maxRetriesPerTask: 2,
  maxTaskAttempts: 3,
  maxTaskTimeoutMs: 30_000,
  maxMemoryEntries: 500,
  maxMessageBytes: 16_384
});

const TERMINAL_TASK_STATES = new Set(["completed", "failed", "blocked", "cancelled"]);
const SAFE_TOOL_NAME = /^[a-z][a-z0-9_.-]{1,63}$/;

function boundedText(value, max = 4_000) {
  return String(value ?? "").trim().slice(0, max);
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function nowIso() {
  return new Date().toISOString();
}

function makeId(prefix) {
  return prefix + "_" + randomUUID();
}

function assertPlainObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(label + " must be an object.");
  }
}

/**
 * In-memory, bounded, namespaced memory. Persistence adapters can be injected
 * by the host application; this class never writes to disk by itself.
 */
export class SharedMemory {
  #entries = [];
  #maxEntries;

  constructor({ maxEntries = DEFAULT_LIMITS.maxMemoryEntries } = {}) {
    this.#maxEntries = Math.max(1, Math.min(10_000, Math.floor(maxEntries)));
  }

  remember({ key, value, namespace = "default", tags = [] }) {
    const safeKey = boundedText(key, 160);
    const safeNamespace = boundedText(namespace, 80);
    if (!safeKey || !safeNamespace) throw new TypeError("Memory key and namespace are required.");
    const entry = {
      id: makeId("mem"),
      key: safeKey,
      namespace: safeNamespace,
      value: clone(value),
      tags: Array.isArray(tags) ? tags.slice(0, 20).map(tag => boundedText(tag, 50)) : [],
      updatedAt: nowIso()
    };
    const existing = this.#entries.findIndex(item => item.namespace === safeNamespace && item.key === safeKey);
    if (existing >= 0) this.#entries.splice(existing, 1);
    this.#entries.push(entry);
    if (this.#entries.length > this.#maxEntries) this.#entries.splice(0, this.#entries.length - this.#maxEntries);
    return clone(entry);
  }

  recall({ key, namespace = "default" } = {}) {
    const entry = this.#entries.find(item => item.namespace === namespace && item.key === key);
    return entry ? clone(entry) : null;
  }

  search({ namespace, tag, limit = 20 } = {}) {
    const safeLimit = Math.max(1, Math.min(100, Math.floor(limit)));
    return this.#entries.filter(entry =>
      (!namespace || entry.namespace === namespace) &&
      (!tag || entry.tags.includes(tag))
    ).slice(-safeLimit).reverse().map(clone);
  }

  forget({ key, namespace = "default" }) {
    const before = this.#entries.length;
    this.#entries = this.#entries.filter(item => !(item.key === key && item.namespace === namespace));
    return before !== this.#entries.length;
  }

  snapshot() {
    return this.#entries.map(clone);
  }
}

/**
 * Bounded message bus. Messages are data only; they are never evaluated.
 */
export class MultiAgentCommunication {
  #handlers = new Map();
  #messages = [];
  #maxMessageBytes;

  constructor({ maxMessageBytes = DEFAULT_LIMITS.maxMessageBytes } = {}) {
    this.#maxMessageBytes = Math.max(256, Math.min(65_536, maxMessageBytes));
  }

  register(agentId, handler) {
    const id = boundedText(agentId, 80);
    if (!id || typeof handler !== "function") throw new TypeError("Agent ID and handler are required.");
    if (this.#handlers.has(id)) throw new Error("Agent is already registered: " + id);
    this.#handlers.set(id, handler);
    return () => this.#handlers.delete(id);
  }

  async send({ from, to, type = "task", payload }) {
    const target = this.#handlers.get(to);
    if (!target) throw new Error("Unknown recipient agent: " + to);
    const serialized = JSON.stringify(payload ?? null);
    if (Buffer.byteLength(serialized, "utf8") > this.#maxMessageBytes) {
      throw new Error("Agent message exceeds the configured size limit.");
    }
    const message = {
      id: makeId("msg"),
      from: boundedText(from, 80),
      to: boundedText(to, 80),
      type: boundedText(type, 40),
      payload: clone(payload ?? null),
      createdAt: nowIso()
    };
    this.#messages.push(message);
    if (this.#messages.length > 1_000) this.#messages.shift();
    const result = await target(clone(message));
    return { message: clone(message), result: clone(result) };
  }

  history({ limit = 50 } = {}) {
    return this.#messages.slice(-Math.max(1, Math.min(200, limit))).map(clone);
  }
}

/**
 * Explicit allow-list for tools. No shell, eval, arbitrary URL fetch, or
 * implicit file-system access is provided by this engine.
 */
export class ToolCallingEngine {
  #tools = new Map();

  register(name, handler, { description = "", inputSchema = null, requiresApproval = false } = {}) {
    if (!SAFE_TOOL_NAME.test(name) || typeof handler !== "function") {
      throw new TypeError("A valid tool name and handler are required.");
    }
    if (this.#tools.has(name)) throw new Error("Tool already registered: " + name);
    this.#tools.set(name, { handler, description: boundedText(description, 300), inputSchema, requiresApproval });
    return () => this.#tools.delete(name);
  }

  list() {
    return [...this.#tools.entries()].map(([name, tool]) => ({
      name, description: tool.description, requiresApproval: tool.requiresApproval
    }));
  }

  async call(name, input, { approved = false, signal } = {}) {
    const tool = this.#tools.get(name);
    if (!tool) throw new Error("Tool is not allow-listed: " + name);
    if (tool.requiresApproval && approved !== true) {
      const error = new Error("This tool requires explicit user approval.");
      error.code = "APPROVAL_REQUIRED";
      throw error;
    }
    if (signal?.aborted) throw new Error("Tool call cancelled.");
    assertPlainObject(input ?? {}, "Tool input");
    if (tool.inputSchema && typeof tool.inputSchema === "function" && !tool.inputSchema(input ?? {})) {
      throw new TypeError("Tool input failed validation.");
    }
    return await tool.handler(clone(input ?? {}), { signal });
  }
}

/**
 * Validates a dependency graph and creates a stable topological task order.
 * Tasks are data-only descriptions; task execution is delegated to registered
 * agent handlers by the controller.
 */
export class TaskPlanningEngine {
  constructor({ maxTasks = DEFAULT_LIMITS.maxTasks } = {}) {
    this.maxTasks = Math.max(1, Math.min(200, Math.floor(maxTasks)));
  }

  plan(tasks) {
    if (!Array.isArray(tasks) || tasks.length === 0 || tasks.length > this.maxTasks) {
      throw new TypeError("Plan must contain between 1 and " + this.maxTasks + " tasks.");
    }
    const ids = new Set();
    const normalized = tasks.map((task, index) => {
      assertPlainObject(task, "Task");
      const id = boundedText(task.id || "task-" + (index + 1), 80);
      const agent = boundedText(task.agent, 80);
      const description = boundedText(task.description, 1_000);
      if (!id || !agent || !description || ids.has(id)) throw new TypeError("Every task needs a unique ID, agent, and description.");
      ids.add(id);
      const dependsOn = Array.isArray(task.dependsOn) ? [...new Set(task.dependsOn.map(value => boundedText(value, 80)))] : [];
      return { id, agent, description, dependsOn, input: clone(task.input ?? {}), parallelSafe: task.parallelSafe === true, status: "pending", attempts: 0, result: null, error: null };
    });
    for (const task of normalized) {
      for (const dependency of task.dependsOn) {
        if (!ids.has(dependency)) throw new Error("Unknown dependency " + dependency + " for task " + task.id);
        if (dependency === task.id) throw new Error("Task cannot depend on itself: " + task.id);
      }
    }
    const byId = new Map(normalized.map(task => [task.id, task]));
    const indegree = new Map(normalized.map(task => [task.id, task.dependsOn.length]));
    const queue = normalized.filter(task => indegree.get(task.id) === 0).map(task => task.id);
    const ordered = [];
    while (queue.length) {
      const id = queue.shift();
      ordered.push(byId.get(id));
      for (const candidate of normalized) {
        if (candidate.dependsOn.includes(id)) {
          const next = indegree.get(candidate.id) - 1;
          indegree.set(candidate.id, next);
          if (next === 0) queue.push(candidate.id);
        }
      }
    }
    if (ordered.length !== normalized.length) throw new Error("Task plan contains a dependency cycle.");
    return ordered.map(clone);
  }
}

/**
 * Runs a task graph with bounded attempts, timeouts, cancellation and an audit
 * log. Parallelism is opt-in per task via parallelSafe=true.
 */
export class AutonomousExecutionLoop {
  constructor({ maxSteps = DEFAULT_LIMITS.maxSteps, maxTaskTimeoutMs = DEFAULT_LIMITS.maxTaskTimeoutMs } = {}) {
    this.maxSteps = Math.max(1, Math.min(500, Math.floor(maxSteps)));
    this.maxTaskTimeoutMs = Math.max(100, Math.min(120_000, Math.floor(maxTaskTimeoutMs)));
  }

  async run(plan, { executeTask, signal, maxRetriesPerTask = DEFAULT_LIMITS.maxRetriesPerTask, onEvent = () => {} } = {}) {
    if (!Array.isArray(plan) || typeof executeTask !== "function") throw new TypeError("A plan and executeTask handler are required.");
    const tasks = plan.map(clone);
    const events = [];
    let steps = 0;
    const emit = event => {
      const record = { ...event, at: nowIso() };
      events.push(record);
      onEvent(clone(record));
    };
    const retryLimit = Math.max(0, Math.min(5, Math.floor(maxRetriesPerTask)));
    const executeOne = async task => {
      if (signal?.aborted) { task.status = "cancelled"; return; }
      task.status = "running";
      while (task.attempts <= retryLimit) {
        if (++steps > this.maxSteps) {
          task.status = "blocked";
          task.error = "Execution step limit reached.";
          emit({ type: "blocked", taskId: task.id, reason: task.error });
          return;
        }
        task.attempts++;
        emit({ type: "task_started", taskId: task.id, attempt: task.attempts });
        const controller = new AbortController();
        const abortFromParent = () => controller.abort(signal?.reason);
        signal?.addEventListener("abort", abortFromParent, { once: true });
        const timeout = setTimeout(() => controller.abort(new Error("Task timed out.")), this.maxTaskTimeoutMs);
        try {
          const aborted = new Promise((_, reject) => {
            if (controller.signal.aborted) {
              reject(controller.signal.reason || new Error("Task cancelled or timed out."));
              return;
            }
            controller.signal.addEventListener("abort", () => {
              reject(controller.signal.reason || new Error("Task cancelled or timed out."));
            }, { once: true });
          });
          const result = await Promise.race([
            Promise.resolve().then(() => executeTask(clone(task), { signal: controller.signal, attempt: task.attempts })),
            aborted
          ]);
          task.result = clone(result);
          task.error = null;
          task.status = "completed";
          emit({ type: "task_completed", taskId: task.id, attempt: task.attempts });
          return;
        } catch (error) {
          task.error = boundedText(error?.message || error, 1_000);
          emit({ type: "task_error", taskId: task.id, attempt: task.attempts, error: task.error });
          if (signal?.aborted) { task.status = "cancelled"; return; }
          if (task.attempts > retryLimit) { task.status = "failed"; return; }
        } finally {
          clearTimeout(timeout);
          signal?.removeEventListener("abort", abortFromParent);
        }
      }
    };

    const pending = new Map(tasks.map(task => [task.id, task]));
    while (pending.size && steps < this.maxSteps) {
      if (signal?.aborted) {
        for (const task of pending.values()) task.status = "cancelled";
        break;
      }
      const ready = [...pending.values()].filter(task =>
        task.dependsOn.every(id => tasks.find(parent => parent.id === id)?.status === "completed")
      );
      const blocked = [...pending.values()].filter(task =>
        task.dependsOn.some(id => TERMINAL_TASK_STATES.has(tasks.find(parent => parent.id === id)?.status) &&
          tasks.find(parent => parent.id === id)?.status !== "completed")
      );
      for (const task of blocked) {
        task.status = "blocked";
        task.error = "A dependency did not complete.";
        pending.delete(task.id);
        emit({ type: "task_blocked", taskId: task.id, reason: task.error });
      }
      if (!ready.length) {
        if (pending.size) {
          for (const task of pending.values()) { task.status = "blocked"; task.error = "No runnable task remains."; }
          break;
        }
        continue;
      }
      const parallel = ready.filter(task => task.parallelSafe === true);
      const sequential = ready.filter(task => task.parallelSafe !== true);
      if (parallel.length) await Promise.all(parallel.map(executeOne));
      if (sequential.length) await executeOne(sequential[0]);
      for (const task of ready) if (TERMINAL_TASK_STATES.has(task.status)) pending.delete(task.id);
    }
    if (pending.size) for (const task of pending.values()) {
      if (!TERMINAL_TASK_STATES.has(task.status)) { task.status = "blocked"; task.error = "Execution step limit reached."; }
    }
    return {
      tasks: tasks.map(clone),
      events: events.map(clone),
      summary: {
        total: tasks.length,
        completed: tasks.filter(task => task.status === "completed").length,
        failed: tasks.filter(task => task.status === "failed").length,
        blocked: tasks.filter(task => task.status === "blocked").length,
        cancelled: tasks.filter(task => task.status === "cancelled").length,
        steps
      }
    };
  }
}

/**
 * Retries only through an injected repair strategy. The strategy must return
 * a corrected task; no source files are edited implicitly.
 */
export class SelfCorrectionLoop {
  constructor({ maxRepairs = 2 } = {}) {
    this.maxRepairs = Math.max(0, Math.min(5, Math.floor(maxRepairs)));
  }

  async run({ task, execute, repair, validate = () => true }) {
    if (typeof execute !== "function" || typeof repair !== "function") {
      throw new TypeError("execute and repair callbacks are required.");
    }
    let currentTask = clone(task);
    const history = [];
    for (let attempt = 0; attempt <= this.maxRepairs; attempt++) {
      try {
        const result = await execute(clone(currentTask));
        if (!await validate(result, currentTask)) throw new Error("Result failed validation.");
        return { status: "completed", task: currentTask, result: clone(result), history };
      } catch (error) {
        const failure = boundedText(error?.message || error, 1_000);
        history.push({ attempt: attempt + 1, error: failure });
        if (attempt === this.maxRepairs) break;
        const repaired = await repair({ task: clone(currentTask), error: failure, attempt: attempt + 1 });
        assertPlainObject(repaired, "Repaired task");
        currentTask = clone(repaired);
      }
    }
    return { status: "failed", task: currentTask, result: null, history };
  }
}

/**
 * Agent registry and orchestration boundary. Each agent is an explicitly
 * registered function; model calls and privileged tools must be injected.
 */
export class AgentController {
  constructor({ planner = new TaskPlanningEngine(), executor = new AutonomousExecutionLoop(), tools = new ToolCallingEngine(), memory = new SharedMemory(), communication = new MultiAgentCommunication() } = {}) {
    this.planner = planner;
    this.executor = executor;
    this.tools = tools;
    this.memory = memory;
    this.communication = communication;
    this.agents = new Map();
  }

  registerAgent(id, handler, { description = "", capabilities = [] } = {}) {
    const safeId = boundedText(id, 80);
    if (!safeId || typeof handler !== "function") throw new TypeError("Agent ID and handler are required.");
    if (this.agents.has(safeId)) throw new Error("Agent already registered: " + safeId);
    this.agents.set(safeId, { handler, description: boundedText(description, 300), capabilities: capabilities.slice(0, 30).map(value => boundedText(value, 60)) });
    const unregisterBus = this.communication.register(safeId, message => handler({ kind: "message", message }, { memory: this.memory, tools: this.tools }));
    return () => { this.agents.delete(safeId); unregisterBus(); };
  }

  listAgents() {
    return [...this.agents.entries()].map(([id, item]) => ({ id, description: item.description, capabilities: [...item.capabilities] }));
  }

  async run(tasks, options = {}) {
    const plan = this.planner.plan(tasks);
    return await this.executor.run(plan, {
      ...options,
      executeTask: async (task, context) => {
        const agent = this.agents.get(task.agent);
        if (!agent) throw new Error("No registered agent: " + task.agent);
        return await agent.handler({ kind: "task", task: clone(task) }, {
          ...context, memory: this.memory, tools: this.tools, communication: this.communication
        });
      }
    });
  }
}

/**
 * A small contract-test harness for registered agents. It does not pretend
 * to replace unit, integration, security or real-device tests.
 */
export class AgentTestingFramework {
  constructor(controller) {
    if (!(controller instanceof AgentController)) throw new TypeError("AgentTestingFramework needs an AgentController.");
    this.controller = controller;
  }

  async runCases(cases) {
    if (!Array.isArray(cases)) throw new TypeError("Test cases must be an array.");
    const results = [];
    for (const testCase of cases) {
      const startedAt = Date.now();
      try {
        const agent = this.controller.agents.get(testCase.agent);
        if (!agent) throw new Error("Unknown agent: " + testCase.agent);
        const output = await agent.handler({ kind: "test", input: clone(testCase.input ?? {}) }, {
          memory: this.controller.memory,
          tools: this.controller.tools,
          communication: this.controller.communication,
          testMode: true
        });
        const passed = typeof testCase.expect === "function"
          ? Boolean(await testCase.expect(output))
          : testCase.expected === undefined || JSON.stringify(output) === JSON.stringify(testCase.expected);
        results.push({ name: boundedText(testCase.name || testCase.agent, 120), status: passed ? "passed" : "failed", durationMs: Date.now() - startedAt, output: clone(output) });
      } catch (error) {
        results.push({ name: boundedText(testCase.name || testCase.agent, 120), status: "failed", durationMs: Date.now() - startedAt, error: boundedText(error?.message || error, 1_000) });
      }
    }
    return {
      total: results.length,
      passed: results.filter(item => item.status === "passed").length,
      failed: results.filter(item => item.status === "failed").length,
      results
    };
  }
}

export const AGENT_ENGINE_LIMITS = DEFAULT_LIMITS;
