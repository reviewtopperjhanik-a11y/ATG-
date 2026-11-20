# 11 — Implementation Plan for Google AI Studio

## 1. Purpose

Use this document as the implementation sequence and working contract when supplying the AGT Code documentation pack to Google AI Studio.

Google AI Studio is an assisted development environment, not a substitute for building, running, testing, and inspecting the Android app on real devices.

## 2. Rules for AI-assisted implementation

1. Read the entire documentation pack before generating code.
2. Treat `09_UI_UX_SPECIFICATION.md` as the approved UI baseline.
3. Treat `02_REFERENCE_SOURCE_AUDIT.md` as the boundary of what was observed in the supplied source archives.
4. Do not invent existing APIs or pretend integrations are connected.
5. Do not copy GPL-licensed AntiGem code.
6. Do not create a second AI gateway; integrate JH GetWay Pro.
7. Implement OpenCode first; preserve an AgentRuntime interface for future runtimes.
8. Generate complete, compiling files for each implementation step.
9. Do not leave TODO, placeholder, mock-success, or fake integration behavior in production paths.
10. If a detail is unknown, inspect the relevant source or ask a focused question rather than silently guessing.
11. Keep secrets out of code and build files.
12. After each milestone, run Gradle build/tests and report exact results.
13. Keep all UI navigation and settings behavior aligned with the approved spec.
14. Do not redesign approved screens without an explicit product decision.

## 3. Recommended technical baseline

Proposed stack, to validate against current Android tooling:
- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel + StateFlow
- Room
- DataStore
- Android Keystore
- OkHttp/Ktor as appropriate
- Foreground service for long-running work
- PRoot-based Linux runtime initial path
- JNI/native process bridge behind a Kotlin abstraction
- WebView for built-in preview

Dependency versions must be selected from current stable compatible releases at implementation time.

## 4. Milestone sequence

### M0 — Repository and build foundation
Deliver:
- Android project scaffold.
- Build configuration.
- App entry point.
- Navigation shell.
- Theme tokens.
- Dependency injection decision.
- Unit test baseline.

Acceptance:
- Debug APK builds.
- Unit tests run.
- No secrets in source.
- Package/module naming is consistent.

### M1 — Approved UI shell
Deliver:
- Setup page.
- GitHub Connect page.
- Home.
- Project Workspace with horizontal Agent/Files/Terminal/Preview tabs.
- Extensions with Skills/MCP/Plugins.
- Settings vertical list and separate detail pages.
- Light/Dark/System theme.
- Porcelain Light palette.

Acceptance:
- Every navigation path works.
- Settings details open as separate pages.
- Workspace layout matches spec.
- UI has loading/empty/error states.

### M2 — Local persistence and projects
Deliver:
- Room entities/DAOs.
- Project create/open/recent.
- Workspace directory mapping.
- Settings persistence.
- Project-root path validation.

Acceptance:
- Projects survive app restart.
- Recent projects list is correct.
- Path traversal tests pass.

### M3 — JH GetWay Pro connection
Deliver:
- Gateway settings and secure token storage.
- Health check.
- Model discovery.
- Streaming client.
- Error mapping.
- Capability representation.

Acceptance:
- Connect to a real configured gateway.
- Model list comes from `/v1/models`.
- Streaming works or reports precise unsupported capability.
- Credentials are redacted.

### M4 — Linux runtime installer
Deliver:
- Runtime manifest.
- Compatibility checks.
- Download/checksum/extraction.
- Staging/activation/repair.
- Progress UI.
- Base tools.

Acceptance:
- Install on supported ARM64 test device.
- Corrupt bundle rejected.
- Interrupted install safely recovers.
- Runtime health checks pass.

### M5 — Process and terminal
Deliver:
- RuntimeExecutor.
- Native process bridge if needed.
- Terminal session UI.
- Streaming output.
- Cancellation and timeout.
- Foreground execution.

Acceptance:
- Run shell commands in project directory.
- Stream stdout/stderr.
- Stop command and child processes.
- App remains responsive.

### M6 — OpenCode runtime integration
Deliver:
- OpenCode installation/update.
- AgentRuntime adapter.
- Gateway configuration adapter.
- Session lifecycle.
- Structured event parser.
- Chat UI with tool activity and approval.

Acceptance:
- Submit a real task through OpenCode.
- Tool events appear correctly.
- Unsupported model capability is surfaced.
- Stop cancels the session.

### M7 — Orchestrator and quality loop
Deliver:
- Task state machine.
- Repository inspection.
- Plan and acceptance criteria.
- Tool Broker.
- Checkpoints/diffs.
- Build/test/debug loop.
- Task summary and recovery.

Acceptance:
- Complete a small end-to-end coding task in a test repository.
- Build/test results are real.
- Checkpoint restore works.
- Dangerous actions require approval.

### M8 — GitHub
Deliver:
- OAuth.
- Clone/list repositories.
- Git status/diff/commit.
- Push/PR with approval.

Acceptance:
- User can clone a test repository.
- Changes can be reviewed and committed.
- Push is approval-gated.

### M9 — Extensions and preview
Deliver:
- Skills registry.
- HTTP MCP client/registry.
- Preview server detection and WebView.
- Plugin host only after security design is approved.

Acceptance:
- Install and use a trusted Skill.
- Connect a test MCP server.
- Preview a local web server.
- Extension permissions are enforced.

### M10 — Hardening and release
Deliver:
- Security review.
- Performance and resource tests.
- Accessibility.
- Bengali/English localization.
- Crash/error handling.
- License notices.
- Release signing and update plan.

Acceptance:
- Release build is signed securely.
- No credentials are embedded.
- All P0 tests pass on supported devices.

## 5. Suggested AI Studio master prompt

Use this prompt after uploading all documentation:

> You are the senior Android systems architect and implementation engineer for AGT Code. Read every uploaded Markdown specification before writing code. The product is a full autonomous AI software engineer for Android, not a chatbot. JH GetWay Pro is the existing AI gateway; do not build a second gateway. OpenCode is the first Agent Runtime. The Linux runtime and process execution must be real, secure, cancellable, and tested. Follow the approved UI navigation exactly: Setup → GitHub Connect/Skip → Home; app navigation Home/Extensions/Settings; Project Workspace tabs Agent/Files/Terminal/Preview in one horizontal row; Settings is a vertical category list and each category opens a separate detail page. Use the approved Porcelain Light palette. Do not copy AntiGem GPL-3.0 source code. Distinguish verified source facts from proposed AGT decisions. Do not fabricate APIs, connected states, model names, build results, or successful operations. Implement only the requested milestone, provide complete files, run available tests/builds, and report exact changed files and verification results. If a critical implementation detail is missing, inspect the uploaded sources or ask one focused question.

## 6. Working cadence

For every milestone:
1. Confirm the milestone scope.
2. Inspect current project files.
3. Implement complete changes.
4. Build and run tests.
5. Fix errors before moving forward.
6. Report files changed and test output.
7. Update documentation if architecture decisions change.

Do not ask for approval after every small coding action. Ask only for irreversible or security-sensitive actions, or when a required product decision is genuinely missing.
