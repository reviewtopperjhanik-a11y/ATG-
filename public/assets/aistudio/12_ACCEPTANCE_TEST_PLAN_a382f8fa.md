# 12 — Acceptance Test Plan

## 1. Setup and onboarding

- [ ] Fresh install opens Setup.
- [ ] Unsupported architecture is clearly rejected.
- [ ] Download progress is visible.
- [ ] Download integrity is verified.
- [ ] Interrupted download can retry/resume safely.
- [ ] Interrupted extraction does not corrupt an existing runtime.
- [ ] Runtime health checks run before completion is shown.
- [ ] GitHub Connect opens OAuth flow.
- [ ] OAuth return is validated.
- [ ] Skip proceeds to Home.

## 2. Home and project management

- [ ] Home displays Create Project and Recent Projects.
- [ ] Project name validation prevents invalid paths.
- [ ] New project creates a persistent workspace.
- [ ] Recent project opens the correct workspace.
- [ ] Existing repository can be cloned/imported.
- [ ] Project paths remain confined to the project root.

## 3. Workspace navigation

- [ ] Agent, Files, Terminal, Preview appear in one horizontal row.
- [ ] Agent is the default view.
- [ ] Workspace back control works.
- [ ] No separate workspace theme toggle appears.
- [ ] Main app navigation remains Home, Extensions, Settings only.

## 4. Agent

- [ ] Composer starts compact and expands with text.
- [ ] Plus menu contains Camera, Image, File, Skills, MCP Servers, Plugins.
- [ ] Model selector is separate from plus.
- [ ] Model list comes from real gateway discovery.
- [ ] Streaming response renders incrementally.
- [ ] Tool requests render as structured events.
- [ ] Approval prompts appear for gated actions.
- [ ] Stop cancels active work.
- [ ] Task state survives expected app lifecycle events.

## 5. Files and checkpoints

- [ ] Project tree reflects real files.
- [ ] File open/read/write works.
- [ ] File changes appear in diff.
- [ ] Checkpoint is created before configured high-impact changes.
- [ ] Restore previews impact and requires confirmation.
- [ ] Path traversal and symlink escape are rejected.

## 6. Terminal and runtime

- [ ] Runtime status is real.
- [ ] Commands run in the selected project directory.
- [ ] stdout and stderr stream.
- [ ] Exit code is captured.
- [ ] Timeout works.
- [ ] Stop terminates process and child processes.
- [ ] Long-running task remains user-visible.
- [ ] Resource exhaustion is handled gracefully.

## 7. Build/test loop

- [ ] Agent detects project stack and build commands.
- [ ] Build is actually executed.
- [ ] Test command is actually executed.
- [ ] Failures are returned to the agent.
- [ ] Fix/retry loop is bounded.
- [ ] Final report accurately lists executed checks and outcomes.

## 8. Gateway

- [ ] Health check succeeds with valid configuration.
- [ ] Invalid token fails safely.
- [ ] Scope errors are handled.
- [ ] `/v1/models` is used for discovery.
- [ ] Unsupported endpoint/capability is reported.
- [ ] Streaming and tool-call behavior are tested per model.
- [ ] Credentials never appear in logs.

## 9. GitHub

- [ ] OAuth scopes are least privilege.
- [ ] Repository listing and clone work.
- [ ] Git status and diff are correct.
- [ ] Commit identity is configurable.
- [ ] Push requires approval.
- [ ] Remote operation errors are visible.

## 10. Extensions

- [ ] Skills, MCP Servers, Plugins are separate categories.
- [ ] Manifest and license are inspected.
- [ ] Package integrity is checked.
- [ ] Permissions are shown before installation.
- [ ] Disabled extensions cannot execute tools.
- [ ] Dangerous MCP tools require approval.
- [ ] Uninstall revokes tool registration and permissions.

## 11. Settings and appearance

- [ ] Settings home is a vertical list.
- [ ] Every category opens a separate detail page.
- [ ] Back returns to the Settings list.
- [ ] No side-by-side persistent settings pane.
- [ ] Light, Dark, System modes work.
- [ ] Light mode uses approved Porcelain tokens.
- [ ] Theme preference persists.

## 12. Security and release

- [ ] Secrets use secure storage.
- [ ] No embedded production credentials.
- [ ] Dangerous operations require approval.
- [ ] Runtime bundle checksum/signature validation works.
- [ ] Logs redact secrets.
- [ ] License notices are complete.
- [ ] Release signing keys are managed outside source control.
- [ ] Supported Android devices pass smoke tests.
