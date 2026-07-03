# Test Matrix

## Raw Protocol

- little-endian setup success,
- big-endian setup success,
- authorization name/data padding,
- invalid byte order failure,
- malformed short setup request,
- request length validation,
- unsupported opcode error.

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
| `xlogo` | Window, expose, drawing | Passing rough Robot/composed-SVG parity probe |
| `xclock` | Drawing, timer updates, events | Passing rough Robot/composed-SVG parity probe |
| `xeyes` | Pointer motion and drawing | Passing rough Robot/composed-SVG parity probe |
| `xcalc` | Widgets, cursors, fonts, text drawing | Passing rough Robot/composed-SVG parity probe |
| `twm` + `xlogo` + `xclock` | Window manager, independent windows, overlap/focus state | Passing rough Robot/composed-SVG parity probe |
| `xterm` | Text, keyboard, properties | Passing rough Robot/composed-SVG parity probe |
| AWT/Swing Java2D samples | Java GUI behavior, overlapping windows, owned popups/dialogs, heavyweight popup menus, menu dropdown popups, combo-box dropdowns, Swing tooltips, dense scroll-pane content, form controls, tabbed split-pane layouts, desktop-pane internal-frame layouts, layered/glass-pane overlays, primitive/dense/form/tabbed/desktop/layered SVG framebuffer export parity, and overlap/popup composed SVG parity | Passing Docker/Xvfb parity probe |
| IntelliJ IDEA Community GitHub release | Real-world target, heavyweight opt-in smoke and rough full-screen Xvfb parity | Passing opt-in smoke; rough parity opt-in |
| VSCode stable tarball | Real-world Electron target, heavyweight opt-in startup/rendering smoke with extension-query evidence | Opt-in Kotlin smoke harness added |

The AWT/Swing Java2D probes compare client-side `Robot` captures against Xvfb for deterministic primitives, overlapping Swing windows, owned popup windows, owned dialog windows, heavyweight popup menus, menu dropdown popups, combo-box dropdowns, Swing tooltips, dense `JTable`/`JTree` scroll-pane content, standard form controls such as text fields, checkboxes, sliders, progress bars, buttons, and text areas, IDE-style tabbed split-pane layouts with lists and editor text, desktop-pane internal-frame layouts, and layered/glass-pane overlays. They also compare the Kotlin server's exported SVG framebuffer for the single-window primitive, dense Swing, form-controls, tabbed split-pane, desktop-pane, and layered-overlay probes, plus the composed SVG framebuffer stack for overlapping windows and heavyweight popup surfaces. The `xlogo`, `xclock`, `xeyes`, `xcalc`, `xterm`, and `twm`-managed overlap probes now compare both Robot output and composed SVG output against Xvfb with rough rasterization tolerance for core `FillPoly` edge coverage, `PutImage` clock-face rasterization, shaped `RENDER.Trapezoids` eyes/pupils, mapped child-window borders, window-manager reparent/map exposure delivery, and synthetic fixed-font `ImageText8` text. IntelliJ has an opt-in full-screen Robot/SVG-composition comparison against Xvfb for the current project-open state, but still needs broader SVG/HTML parity coverage for larger retained surfaces and GL/JCEF-backed paths.

The IntelliJ and VSCode smokes are excluded from default `test` because they download large current release tarballs.
Build `jonnyzzz-x/x11-client:latest` first with `scripts/run-gradle-bounded.sh dockerBuildX11Client`, then run the smoke explicitly with `-Dx.intellijSmoke=true`. Build both Docker images with `scripts/run-gradle-bounded.sh dockerBuildX11Images`, then run the rough parity probe explicitly with `-Dx.intellijParity=true`.
Both IntelliJ paths mount a clean tracked-file project export from `build/tmp/intellij-community-smoke/project` so untracked orchestration files do not affect the visible IDE project tree.
The parity probe writes PNG/SVG/text diagnostics, visual diffs/metrics, and IntelliJ client logs under `build/tmp/intellij-community-smoke/` so visual drift can be inspected without rerunning the heavyweight clients immediately.
It also writes `intellij-glx-jcef-diagnostics.txt` from Xvfb/Kotlin `xdpyinfo -ext GLX` preflight logs, decoded client GLX extension strings, and known JCEF/ANGLE signatures.
Add `-Dx.intellijDebug=true` or `X_INTELLIJ_DEBUG=true` to the smoke or parity probe when XAWT/JCEF tracing is needed; optional trace files are copied into the same diagnostics directory.
Debug mode also captures pid-suffixed JCEF/Chromium log files when JetBrains Runtime writes them and enables Mesa/EGL loader diagnostics in the IntelliJ run log.
Run the VSCode smoke explicitly with `-Dx.vscodeSmoke=true` after building `jonnyzzz-x/x11-client:latest`; it writes the Kotlin `/text.txt`, `/screen.svg`, `vscode-diagnostics.txt`, and VSCode logs under `build/tmp/vscode-smoke/`.

Build all local Docker images before the default Docker-backed test matrix:

```bash
scripts/run-gradle-bounded.sh dockerBuildX11Images
scripts/run-gradle-bounded.sh test
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

Use `workflow/extension-scope.md` when choosing extension work. A new extension, or a new request inside an advertised extension, needs trace evidence from IntelliJ IDEA, VSCode, or a matrix client that supports those targets. Keep broad Xvfb parity out of scope until the essential target clients run and render correctly.
