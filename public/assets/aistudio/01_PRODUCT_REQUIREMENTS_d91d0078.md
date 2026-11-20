# 01 — Product Requirements

## 1. Product definition

**AGT Code** is an Android application intended to function as a full autonomous AI software engineer. It enables a user to create a project or work inside an existing repository, then delegate software engineering tasks to an AI agent operating against a real project workspace and Linux execution environment.

The product is not merely an AI chat interface, code-completion tool, or terminal wrapper.

## 2. Product goals

- Create new software projects from natural-language requirements.
- Inspect and understand existing repositories before modifying them.
- Plan multi-step engineering work and execute dependent tasks.
- Read, create, edit, move, and delete project files.
- Run real shell commands and development servers in a Linux environment.
- Build, test, diagnose failures, and iterate on fixes.
- Show changes, diffs, tool activity, progress, and approval requests clearly.
- Integrate with GitHub for repository workflows.
- Use JH GetWay Pro for model/provider connectivity.
- Start with OpenCode as the first agent runtime.
- Support installable Skills, MCP Servers, and Plugins.
- Keep the Android app usable during long-running setup and coding operations.

## 3. Primary user journeys

1. Install APK → first-launch Setup → install runtime and tools → GitHub Connect or Skip → Home.
2. Home → Create Project → Project Workspace → Agent request → plan → code → build/test → result.
3. Home → Recent Project → inspect existing repository → make requested changes → validate → prepare Git changes.
4. Extensions → discover and install a Skill, MCP server, or Plugin.
5. Settings → choose a category → edit that category on its own detail page.

## 4. Functional requirements

### FR-001 First-launch setup
The first app launch must open Setup, not Home. Runtime assets must be downloaded after APK installation where practical. Setup must display component-level progress, stage, success, failure, retry, and cancellation state.

### FR-002 Runtime installation
The app must provision the selected Linux runtime and required base tools. Runtime installation must be resumable or safely repairable after interruption. The exact distro and package bundle must be validated against device architecture and resource constraints.

### FR-003 GitHub authorization
The app must support browser-based GitHub OAuth and app return/deep-link handling. GitHub connection may be skipped during onboarding and completed later.

### FR-004 Home
Home must provide Create Project and Recent Projects.

### FR-005 Project creation
The user can enter a project name and create a local project workspace. Project metadata and paths must be persisted.

### FR-006 Existing repository
The app must support connecting/cloning an existing GitHub repository and opening an existing local project. Repository discovery and clone progress must be visible.

### FR-007 Project Workspace
The workspace must contain four horizontal tool selectors: Agent, Files, Terminal, Preview. Agent is the default view.

### FR-008 Agent interaction
The user can submit a natural-language task, attach supported context/files/images, select a gateway model, and observe streaming agent activity.

### FR-009 File operations
The Agent must access only the selected project and explicitly authorized paths. File changes must be inspectable and recoverable.

### FR-010 Terminal
The app must provide real Linux command execution, output streaming, process termination, and session management.

### FR-011 Preview
The app must provide an embedded browser/live preview for supported local development servers.

### FR-012 Extensions
Extensions must organize Skills, MCP Servers, and Plugins separately. Users must be able to inspect, install, configure, enable/disable, update, and uninstall supported extensions.

### FR-013 Settings
Settings home must be a vertical category list. Every category opens a separate detail page.

### FR-014 Appearance
Appearance must support Light, Dark, and System modes. Light mode uses the approved Porcelain/Amber palette.

### FR-015 Approval and safety
Destructive operations, GitHub push, production deployment, and sensitive tool actions must have explicit approval gates according to policy.

### FR-016 Task recovery
The app should preserve task state, logs, checkpoints, and enough execution metadata to resume or diagnose interrupted work.

### FR-017 Stop
An active agent task must expose a Stop action that cancels orchestration and propagates cancellation to active tools/processes.

### FR-018 Usage visibility
Where available, show model, token usage, latency, and estimated/actual cost with provenance clearly distinguished.

## 5. Non-functional requirements

- **Reliability:** interrupted downloads, installs, and agent tasks must fail safely.
- **Security:** protect credentials, isolate project access, validate endpoints, and gate risky operations.
- **Performance:** stream output; avoid blocking the main UI thread; use bounded buffers.
- **Resource awareness:** account for ARM64 architecture, RAM, storage, battery, thermal state, and Android background restrictions.
- **Maintainability:** use modular boundaries and explicit interfaces.
- **Accessibility:** adequate contrast, touch targets, scalable text, and clear status labels.
- **Localization:** English and Bengali are desired; do not hard-code user-facing strings.
- **Observability:** structured logs with secret redaction and useful error codes.
- **Honesty:** never display simulated success as a real connected or installed state.

## 6. Out of scope for the first release

- Building or training a proprietary foundation model.
- Replacing JH GetWay Pro with a second gateway.
- Supporting every agent runtime on day one.
- Unrestricted arbitrary plugin execution without a permission model.
- Guaranteeing every upstream model supports tool calling.
- Production deployment to every hosting provider without provider-specific integration.

## 7. Initial release priority

**P0:** Setup, gateway connection, OpenCode runtime, project workspace, filesystem, terminal, agent loop, cancellation, checkpoints, build/test, Settings, and basic GitHub operations.

**P1:** Skills/MCP registry, built-in preview, task resume, richer diffs, token/cost telemetry, extension lifecycle.

**P2:** Additional agent runtimes, specialist subagents, advanced plugin SDK, remote/server execution, team collaboration.
