# Roadmap

Status reviewed on 2026-07-15. The compatibility target is IntelliJ IDEA,
VSCode, and the Java AWS application, not unrestricted Xvfb feature parity.
Extension work remains governed by `workflow/extension-scope.md`.

## Current Baseline

- Production and tracked protocol/oracle tests are pure Kotlin/JVM. All tests
  under `src/test` are Kotlin/JUnit; `check` rejects non-Kotlin JVM and Python
  test sources.
- The default suite contains 1,381 JUnit tests (4 heavyweight opt-in tests are
  skipped by default). Full `check` passed in
  `runs/gradle-bounded/run_20260715-221207-62803`.
- IntelliJ deterministic project-open parity is pixel-exact for the Xvfb Robot,
  Kotlin Robot, and Kotlin SVG-composed captures. The parity run
  `runs/gradle-bounded/run_20260715-220719-57572` reports all three distances as
  `0.0`, no mismatch bounds, and no unsupported requests.
- VSCode deterministic parity is pixel-exact for Robot and SVG-composed output
  against Xvfb. Run `runs/gradle-bounded/run_20260715-221046-61229` reports both
  distances as `0.0` and no unsupported requests.
- No Java AWS application artifact, source fixture, launch command, or expected
  visual state is tracked in this repository. Its compatibility is therefore
  not yet measurable and must not be inferred from the IntelliJ/VSCode results.

## Completed Foundations

- Kotlin/JVM build, vendored X11 specifications, setup handshake, both byte
  orders, request framing, sequence/error behavior, and protocol tracing.
- One-screen server model with resource ownership, windows, pixmaps, graphics
  contexts, fonts, cursors, colormaps, atoms/properties, events, focus, grabs,
  input, and hierarchy/stacking state.
- Framebuffer-backed core drawing, images, text, pixmap copies, and the
  target-evidenced RENDER surface, with semantic producer/provenance details
  retained for HTTP observation. Core text commands retain their decoded text,
  protocol origin, baselines, paint result, and drawable generations without
  misclassifying RENDER glyph diagnostics as decoded application text. RENDER
  glyph commands retain actual counts, ordered glyph-set/ID placements, pen and
  image coordinates, metrics, picture IDs, and bounded completeness metadata.
  RENDER fill commands retain their operation ID, operator, destination picture
  and format, original CARD16 RGBA values, quantized ARGB32 color, exact
  rectangles, generation, and paint result. No-op fills have a separate
  10,000-command semantic budget, never displace the 10,000 regular drawing
  commands, and do not enter render-paint history; `/state.json` reports total
  and retained command/rectangle counts, completeness, and whether retained
  operation IDs still resolve in the independently bounded provenance ring.
- Same-port HTTP/HTML, SVG, text, JSON, and input endpoints derived from the
  maintained server state rather than a separate visual model.
- Differential Docker coverage for X11 tools, classic clients, AWT/Swing,
  IntelliJ IDEA Community, and VSCode/Electron.
- Advertised and maintained extension inventory: BIG-REQUESTS, RENDER, MIT-SHM,
  XFIXES, SHAPE, XKEYBOARD, RANDR, SYNC, DOUBLE-BUFFER, XC-MISC, XINERAMA,
  MIT-SCREEN-SAVER, Generic Event Extension, minimal GLX, minimal
  XInputExtension, XTEST, and MIT-SUNDRY-NONSTANDARD. Deeper work still needs
  target evidence.

## Remaining Acceptance Work

### P0: Establish The Java AWS Fixture

1. Add or identify the exact application artifact and a reproducible bounded
   launch command.
2. Define the deterministic ready state, required interactions, Xvfb reference
   capture, Kotlin Robot capture, SVG-composed capture, logs, and timeout.
3. Add a Kotlin/Testcontainers smoke and parity test; decide whether it belongs
   in the default or opt-in suite only after the artifact and launch model are
   known. Do not add protocol behavior before the fixture supplies trace evidence.

### P1: Keep Target Evidence Fresh

1. Re-run focused IntelliJ and VSCode parity after any rendering, input,
   extension, setup, or visual metadata change.
2. Fail on unsupported requests, disconnects, missing semantic state, or visible
   capture drift. Retain bounded traces and visual artifacts with the run.
3. Refresh README screenshots only when visible target output changes.
4. Treat the current suspended JCEF browser surface as an explicit known IntelliJ
   limitation until target acceptance either excludes it or supplies a stable
   browser-pixel readiness and parity gate.

### P2: Implement Only Proven Gaps

1. Select the smallest failing request, event, renderer primitive, or semantic
   state transition from a target trace.
2. Add a focused Kotlin protocol regression and, where relevant, a reduced Xvfb
   differential oracle before changing production behavior.
3. Preserve drawable/picture/window provenance and operation context in the
   state and diagnostics APIs.
4. Run focused tests, full `check`, IntelliJ IDE compilation, target parity, and
   the required review quorum before committing and pushing.

## Parked Work

Composite, DAMAGE, Present, DRI2/DRI3, broad XI2 behavior, desktop-specific
extensions, and real GLX rendering remain parked until one of the three target
applications proves they are required. General protocol completeness is useful
only after the target acceptance gates above stay green.

## Compatibility Completion Gate

The current goal is complete when IntelliJ IDEA, VSCode, and the Java AWS
fixture all reach their deterministic ready states without unsupported requests
or disconnects; Kotlin Robot and SVG-composed pixels match the corresponding
Xvfb reference with no visible side effects; relevant semantic resources and
operation provenance remain available through observation APIs; and focused,
full-suite, IDE-build, and review gates pass from a clean tracked tree.
