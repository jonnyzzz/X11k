# Agent Reliability Notes

The X server implementation loop must not run unbounded agent waits. When a run appears stuck, diagnose first, then restart.

## Current Failure Mode

Recent stuck runs were not JVM deadlocks in the X server. The local checks showed:

- no active X server test JVM under `jps`;
- no active `runs/*/pid.txt` agent jobs;
- recent Codex `run-agent.sh` review runs ended with `EXIT_CODE=143`;
- those Codex logs were waiting inside nested collaboration/subagent calls.

The common trigger was review quorum enforcement inside a `codex exec` run. The nested subagent wait is invisible to `run-agent.sh`, so the outer runner only sees a child process that keeps running until manually terminated.

The 2026-06-30 recurrence had a second trigger: built-in subagent lifecycle tools can also block outside the shell timeout model. A bounded `wait_agent` on an old subagent returned no status, but a later `close_agent` call for that same non-responsive agent did not return. Because it was issued inside a parallel tool wrapper, the whole root turn stayed blocked even though the other completed agents closed successfully.

A later 2026-06-30 stall was a different orchestration failure, not an X server hang:

- `jps -lm` showed no active X server test JVM;
- a `run-agent.sh claude ...` child had zero stdout/stderr bytes for multiple minutes;
- `sample <pid>` showed the Claude CLI idle in its event loop / network wait;
- `pid.txt` pointed at a bash subshell instead of the real agent process, which weakened timeout diagnostics and termination.

`run-agent.sh` now `exec`s the agent from the run-directory child process, so `PID=` is the real agent PID. It also writes `heartbeat.txt` while the agent runs and emits one early diagnostics file after a fully silent period. Use `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS` only when a job is expected to print promptly; many `claude -p --output-format text` runs legitimately stay silent until their final answer.

An earlier repeat of this pattern was caused by setting `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300` on a long `claude -p --output-format text` implementation/review prompt. The runner killed the agent after five silent minutes even though text-mode Claude commonly writes no stdout/stderr until completion. That exception later became its own failure mode: Claude review/scout jobs could sit silent with no effective output-idle kill. The runner now keeps output-idle termination enabled for Claude by default; set `RUN_AGENT_CLAUDE_DISABLE_TEXT_NO_OUTPUT_TIMEOUT=1` only for a deliberately long Claude text-mode run where waiting until the wall-clock timeout is acceptable.

The 2026-06-30 20:39Z recurrence was another orchestration stall, not an X server hang:

- the heavyweight `IntellijCommunitySmokeTest` passed immediately before the agent stall;
- `jps -lm` showed no active X server test JVM after the smoke finished;
- `run-agent.sh claude ...` stayed at zero stdout/stderr bytes past the 180-second diagnostics point;
- diagnostics showed the Claude process had spawned an MCP Steroid stdio child whose Java thread dump was idle in `McpStdioServer.readChunk` waiting on stdin;
- the scout was terminated manually with `EXIT_CODE=143` after diagnostics were captured.

Root cause: read-only run-agent research prompts inherited "use MCP Steroid where possible" guidance and could enter an MCP/stdin wait that is invisible as useful progress to the outer runner. For run-agent scouts and reviews, prefer shell/read-only source searches (`rg`, `sed`, `git`, bounded Gradle only when requested). Use MCP Steroid from a run-agent only when the prompt explicitly needs IDE semantic APIs, and then keep the wall-clock timeout plus no-output diagnostics enabled.

`run-agent.sh` now prepends a short reliability override to copied prompts when this file is present. That override supersedes older broad "prefer MCP Steroid" role prompts for run-agent work, while still allowing explicit IDE-semantic tasks to opt into MCP Steroid. Set `RUN_AGENT_RELIABILITY_PREAMBLE=0` only for an intentionally isolated run that must receive the prompt byte-for-byte.

Claude run-agents also default to `--safe-mode` (`RUN_AGENT_CLAUDE_SAFE_MODE=1`), because `claude -p --tools default` can spawn configured MCP stdio servers before the prompt text has any effect. Disable safe mode only for a run that explicitly needs Claude plugins/hooks/MCP configuration and keep the usual wall-clock plus no-output diagnostics.

Codex run-agents also default to an isolated config overlay (`RUN_AGENT_CODEX_ISOLATED=1`): the runner keeps the configured provider/auth path, but passes `-c 'mcp_servers={}' -c 'features.hooks=false' -c 'plugins={}'`. A 2026-06-30 bounded read-only scout reproduced the MCP/stdin wait pattern with plain `codex exec`: despite prompt text forbidding MCP use, the Codex process loaded configured MCP servers and spawned an MCP Steroid Java child that sat in `McpStdioServer.readChunk` waiting on stdin until the runner wall-clock timeout fired. `--ignore-user-config` avoided MCP but broke this local provider/auth setup with 401s, so use the narrower overlay. Disable config isolation only for a run that explicitly needs user-level Codex MCP/plugins/hooks.

The overlay alone was still insufficient in this local Codex CLI configuration: a 2026-06-30 read-only Codex scout launched `mcp-steroid` even with `-c mcp_servers={}` and then hit the 120-second wall-clock timeout. `run-agent.sh` now creates a per-run isolated `CODEX_HOME` for Codex jobs, copying only top-level model settings, `[model_providers]`, and `[projects]` trust entries from the user config. It intentionally omits MCP servers, plugins, hooks, and bundled marketplaces. The runner still passes the old `-c` disables as a belt-and-braces guard.

Timeout and stale-agent recovery also terminate the full descendant process tree now. Earlier versions killed only the top-level agent PID, so helper JVMs or MCP stdio children could survive the parent timeout and make later "stuck" diagnosis noisier.

The 2026-07-01 Gemini scout stall was a runner progress-accounting bug layered on top of another idle agent CLI. The scout printed a few startup lines, then `watch-agents.sh` showed output ages growing past five minutes; `sample <pid>` showed Node idle in `uv__io_poll`/`kevent`, and `jps -lv` showed no active X server/test JVM. `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300` did not fire because the runner only checked whether total output bytes were zero, not whether new bytes had appeared since the last poll. `run-agent.sh` now tracks the last output-size change, writes `OUTPUT_IDLE_SECONDS` to `heartbeat.txt`, and applies no-output diagnostics/timeouts to "no new bytes" after any earlier chatter.

The 2026-07-02 recurrence exposed a root-agent tooling failure rather than a repo runner failure. A bounded `wait_agent` returned completed results for several old built-in subagents, but a later `close_agent` call against a non-responsive old subagent blocked the whole root turn for more than an hour. Do not use built-in subagent lifecycle tools as part of the Ralph loop. For repo work, start agents only through `run-agent.sh`, and recover them only through `watch-agents.sh` so every timeout has persisted stdout/stderr, `run-info.txt`, heartbeat state, process lists, and JVM thread dumps.

The later 2026-07-02 repeat showed that recovery itself also needs a hard boundary. `ralph-loop.sh` now defaults `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300`, wraps its stale-agent recovery pulse in `RUN_AGENT_RECOVER_TIMEOUT_SECONDS=180`, and `watch-agents.sh` restarts inherit a 300-second output-idle kill threshold. The kill path still writes diagnostics before termination; the practical change is that restarted or newly launched agents no longer return to an unbounded silent wait by default.

The follow-up recurrence showed one remaining escape hatch: direct `./run-agent.sh ...` invocations still inherited the upstream one-hour wall-clock default and disabled output-idle termination unless the caller set overrides. Direct runner calls now use the same operational defaults as `ralph-loop.sh`: 900 seconds wall clock, diagnostics after 180 seconds without new stdout/stderr bytes, and termination after 300 output-idle seconds. Claude text-mode uses the same output-idle kill by default; disable it only with `RUN_AGENT_CLAUDE_DISABLE_TEXT_NO_OUTPUT_TIMEOUT=1`.

The next recurrence risk was direct-runner preflight recovery itself. `run-agent.sh` started `watch-agents.sh` before the main run timeout loop existed, so a stuck watcher diagnostic/restart pulse could block the launch without producing a new run heartbeat. Direct preflight recovery is now bounded by `RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS` (default 180 seconds), and `ralph-loop.sh` passes its bounded recovery timeout through to direct-runner preflight.

The 2026-07-03 recurrence had two orchestration causes. First, a Claude review inherited the old text-mode exception and recorded `EFFECTIVE_NO_OUTPUT_TIMEOUT_SECONDS=0`; diagnostics showed a spawned MCP Steroid stdio JVM waiting in `McpStdioServer.readChunk`, with no active X server test JVM. Second, one-shot `watch-agents.sh` restarts launched `run-agent.sh` in a background subshell tied to the watcher process, so a restarted agent could disappear with a stale `pid.txt` and no `EXIT_CODE`. `run-agent.sh` now keeps Claude output-idle termination enabled by default, and `watch-agents.sh` restarts through a detached `setsid`/POSIX-Perl launcher while cleaning `pid.txt` for finished PIDs.

The 2026-07-03 review pass exposed another non-hang failure mode: an agent launched two Gradle test commands concurrently while reviewing one staged diff. Both commands used the same project build directory and binary test-result store, producing `EOFException` and missing `in-progress-results-generic.bin` failures despite the implementation being correct. Parallel shell reads are fine, but project builds, tests, package tasks, IDE builds, and any command that writes under `build/` or `.gradle/` must be serialized.

The current root-side adjustment is `scripts/run-gradle-bounded.sh`. It serializes Gradle with a repository lock, always uses `--no-daemon --max-workers=1 -Dkotlin.incremental=false`, enforces a wall-clock timeout, and writes `jps` plus Java thread dumps before terminating the Gradle process tree. Use it for routine local verification instead of bare `./gradlew` unless a one-off experiment explicitly needs different Gradle flags.

The 2026-07-04 recurrence showed the Gradle wrapper still lagged behind the non-Gradle experiment wrapper: it could collect diagnostics on wall-clock timeout, but a quiet Gradle/Testcontainers/IDE phase had no persisted output files or output-idle kill path. `scripts/run-gradle-bounded.sh` now writes one run directory per invocation under `runs/gradle-bounded/`, mirrors stdout/stderr into files, updates `heartbeat.txt` for monitored long-running invocations, emits diagnostics after `GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS` without new bytes, and terminates with a second diagnostic bundle after `GRADLE_NO_OUTPUT_TIMEOUT_SECONDS`. This makes Gradle verification stalls self-diagnosing instead of depending on manual `jps` / `jstack` checks.

For any non-Gradle experiment that can block on Docker, X clients, IDE startup, network downloads, or JVM subprocesses, use `scripts/run-bounded-experiment.sh -- <command> [args...]`. It creates a per-run directory under `runs/bounded-experiments/`, records stdout/stderr/run-info/heartbeat files, emits diagnostics after output-idle time, and captures process tree, `jps`, `jcmd`/`jstack`, Docker/Testcontainers state, and output tails before killing a timed-out process tree. This wrapper is the default for ad hoc repro commands; Gradle still goes through `scripts/run-gradle-bounded.sh`.

The 2026-07-04 follow-up exposed one more stale-run blind spot: a runner can be interrupted after writing `run-info.txt` but before writing `EXIT_CODE` or while `pid.txt` has already been removed. Older watcher output reported those directories as `unknown (no pid/exit)` forever, which made status checks look stuck even when the recorded PID had long exited. `watch-agents.sh` now recovers a missing `pid.txt` from `PID=` in `run-info.txt` when that process is still alive, so stale diagnostics and terminate/restart still work. If the recorded PID is gone, or there is no PID at all, the watcher waits only `RUN_AGENT_ABANDONED_SECONDS` (default 120) before appending `WATCH_ABANDONED_UTC` / `WATCH_ABANDONED_REASON` to `run-info.txt` and reporting the run as abandoned.

The 2026-07-04 root-side workflow is now wrapper-first. Local implementation, review, and test prompts tell agents to follow this file and to use `scripts/run-gradle-bounded.sh` or `scripts/run-bounded-experiment.sh` instead of bare build/test commands. The agent runner reliability preamble says the same thing, so new agents inherit the policy even when older generic prompt text still mentions MCP Steroid for builds. `run-agent.sh`, `scripts/run-gradle-bounded.sh`, and `scripts/run-bounded-experiment.sh` maintain a `latest` symlink in their run directories, making the first diagnostic target stable: `runs/latest`, `runs/gradle-bounded/latest`, or `runs/bounded-experiments/latest`.

One more local visibility failure was that `scripts/run-bounded-experiment.sh` persisted stdout/stderr to files but did not mirror them to the terminal. That made long but healthy Docker/IDE experiments look silent to the root agent until completion. The wrapper now mirrors output by default while keeping the persisted files and diagnostics; set `EXPERIMENT_MIRROR_OUTPUT=0` only when a deliberately quiet run is more useful. External TERM/INT signals also trigger a diagnostic bundle before the child process tree is terminated.

## Required Practice

- Start long commands through `scripts/run-bounded-experiment.sh`, `timeout`, `scripts/run-gradle-bounded.sh`, or with `RUN_AGENT_TIMEOUT_SECONDS` set.
- Never run Gradle, Maven, IDE build, test, package, or other build-directory-writing commands in parallel for this repository. Queue them one at a time, using `--no-daemon --max-workers=1 -Dkotlin.incremental=false` for Gradle checks unless a task explicitly needs different settings.
- Prefer `scripts/run-gradle-bounded.sh <tasks...>` for Gradle checks. It holds the repo Gradle lock and captures JVM diagnostics before killing a timed-out run.
- Treat `runs/gradle-bounded/run_*/heartbeat.txt`, `run-info.txt`, `stdout.txt`, and `stderr.txt` as the first stop for a suspected Gradle stall. `GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS` controls the first diagnostic snapshot, and `GRADLE_NO_OUTPUT_TIMEOUT_SECONDS` controls the automatic kill.
- For the most recent Gradle stall, start with `runs/gradle-bounded/latest/run-info.txt`; for the most recent non-Gradle experiment, start with `runs/bounded-experiments/latest/run-info.txt`; for the most recent run-agent, start with `runs/latest/run-info.txt`.
- Prefer `scripts/run-bounded-experiment.sh -- <command> [args...]` for ad hoc non-Gradle repros and experiments so timeout failures leave a persisted diagnostic bundle. Its default `EXPERIMENT_MIRROR_OUTPUT=1` also streams child stdout/stderr to the current terminal, which makes healthy long experiments visibly active while still preserving `stdout.txt` and `stderr.txt`.
- Before killing a suspected stuck JVM workload, collect `jps -lm` plus `jcmd <pid> Thread.print` or `jstack <pid>`.
- Before restarting a silent run-agent, inspect its `heartbeat.txt`, `run-info.txt`, any `DIAGNOSTICS=...` entries, and stdout/stderr sizes.
- For agents that print startup text and then may idle, trust `OUTPUT_IDLE_SECONDS`, not total output size.
- To answer "ping agents" without entering an unbounded monitor loop, run:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_WATCH_LIMIT=20 ./watch-agents.sh
  ```

- To diagnose stale active agents, including JVM thread dumps, without killing them, run:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 ./watch-agents.sh
  ```

- To restart genuinely stale active agents, diagnose first and keep termination/restart opt-in:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 RUN_AGENT_RESTART_STALE=1 ./watch-agents.sh
  ```

  Destructive recovery flags intentionally require `RUN_AGENT_WATCH_ONCE=1`; do not run terminate/restart in the periodic watcher loop.

- Keep routine run monitoring bounded to recent runs or active PID files. The local `runs/` tree is large enough that whole-history scans can time out.
- Treat `abandoned` watcher lines as completed forensic records, not live agents. Inspect their `run-info.txt` and diagnostics if needed, but do not wait for them; the watcher has confirmed there is no live PID to recover.
- Do not let run-agents spawn additional unbounded review subagents. Quorum reviews should be scheduled by the root agent with explicit timeouts, or replaced by a bounded local review for trivial changes.
- Do not ask run-agent research/review scouts to use MCP Steroid by default. Shell-based inspection is enough for most gap selection and avoids MCP stdio waits inside a silent text-mode agent.
- When a run times out, inspect the generated `DIAGNOSTICS=...` file in `run-info.txt` before retrying. Agent and watcher diagnostics now include Docker/Testcontainers state for `jonnyzzz-x` client/reference containers and Ryuk, so use those sections to distinguish container download/startup/extract work from JVM or X server deadlocks.
- On macOS, install GNU coreutils or make sure `gtimeout` is available if you need hard time limits around watcher diagnostics. Without either `timeout` or `gtimeout`, the watcher still runs but cannot bound individual `jps`/`jcmd`/`jstack` calls.
- Treat built-in subagents as scarce stateful resources. After a bounded `wait_agent`, close only agents that have returned a final status, and close them individually. Do not call `close_agent` for a non-responsive agent and do not wrap `close_agent` calls in a parallel tool batch; one blocked close can stall the whole root agent.
- Run a one-shot diagnostic/recovery watcher before every new implementation/review batch. This is now the default `run-agent.sh` preflight (`RUN_AGENT_PREFLIGHT_RECOVER_STALE=1`); use the explicit command when recovering outside a new agent launch:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 RUN_AGENT_RESTART_STALE=1 ./watch-agents.sh
  ```

- Use `./ralph-loop.sh <role>` for routine research/implementation/review/test agent work. It runs the recovery watcher before the agent, applies the standard wall-clock/no-output diagnostics settings, and wraps `run-agent.sh` in an outer process timeout.
- Keep direct-runner preflight recovery bounded with `RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS` (default 180 seconds). If that fires, inspect `runs/agent-watch.log` before retrying.

## Runner Timeout Knobs

Use these defaults unless a specific run justifies changing them:

```bash
RUN_AGENT_TIMEOUT_SECONDS=900 \
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=180 \
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300 \
timeout 990 ./ralph-loop.sh review codex
```

Use this shape for repository Gradle checks:

```bash
GRADLE_TIMEOUT_SECONDS=1800 \
GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS=300 \
GRADLE_NO_OUTPUT_TIMEOUT_SECONDS=900 \
scripts/run-gradle-bounded.sh test --console=plain
```

Use this shape for non-Gradle experiments:

```bash
EXPERIMENT_TIMEOUT_SECONDS=900 \
EXPERIMENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=180 \
EXPERIMENT_NO_OUTPUT_TIMEOUT_SECONDS=300 \
scripts/run-bounded-experiment.sh -- sh -lc 'your repro command here'
```

`scripts/run-gradle-bounded.sh` also captures Docker/Testcontainers state on timeout and removes stopped/dead Testcontainers before starting. It removes still-running Testcontainers only after `GRADLE_STALE_TESTCONTAINERS_SECONDS` (default 3600), which keeps normal heavyweight IntelliJ/VSCode runs intact while clearing leaks from killed runs.

For short scout prompts that should either answer quickly or fail with evidence, keep or lower:

```bash
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300
```

For a deliberately long Claude text-mode run where no-output termination should be disabled:

```bash
RUN_AGENT_CLAUDE_DISABLE_TEXT_NO_OUTPUT_TIMEOUT=1 \
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300
```
