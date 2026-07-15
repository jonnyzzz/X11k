# X11 Protocol Review For The Kotlin Server

This review records the original implementation guidance for `jonnyzzz/x`, not a replacement for the X11 protocol specification. Most foundational items below are now implemented; current priorities and acceptance evidence live in `workflow/roadmap.md`. The vendored spec lives at `docs/spec/x11protocol.html`.

## Server Boundary

The Kotlin server owns the server side of the X11 wire protocol. It does not need `libX11`, XCB, X.Org server libraries, or OS display APIs. Client containers still need ordinary X client libraries and fonts when they run Xlib/XCB/AWT/Swing applications.

## Core Handshake

The first bytes sent by a client establish byte order and the requested protocol version. The server must:

- accept `0x6c` little-endian and `0x42` big-endian setup requests,
- parse authorization protocol name/data with 4-byte padding,
- reply with success/failure/authenticate using the client's byte order,
- advertise one screen, supported pixmap formats, depths, and visuals,
- assign a per-client resource-id base and mask.

The server implements setup in both byte orders with one root window and the visual/depth inventory required by the maintained target clients.

## Request Dispatcher

The request loop implements the maintained core surface, including:

- 16-bit sequence numbers per client,
- request length validation before side effects,
- exact reply/error packet sizing,
- opcode table for all core requests,
- explicit unsupported-request errors for unimplemented opcodes,
- trace hooks for AI/debug observation.

## Minimal Core Model

The maintained server model includes:

- one screen and root window,
- resource table scoped by client,
- windows, pixmaps, graphics contexts, colormaps, cursors, fonts as typed resources,
- atoms and properties,
- event masks, delivery, and propagation,
- expose, structure, property, focus, key, button, and motion events.

## Rendering Subset

Use a deterministic ARGB framebuffer internally. Implement enough conversion at the protocol boundary to satisfy advertised depths and formats.

Initial drawing operations:

- `PutImage` and `GetImage`,
- `ClearArea`,
- `CopyArea`,
- filled rectangles,
- simple points and lines as clients require them.

The target-evidenced drawing and text paths mutate deterministic framebuffer state. Unimplemented operations must be selected from target traces rather than accepted as broad speculative work.

## Extension Strategy

Unmaintained extensions remain absent from `QueryExtension`. The implemented inventory and evidence gate are documented in `workflow/extension-scope.md`; new work requires an IntelliJ, VSCode, established Java AWS, or existing matrix trace.

## AI Observation

The observation model should be fed from server state, not inferred from protocol logs after the fact:

- screen snapshots and frame diffs,
- normalized input and X event stream,
- focused window and cursor state,
- window/resource hierarchy with geometry, mapped state, properties, and drawable IDs,
- protocol errors with client id, sequence number, and opcode.
