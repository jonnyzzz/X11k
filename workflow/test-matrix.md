# Test Matrix

## Raw Protocol

- little-endian setup success,
- big-endian setup success,
- authorization name/data padding,
- invalid byte order failure,
- malformed short setup request,
- request length validation,
- unsupported opcode error.

All tracked protocol clients and differential oracles are implemented as native
Kotlin/JUnit tests under `src/test/kotlin`. The Gradle `check` lifecycle runs
`verifyKotlinTestSources`, which rejects Python, Java, Groovy, and Scala sources
under `src/test` so a standalone reproducer cannot silently become an
unmaintained second test stack and Kotlin/JUnit remains the single maintained
JVM test language.

## Docker Clients

Run each client against Xvfb first, then against the Kotlin server. Xvfb belongs only in
the `jonnyzzz-x/x11-reference:latest` comparison image; the normal
`jonnyzzz-x/x11-client:latest` demo/client image must stay free of Xvfb.

| Client | Purpose | First Expected Kotlin Result |
| --- | --- | --- |
| `xdpyinfo` | Setup, screens, visuals, extensions | Passing |
| `xprop -root` | Atoms and properties | Passing |
| `xwininfo -root` | Window tree and geometry | Passing |
| `xset q` | Basic server state queries | Passing |
| `xlogo` | Window, expose, drawing | Passing exact Robot/composed-SVG parity with no unsupported requests |
| `xclock` | Drawing, timer updates, events | Passing exact Robot/composed-SVG parity with no unsupported requests |
| `xeyes` | Pointer motion and drawing | Passing exact Robot/composed-SVG parity with no unsupported requests |
| `xcalc` | Widgets, cursors, fonts, text drawing | Passing exact Robot/composed-SVG parity with no unsupported requests |
| `twm` + `xlogo` + `xclock` | Window manager, independent windows, overlap/focus state | Passing exact Robot/composed-SVG parity with no unsupported requests |
| `xterm` | Text, keyboard, properties | Passing exact Robot/composed-SVG parity with no unsupported requests |
| AWT/Swing Java2D samples | Java GUI behavior, overlapping windows, owned popups/dialogs, heavyweight popup menus, menu dropdown popups, combo-box dropdowns, Swing tooltips, dense scroll-pane content, form controls, tabbed split-pane layouts, desktop-pane internal-frame layouts, layered/glass-pane overlays, primitive/dense/form/tabbed/desktop/layered SVG framebuffer export parity, and overlap/popup composed SVG parity | Passing exact full-pixel Docker/Xvfb parity with no unsupported requests |
| IntelliJ IDEA Community GitHub release | Real-world target, heavyweight opt-in smoke and deterministic full-screen Xvfb parity | Passing opt-in smoke; latest traced parity is pixel-exact |
| VSCode stable tarball | Real-world Electron target, heavyweight opt-in startup/rendering smoke and full-pixel Xvfb parity with extension-query evidence | Passing opt-in smoke; latest parity is pixel-exact |
| Java AWS application | Third real-world target requested for acceptance | Fixture/artifact and deterministic ready state not yet present; harness is P0 |

The AWT/Swing Java2D probes require full-pixel equality for client-side `Robot` captures against Xvfb for deterministic primitives, overlapping Swing windows, owned popup windows, owned dialog windows, heavyweight popup menus, menu dropdown popups, combo-box dropdowns, Swing tooltips, dense `JTable`/`JTree` scroll-pane content, standard form controls such as text fields, checkboxes, sliders, progress bars, buttons, and text areas, IDE-style tabbed split-pane layouts with lists and editor text, desktop-pane internal-frame layouts, and layered/glass-pane overlays. They also require full-pixel equality for the Kotlin server's exported SVG framebuffer for the single-window primitive, dense Swing, form-controls, tabbed split-pane, desktop-pane, and layered-overlay probes, plus the composed SVG framebuffer stack for overlapping windows and heavyweight popup surfaces. Every Kotlin JVM probe fails if `/text.txt` reports an unsupported request. The `xlogo`, `xclock`, `xeyes`, `xcalc`, `xterm`, and `twm`-managed overlap probes now require the same full-pixel Robot/composed-SVG equality and final unsupported-request inventory for core `FillPoly`, `PutImage`, shaped `RENDER.Trapezoids`, mapped child-window borders, window-manager reparent/map exposure delivery, and fixed-font `ImageText8` paths. Both harnesses accept captures only after three identical Robot frames; classic-client references also require fixture-specific content regions so black root pixels or partial windows cannot satisfy the gate. The AWT probes retain text/SVG/HTML, SVG layer inventories, Robot PNGs, full mismatch counts, and scalar metrics under `build/tmp/awt-primitive-docker/`; the real xclient parity probes retain analogous final text/SVG/layer/Robot/metrics artifacts under `build/tmp/xvfb-container-test/`. The xcalc metrics additionally split any visual delta into display-frame, display-text, angle-indicator, and keypad regions so renderer fixes can be selected from evidence. IntelliJ and VSCode have opt-in full-screen Robot/SVG-composition comparisons against Xvfb for their current project-open states, and both parity paths now retain raw HTML plus per-window preview inventories for large retained surfaces. IntelliJ JCEF initializes and the Markdown fixture reaches `setHtml`, but the accepted capture still shows a suspended embedded browser; browser pixels and real GL rendering remain parked until target evidence selects that work.

The IntelliJ and VSCode smokes are excluded from default `test` because they use
heavyweight external application artifacts. Decide whether the future Java AWS
fixture is opt-in only after its artifact and launch model are established.
Both IDE fixtures retain validated release archives in host directories under
`build/tmp` and bind them into disposable containers; Xvfb and Kotlin phases
reuse the same archive rather than downloading it per container.
The retained VSCode trace supplies target evidence for XKEYBOARD negotiation,
map, event-selection, and per-client-flag requests. Focused Kotlin tests cover
compatible and incompatible versions, pre-initialization `BadAccess`, byte
order, request validation order, failed renegotiation, and per-client isolation.
Build `jonnyzzz-x/x11-client:latest` first with `scripts/run-supervised.sh gradle dockerBuildX11Client`, then run the smoke explicitly with `-Dx.intellijSmoke=true`. Build both Docker images with `scripts/run-supervised.sh gradle dockerBuildX11Images`, then run the deterministic bounded full-pixel parity probe explicitly with `-Dx.intellijParity=true`.
Both IntelliJ paths mount a clean tracked-file project export from `build/tmp/intellij-community-smoke/project` so untracked orchestration files do not affect the visible IDE project tree.
The parity probe writes PNG/SVG/HTML/text diagnostics, SVG layer and HTML per-window preview inventories, visual diffs/metrics, visual region metrics with full-screen, inside-frame, top-frame, right-frame, and bottom-frame mismatch bounds, and IntelliJ client logs under `build/tmp/intellij-community-smoke/` so visual drift can be inspected without rerunning the heavyweight clients immediately.
It also writes `intellij-glx-jcef-diagnostics.txt` from Xvfb/Kotlin `xdpyinfo -ext GLX` preflight logs, decoded client GLX extension strings, and side-by-side Xvfb/Kotlin JCEF/ANGLE signatures such as missing FBConfigs, ES2 supportability, and missing create-context extension messages.
Add `-Dx.intellijDebug=true` or `X_INTELLIJ_DEBUG=true` to the smoke or parity probe when XAWT/JCEF tracing is needed; optional trace files are copied into the same diagnostics directory.
Debug mode also captures pid-suffixed JCEF/Chromium log files when JetBrains Runtime writes them and enables Mesa/EGL loader diagnostics in the IntelliJ run log.
Run the VSCode smoke explicitly with `-Dx.vscodeSmoke=true` after building `jonnyzzz-x/x11-client:latest`; it writes the Kotlin `/text.txt`, `/screen.svg`, raw `/` HTML, `vscode-diagnostics.txt`, HTML preview inventory, and VSCode logs under `build/tmp/vscode-smoke/`.
Build both Docker images with `scripts/run-supervised.sh gradle dockerBuildX11Images`, then run the bounded full-pixel VSCode parity probe explicitly with `-Dx.vscodeParity=true`; it writes Xvfb reference/Kotlin Robot/SVG-composed PNGs, raw SVG/HTML/text diagnostics, SVG layer and HTML per-window preview inventories, visual diffs, full-screen RGB-L1 metrics with full-frame, worst-row, worst-column, sliding 8x8-window, and maximum-pixel limits, visual region metrics, diagnostics, and logs under `build/tmp/vscode-smoke/`.

Build all local Docker images before the default Docker-backed test matrix:

```bash
scripts/run-supervised.sh gradle dockerBuildX11Images
scripts/run-supervised.sh gradle test
```

## Differential Output

Normalize before comparing:

- display names,
- vendor strings,
- timestamps,
- resource ids when not semantically important,
- ordering that the X11 spec leaves unspecified.

Compare:

- exit code,
- stderr category,
- normalized stdout,
- protocol trace,
- hierarchy snapshot,
- deterministic pixel checks.

## Extension Gate

Use `workflow/extension-scope.md` when choosing extension work. A new extension, or a new request inside an advertised extension, needs trace evidence from IntelliJ IDEA, VSCode, the Java AWS fixture once established, or a matrix client that supports those targets. Keep broad Xvfb parity out of scope until the essential target clients run and render correctly.
