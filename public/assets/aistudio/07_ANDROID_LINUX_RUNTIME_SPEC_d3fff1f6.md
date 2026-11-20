# 07 — Android and Linux Runtime Specification

## 1. Objective

Provide a real Linux development environment on Android for OpenCode, project commands, builds, tests, Git, and local preview servers.

## 2. Reference findings

The supplied Mobile Harness source demonstrates:
- ARM64 compatibility checks.
- Embedded PRoot availability checks.
- Ubuntu rootfs installation.
- Downloaded runtime bundles.
- Zstandard tar extraction.
- Staging directory and repair/backup activation.
- Marker-based component/version tracking.
- Runtime setup progress events.
- Native process execution components.
- Foreground execution services.
- Runtime bridge abstraction.
- Workspace checkpoints.

The Mobile Harness README warns that PRoot is not a virtualization boundary or hardened security jail.

AntiGem's README describes a different default: its recommended self-contained Termux-compatible environment runs without PRoot. Do not conflate these approaches.

## 3. AGT initial runtime decision

**Initial implementation direction:** use a PRoot-based Linux runtime as the first implementation path, based on the supplied Mobile Harness reference, subject to device benchmarks and tool compatibility tests.

This is an AGT design decision, not a claim that PRoot is universally best. Compare Ubuntu PRoot against other feasible environments before locking long-term distribution strategy.

## 4. Setup stages

1. Resolve official runtime manifest URL from `AgtAppConfig` (`BuildConfig.AGT_RUNTIME_MANIFEST_URL`), falling back safely only to valid legacy preferences if `BuildConfig` is unset, or fail early with an actionable release-configuration error. No manual manifest URL input is shown to end users.
2. Detect Android version, CPU ABI (`supportedAbis`), free storage, and baseline compatibility.
3. Download and parse runtime manifest automatically; select the compatible ABI bundle from `bundles` (or `bundle`).
4. Download bundle with resumable `Range` transfer where feasible.
5. Verify SHA-256 integrity against the manifest before touching staging.
6. Extract into isolated staging directory while rejecting path traversal and symlink escapes.
7. Validate required binaries and filesystem layout.
8. Atomically activate runtime (preserving backup for rollback).
9. Install/update base tools, write `.agt-markers`, and run post-activation health checks.
10. Persist installed version, selected ABI, and setup state.

Do not execute unverified archives. Prevent archive path traversal during extraction.

## 5. Runtime bundle design

Use a manifest containing:
- Runtime ID and version.
- Supported ABI/device constraints (`supported_abis`, `min_android_sdk`, `min_required_free_bytes`).
- Single `bundle` or per-ABI `bundles` map (`arm64-v8a`, `x86_64`, etc.), each with:
  - HTTPS Download URL (`http://` allowed only for loopback dev servers in debug builds).
  - Expected compressed size.
  - SHA-256 digest.
  - Archive format (`tar.gz`, `zip`, `tar`).
  - Required extraction root and required paths.
- Component dependencies and license notices.
- Release channel.

Support component version markers and repair/reinstall.

## 6. Process execution

RuntimeExecutor should provide:
- Start process with working directory and environment.
- Stream stdout and stderr.
- Capture exit code.
- Send stdin.
- Send interrupt/terminate/kill.
- Track PID/session/process identity.
- Enforce timeout and output bounds.
- Propagate cancellation.
- Recover or clean stale process metadata.

Native bridge may be needed for process/PTY behavior. Keep JNI calls behind a Kotlin interface.

## 7. Foreground execution

Long-running downloads, agent runs, builds, and development servers need an Android-appropriate execution strategy. Use a foreground service where required by platform behavior and user-visible ongoing work. Handle notification permission and Android background execution limits.

Do not rely on WorkManager as the only mechanism for interactive, continuous terminal sessions.

## 8. Project workspace mapping

- Each project has a stable app-managed root.
- The Linux runtime sees that project at a known workspace path.
- Path translation must be centralized.
- Never accept arbitrary absolute paths from model output.
- Resolve symlinks and reject paths escaping the project root unless explicitly authorized.
- Keep runtime system files separate from project data.

## 9. Terminal sessions

- Multiple sessions are desirable, but initial implementation may begin with one active session per project.
- Store session metadata.
- Stream output with bounded buffering.
- Support command cancellation.
- Preserve exit status.
- Provide explicit shell environment and working directory.
- Avoid leaking secrets into process environment unless needed and scoped.

## 10. Preview server

- Start a development server through RuntimeExecutor.
- Detect or configure host/port.
- Bind to a safe interface; avoid exposing services to LAN by default.
- Route the local preview into WebView using an app-controlled bridge or secure local URL strategy.
- Stop the server when the user stops it or project lifecycle policy requires.
- Handle port conflicts and server startup timeouts.

## 11. Resource management

Before installation, estimate storage needs. During execution:
- Monitor disk space.
- Limit concurrent builds.
- Avoid excessive memory retention.
- Provide cancellation.
- Respect battery/thermal constraints.
- Clean only known temporary/cache directories.
- Do not automatically delete user project data.

## 12. Runtime tests

- Unsupported ABI is blocked with a clear message.
- Interrupted download resumes or restarts safely.
- Corrupt checksum is rejected.
- Interrupted extraction does not replace a valid runtime.
- Runtime repair preserves project files.
- Process output streams correctly.
- Process cancellation terminates child processes.
- Long-running task remains visible and controllable.
- Project path traversal is rejected.
- Preview server is isolated and stoppable.
