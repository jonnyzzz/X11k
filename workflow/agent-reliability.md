# Agent Reliability Notes

The X server implementation loop must not run unbounded agent waits. When a run appears stuck, diagnose first, then restart.

The 2026-07-18 IntelliJ-cache review recurrence was bounded but wasteful: nine Codex reviewers each exhausted the 600-second wall limit without a verdict. Their logs showed broad recursive evidence searches through ignored `.gradle`, `build`, `.idea`, and historical `runs` trees; one `find .` spent more than four minutes traversing the persistent 836 MB IntelliJ cache. Review prompts must name the tracked diff and exact evidence files, prohibit recursive workspace/history/cache searches, set a small command budget, and require the verdict before the wall limit. Review agents should start with `git diff -- <tracked paths>` and targeted `sed`/`rg`; they must never use unpruned `find .`, search all of `runs`, or try to discover an IDE execution id recursively. A cited IDE execution id is evidence metadata, not a request to locate it on disk. The hard timeout remains the backstop, not the normal way a reviewer finishes.

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

The current root-side adjustment is `scripts/run-gradle-bounded.sh`. It serializes Gradle with a repository lock, always uses `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process`, enforces a wall-clock timeout, and writes `jps` plus Java thread dumps before terminating the Gradle process tree. The in-process Kotlin compiler default avoids a repeated stall mode where a separate `KotlinCompileDaemon` consumed CPU for more than 30 minutes while the Gradle client waited silently; override with `GRADLE_KOTLIN_COMPILER_EXECUTION_STRATEGY=<strategy>` or an explicit `-Dkotlin.compiler.execution.strategy=...` only for an intentional experiment. Use it for routine local verification instead of bare `./gradlew` unless a one-off experiment explicitly needs different Gradle flags.

The 2026-07-04 recurrence showed the Gradle wrapper still lagged behind the non-Gradle experiment wrapper: it could collect diagnostics on wall-clock timeout, but a quiet Gradle/Testcontainers/IDE phase had no persisted output files or output-idle kill path. `scripts/run-gradle-bounded.sh` now writes one run directory per invocation under `runs/gradle-bounded/`, mirrors stdout/stderr into files, updates `heartbeat.txt` for monitored long-running invocations, emits diagnostics after `GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS` without new bytes, and terminates with a second diagnostic bundle after `GRADLE_NO_OUTPUT_TIMEOUT_SECONDS`. This makes Gradle verification stalls self-diagnosing instead of depending on manual `jps` / `jstack` checks.

For any non-Gradle experiment that can block on Docker, X clients, IDE startup, network downloads, or JVM subprocesses, use `scripts/run-bounded-experiment.sh -- <command> [args...]`. It creates a per-run directory under `runs/bounded-experiments/`, records stdout/stderr/run-info/heartbeat files, emits diagnostics after output-idle time, and captures process tree, `jps`, `jcmd`/`jstack`, Docker/Testcontainers state, and output tails before killing a timed-out process tree. This wrapper is the default for ad hoc repro commands; Gradle still goes through `scripts/run-gradle-bounded.sh`.

The 2026-07-04 follow-up exposed one more stale-run blind spot: a runner can be interrupted after writing `run-info.txt` but before writing `EXIT_CODE` or while `pid.txt` has already been removed. Older watcher output reported those directories as `unknown (no pid/exit)` forever, which made status checks look stuck even when the recorded PID had long exited. `watch-agents.sh` now recovers a missing `pid.txt` from `PID=` in `run-info.txt` when that process is still alive, so stale diagnostics and terminate/restart still work. If the recorded PID is gone, or there is no PID at all, the watcher waits only `RUN_AGENT_ABANDONED_SECONDS` (default 120) before appending `WATCH_ABANDONED_UTC` / `WATCH_ABANDONED_REASON` to `run-info.txt` and reporting the run as abandoned.

The 2026-07-16 recurrence exposed PID reuse in that recovery path. An old run already marked `WATCH_ABANDONED_UTC` referred to a PID later assigned by macOS to `mdworker_shared`; the watcher restored `pid.txt`, killed the unrelated process, and restarted obsolete work. Abandoned and completed records are now terminal and never revived. New runs persist a locale-stable `PID_START`; legacy records without one fail closed. Every live PID must match that process-start signature, the expected agent command, and its recorded process-group role before the watcher may restore, diagnose, terminate, or restart it. Identity mismatches are reported and eventually marked abandoned without signaling the process. The termination path snapshots descendant birth signatures before `TERM`, uses a surviving original member to anchor the process group while discovering TERM-time children, and revalidates every survivor before `KILL`, avoiding PID-reuse races during the grace period.

Review quorum attribution also fails closed when multiple projects share one `RUNS_DIR`. Each review attempt supplies a unique `RUN_AGENT_ATTEMPT_TOKEN`; `run-agent.sh` persists it in `run-info.txt`, and `run-review-quorum.sh` selects the completed run by token, expected repository `CWD`, agent, and copied prompt instead of trusting the mutable `runs/latest` symlink. This prevents an unrelated concurrent project run from stealing or invalidating a verdict.

The 2026-07-04 root-side workflow is now wrapper-first. Local implementation, review, and test prompts tell agents to follow this file and to use `scripts/run-gradle-bounded.sh` or `scripts/run-bounded-experiment.sh` instead of bare build/test commands. The agent runner reliability preamble says the same thing, so new agents inherit the policy even when older generic prompt text still mentions MCP Steroid for builds. `run-agent.sh`, `scripts/run-gradle-bounded.sh`, and `scripts/run-bounded-experiment.sh` maintain a `latest` symlink in their run directories, making the first diagnostic target stable: `runs/latest`, `runs/gradle-bounded/latest`, or `runs/bounded-experiments/latest`.

One more local visibility failure was that `scripts/run-bounded-experiment.sh` persisted stdout/stderr to files but did not mirror them to the terminal. That made long but healthy Docker/IDE experiments look silent to the root agent until completion. The wrapper now mirrors output by default while keeping the persisted files and diagnostics; set `EXPERIMENT_MIRROR_OUTPUT=0` only when a deliberately quiet run is more useful. External TERM/INT signals also trigger a diagnostic bundle before the child process tree is terminated.

The 2026-07-04 follow-up found one remaining outer-timeout blind spot. `ralph-loop.sh` wraps `run-agent.sh` with a process timeout, but a `TERM` delivered by that outer guard used to make `run-agent.sh` terminate the agent tree without first writing a diagnostic bundle. `run-agent.sh` now handles `TERM`/`INT`/`HUP` by writing the same `DIAGNOSTICS=...` bundle used for no-output and wall-clock timeouts before killing descendants. `ralph-loop.sh` also refuses bounded runs when neither `timeout` nor `gtimeout` is installed, instead of silently falling back to an unbounded shell command.

The 2026-07-04 repeated-stall pattern showed that recovery also needs a retry budget. A stale run may now be restarted by `watch-agents.sh` only up to `RUN_AGENT_RESTART_MAX_ATTEMPTS` (default 1). Restarted runs record `RESTART_ROOT`, `RESTART_OF`, and `RESTART_ATTEMPT` in `run-info.txt`; the stale run records `WATCH_RESTART_*` fields. The watcher rotates the restarted job across `RUN_AGENT_RESTART_AGENTS` (default `codex,gemini,claude`) when `RUN_AGENT_RESTART_ROTATE_AGENT=1`, so a silent Claude/Codex/Gemini failure does not simply restart the same CLI until the root loop appears stuck again.

The next recurrence showed that successful recovery can still create a concurrency problem if the loop immediately starts another agent while the recovered prompt is also relaunched. `watch-agents.sh` now prints a machine-readable `WATCH_SUMMARY stale=... diagnostics=... terminated=... restarted=... restart_skipped=... abandoned=...` line and supports `RUN_AGENT_FAIL_ON_RECOVERY=1`. With that flag, a one-shot watcher exits `124` after terminating, restarting, or skipping a restart for a stale run. `run-agent.sh`, `ralph-loop.sh`, and `scripts/run-supervised.sh` now use that signal internally: they run one bounded recovery pass, then automatically run a clean verification pass before launching the requested work. The retry budget is capped (`RUN_AGENT_PREFLIGHT_RECOVERY_MAX_PASSES`, `RUN_AGENT_RECOVERY_MAX_PASSES`, and `SUPERVISED_RECOVERY_MAX_PASSES` default to 2), so recurring recovery still fails with diagnostics instead of spinning.

The 2026-07-04 follow-up closed the same evidence gap for Gradle signal exits. `scripts/run-gradle-bounded.sh` now handles `TERM`, `INT`, and `HUP` by writing the same `DIAGNOSTICS=...` bundle used for wall-clock and output-idle timeouts before it terminates the Gradle process tree and releases the repository lock. This matters when an outer supervisor, terminal interruption, or root-agent timeout kills a verification run: the next retry must start by reading `runs/gradle-bounded/latest/run-info.txt` and the recorded diagnostics instead of relaunching blindly.

The latest adjustment is a single front-door wrapper: `scripts/run-supervised.sh`. It runs bounded stale-agent recovery first, automatically verifies that recovery has settled, dispatches Gradle checks through `scripts/run-gradle-bounded.sh`, dispatches ad hoc repros through `scripts/run-bounded-experiment.sh`, and runs role agents through `ralph-loop.sh`. If the command exits non-zero, it prints the latest `run-info.txt` path plus any recorded `DIAGNOSTICS=` / `GRADLE_DIAGNOSTICS=` entries. Use this wrapper by default so every retry starts from recovered agent state and every timeout points directly at the forensic bundle.

`scripts/run-supervised.sh health` is the default first command after an apparent stall. It runs shell syntax checks for the orchestration scripts, performs a bounded one-shot watcher status check, prints the latest agent/Gradle/experiment diagnostic anchors, and reports whether `jps`, `jcmd`, `jstack`, and Docker are available. Normal `scripts/run-supervised.sh ...` work also runs the shell-health gate before launching the requested child command, so broken wrapper edits fail closed before starting another long experiment.

`scripts/run-supervised.sh` now forwards `TERM`, `INT`, and `HUP` to the active child wrapper and waits up to `SUPERVISED_SIGNAL_GRACE_SECONDS` for that child to write its own diagnostics before forcibly terminating the process tree. This covers outer root-agent interruptions: a killed supervised Gradle/experiment/agent run should still leave the lower-level `DIAGNOSTICS=` / `GRADLE_DIAGNOSTICS=` path in the relevant `latest/run-info.txt`.

The follow-up hardening makes the lower-level helper timeouts self-contained. `run-agent.sh` and `watch-agents.sh` now try `/opt/homebrew/bin/timeout`, `gtimeout`, and `timeout`, then fall back to a local background-process timeout loop. That keeps preflight recovery and individual diagnostic commands bounded even on macOS setups where GNU coreutils is not on `PATH`. `scripts/run-bounded-experiment.sh` now also checks the Homebrew timeout path before using its manual fallback.

The top-level front doors now follow the same rule. `scripts/run-supervised.sh` and `ralph-loop.sh` also fall back to a local elapsed-time loop when no system timeout command is available, returning `124` after sending `TERM` and then `KILL` to the bounded process tree. This keeps recovery, review quorum, role-agent launches, and direct Ralph-loop runs bounded even on a minimal macOS shell.

The IntelliJ README screenshot helper follows the same wrapper-first rule. Refresh it through `scripts/run-supervised.sh experiment -- scripts/update-intellij-readme-screenshot.sh`; the helper also has a local timeout fallback for its internal Docker, Playwright, npm, and JVM-diagnostic subprocesses. It explicitly launches IDEA with the native launcher and fails closed if fresh logs or renderer text expose `ide.script.launcher.used` / script-launcher notification state, so stale warning balloons cannot silently overwrite `docs/images/intellij-demo-renderer.png`. IntelliJ archives and URL-keyed extracted homes persist under `.gradle/intellij-community-cache` by default and are bind-mounted into disposable smoke, parity, and screenshot containers; do not put them under `build/`, where `gradle clean` would force another download and extraction.

The lower-level local timeout fallbacks now also terminate descendant process trees consistently. This covers `run-agent.sh` preflight/diagnostic commands, `watch-agents.sh` diagnostic commands, Gradle diagnostic helpers, and bounded experiment diagnostic helpers on systems without `/opt/homebrew/bin/timeout`, `gtimeout`, or `timeout`. The main Gradle, experiment, and agent child processes already wrote diagnostics and killed descendants; the remaining gap was helper subprocesses that could leave a nested JVM/CLI child alive after the fallback killed only the direct shell.

IntelliJ parity captures must wait for comparable semantic UI state, not a fixed sleep after the project frame appears. The `run-intellij` helper opens the project README explicitly, and the heavyweight parity test waits for Project View initialization, `README.md` open, and Markdown preview HTML generation before capturing Xvfb or Kotlin screenshots. If those markers do not appear, treat that as the next root-cause artifact: inspect the dumped IDEA logs and X server traces before changing protocol behavior. This prevents slower Kotlin-side startup from being misclassified as a rendering regression.

## Required Practice

- Start long commands through `scripts/run-supervised.sh` unless a lower-level wrapper is explicitly needed.
- After any apparent stall, run `scripts/run-supervised.sh health` before retrying the implementation loop. Treat a health failure as the next bug to fix before starting more agents or experiments.
- Use `scripts/run-supervised.sh gradle ...` for Gradle/build/test work and `scripts/run-supervised.sh experiment -- ...` for blocking non-Gradle repros. The script performs stale-agent recovery before the run, verifies a clean watcher pass after any recovery, and prints the latest diagnostic target on failure.
- Never run Gradle, Maven, IDE build, test, package, or other build-directory-writing commands in parallel for this repository. Queue them one at a time, using `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process` for Gradle checks unless a task explicitly needs different settings.
- Prefer `scripts/run-gradle-bounded.sh <tasks...>` for Gradle checks. It holds the repo Gradle lock and captures JVM diagnostics before killing a timed-out run.
- Treat `runs/gradle-bounded/run_*/heartbeat.txt`, `run-info.txt`, `stdout.txt`, and `stderr.txt` as the first stop for a suspected Gradle stall. `GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS` controls the first diagnostic snapshot, and `GRADLE_NO_OUTPUT_TIMEOUT_SECONDS` controls the automatic kill.
- For the most recent Gradle stall, start with `runs/gradle-bounded/latest/run-info.txt`; for the most recent non-Gradle experiment, start with `runs/bounded-experiments/latest/run-info.txt`; for the most recent run-agent, start with `runs/latest/run-info.txt`.
- Prefer `scripts/run-bounded-experiment.sh -- <command> [args...]` for ad hoc non-Gradle repros and experiments so timeout failures leave a persisted diagnostic bundle. Its default `EXPERIMENT_MIRROR_OUTPUT=1` also streams child stdout/stderr to the current terminal, which makes healthy long experiments visibly active while still preserving `stdout.txt` and `stderr.txt`.
- Refresh README screenshots through `scripts/run-supervised.sh experiment -- ...` so Docker, IntelliJ/VSCode, Playwright, and capture failures produce bounded experiment diagnostics instead of silent local hangs.
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

- Keep stale restart loops capped. Defaults are:

  ```bash
  RUN_AGENT_RESTART_MAX_ATTEMPTS=1
  RUN_AGENT_RESTART_ROTATE_AGENT=1
  RUN_AGENT_RESTART_AGENTS=codex,gemini,claude
  ```

  If a restarted run also stalls, inspect its `RESTART_ROOT` chain and diagnostics instead of increasing the retry count blindly.

- Treat `WATCH_RECOVERY_PERFORMED=1` or a `124` from `run-agent.sh`/`ralph-loop.sh` preflight as a completed recovery pass. Current wrappers automatically perform the follow-up clean watcher pass before launching work; if they still exit `124`, inspect `runs/agent-watch.log` because recovery did not settle within the retry budget.
- Keep routine run monitoring bounded to recent runs or active PID files. The local `runs/` tree is large enough that whole-history scans can time out.
- Treat `abandoned` watcher lines as completed forensic records, not live agents. Inspect their `run-info.txt` and diagnostics if needed, but do not wait for them; the watcher has confirmed there is no live PID to recover.
- Do not let run-agents spawn additional unbounded review subagents. Quorum reviews should be scheduled by the root agent with explicit timeouts, or replaced by a bounded local review for trivial changes.
- For routine 3x review quorum, use `scripts/run-supervised.sh review-quorum <prompt-file>`. It runs one bounded review at a time, rejects empty/no-verdict outputs, stops immediately on a real FAIL verdict, and defaults to three independent Codex reviews because Gemini/Claude have repeatedly been the source of silent CLI stalls in this repository. Override `REVIEW_QUORUM_AGENTS` only for an intentional cross-agent experiment.
- Do not ask run-agent research/review scouts to use MCP Steroid by default. Shell-based inspection is enough for most gap selection and avoids MCP stdio waits inside a silent text-mode agent.
- When a run times out, inspect the generated `DIAGNOSTICS=...` file in `run-info.txt` before retrying. Agent and watcher diagnostics now include Docker/Testcontainers state for `jonnyzzz-x` client/reference containers and Ryuk, so use those sections to distinguish container download/startup/extract work from JVM or X server deadlocks.
- On macOS, prefer installing GNU coreutils or exposing `/opt/homebrew/bin/timeout` / `gtimeout` for better process-group handling. If none is available, the local wrappers still apply a manual fallback timeout around watcher, preflight, and experiment diagnostic commands instead of running those helpers unbounded.
- Treat built-in subagents as scarce stateful resources. After a bounded `wait_agent`, close only agents that have returned a final status, and close them individually. Do not call `close_agent` for a non-responsive agent and do not wrap `close_agent` calls in a parallel tool batch; one blocked close can stall the whole root agent.
- Run a one-shot diagnostic/recovery watcher before every new implementation/review batch. This is now the default `run-agent.sh` preflight (`RUN_AGENT_PREFLIGHT_RECOVER_STALE=1`); use the explicit command when recovering outside a new agent launch:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 RUN_AGENT_RESTART_STALE=1 ./watch-agents.sh
  ```

- Use `./ralph-loop.sh <role>` for routine research/implementation/review/test agent work. It runs the recovery watcher before the agent, applies the standard wall-clock/no-output diagnostics settings, and wraps `run-agent.sh` in an outer process timeout.
- If `./ralph-loop.sh <role>` exits from the outer timeout, inspect `runs/latest/run-info.txt` for `SIGNAL=TERM` and `DIAGNOSTICS=...`; the signal path should now contain process lists, `jps`, JVM thread dumps when present, Docker/Testcontainers state, and stdout/stderr tails.
- If `scripts/run-gradle-bounded.sh` exits after an external signal, inspect `runs/gradle-bounded/latest/run-info.txt` for `TIMEOUT_REASON=gradle-signal-*` and the recorded `DIAGNOSTICS=...` path before rerunning the same task.
- Failed Gradle and experiment runs also keep their post-failure artifact scans bounded. Gradle native-crash scans now inspect only current-run text/log/XML/stdout/stderr/`hs_err` candidates and use separate bounds for file discovery, per-file grep, max files, and max bytes (`GRADLE_NATIVE_CRASH_SCAN_FIND_TIMEOUT_SECONDS`, `GRADLE_NATIVE_CRASH_SCAN_GREP_TIMEOUT_SECONDS`, `GRADLE_NATIVE_CRASH_SCAN_MAX_FILES`, `GRADLE_NATIVE_CRASH_SCAN_MAX_BYTES`). Experiment crash-anchor scans remain wrapped in `EXPERIMENT_DIAGNOSTICS_COMMAND_TIMEOUT_SECONDS`. An expected failing test should return with relevant diagnostics instead of recursively scanning retained IntelliJ/VSCode artifacts.
- Keep direct-runner preflight recovery bounded with `RUN_AGENT_PREFLIGHT_WATCH_TIMEOUT_SECONDS` (default 180 seconds). If that fires, inspect `runs/agent-watch.log` before retrying.

## Runner Timeout Knobs

Use the supervised wrapper unless a specific run justifies changing it:

```bash
scripts/run-supervised.sh review codex
scripts/run-supervised.sh gradle test --console=plain
scripts/run-supervised.sh experiment -- sh -lc 'your repro command here'
```

Use these lower-level defaults only when bypassing the supervised preflight:

```bash
RUN_AGENT_TIMEOUT_SECONDS=900 \
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=180 \
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300 \
timeout 990 ./ralph-loop.sh review codex
```

Use this lower-level shape for repository Gradle checks:

```bash
GRADLE_TIMEOUT_SECONDS=1800 \
GRADLE_NO_OUTPUT_DIAGNOSTICS_SECONDS=300 \
GRADLE_NO_OUTPUT_TIMEOUT_SECONDS=900 \
scripts/run-gradle-bounded.sh test --console=plain
```

Use this lower-level shape for non-Gradle experiments:

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
