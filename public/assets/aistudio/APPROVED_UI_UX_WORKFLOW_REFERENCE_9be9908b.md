# AGT Code — UI/UX Workflow Specification

**Document type:** Product UI/UX baseline  
**Status:** Approved baseline for implementation  
**Version:** 1.0  
**Date:** 2026-11-18

---

## 1. Product purpose

AGT Code is an Android application intended to act as a full autonomous AI software engineer. A user can create or open a software project and delegate development tasks to an AI agent that can work with project files, a Linux environment, terminal tools, tests, and preview workflows.

This document records the agreed UI structure and user workflow. It is the reference baseline for future UI design and implementation. Any change to this baseline should be deliberate rather than an incidental redesign.

## 2. Core architecture visible to the user

- **AI connectivity:** JH GetWay Pro is the central AI gateway. The app connects to it; it does not create a separate AI gateway.
- **Agent runtime:** OpenCode is the first supported agent runtime.
- **Source control:** GitHub is the repository integration.
- **Execution environment:** Linux runtime provides the command-line development environment.
- **Extensions:** Skills, MCP Servers, and Plugins are managed through the Extensions area.

The UI specification does not imply that integrations are already implemented. Each integration must be connected to real services and runtime capabilities during engineering.

## 3. First-launch workflow

### 3.1 Setup page

The first launch opens the Setup page rather than the Home page.

The setup process installs or prepares the required environment, including:

1. Linux Runtime
2. OpenCode Agent Runtime
3. Base toolchains
4. GitHub CLI
5. Base Skills and MCP components

The setup UI should show compact, clear progress and per-component states such as waiting, downloading, extracting, installing, completed, or failed. Large runtime assets are downloaded after the APK is installed; they are not bundled into an unnecessarily large APK.

### 3.2 GitHub connection

After setup, show a GitHub Connect page.

- Use browser-based GitHub OAuth and return to the app after authorization.
- Allow the user to skip GitHub connection and continue.
- Show the actual connection state; do not present a simulated connection as a real one.

### 3.3 Home

After GitHub Connect or Skip, open Home.

Home contains:
- Create Project
- Recent Projects

Creating a project begins with entering a project name. Opening a recent project enters its Project Workspace.

## 4. Project Workspace

The Project Workspace is the primary development area.

### 4.1 Header and tool navigation

- Show the project name.
- Place four compact tool selectors in one horizontal row: **Agent**, **Files**, **Terminal**, and **Preview**.
- Keep the back/navigation control as an icon in the top corner.
- Do not add a large brand header inside the workspace.
- Avoid redundant labels, repeated project names, and unnecessary status text.
- Do not add a separate theme toggle in the workspace. Theme is controlled from app Settings.

### 4.2 Agent view

- Agent is the default and primary workspace view.
- Give the conversation the maximum practical available screen area.
- Use a compact composer that grows as the user enters more text.
- Include a plus/attachment menu with:
  - Camera
  - Image
  - File
  - Skills
  - MCP Servers
  - Plugins
- Place a separate model selector beside the plus control.
- The model selector should list models discovered from JH GetWay Pro (for example, through its models API); never hard-code a pretend live model list in production.
- Put the send action inside the composer.
- Support agent progress, tool activity, results, diffs, and approval requests in the conversation.

### 4.3 Files view

Show the current project's file tree in a compact, readable layout. File operations must act on the real project workspace.

### 4.4 Terminal view

Show terminal output cleanly with compact typography. The terminal must be connected to the project's Linux runtime and support real command execution, cancellation, and output streaming.

### 4.5 Preview view

Provide a built-in browser/live preview for the running project. The preview should connect to the project's development server where supported.

## 5. Main app navigation

The app-level bottom navigation contains exactly these three destinations:

1. **Home**
2. **Extensions**
3. **Settings**

Do not confuse this app-level navigation with the four tool selectors inside Project Workspace.

## 6. Extensions

Extensions is the app-level area for discovering and managing:

- Skills
- MCP Servers
- Plugins

Users should be able to browse, inspect, install, configure, enable/disable, update, and uninstall supported extensions as appropriate.

Extension installation must include compatibility, source, license, and security checks. Do not blindly install every available package. Respect extension permissions and require approval for sensitive capabilities.

## 7. Settings information architecture

### 7.1 Settings home

Settings opens to a **single vertical list** of categories. Categories must not be displayed as side-by-side columns or a persistent two-pane layout.

Each row contains a small icon, category title, short description, and a chevron. Selecting a row navigates into that category's own detail page.

### 7.2 Settings categories

The Settings home list contains:

1. **Gateway** — JH GetWay Pro base URL, API key/device token, protocol, connection test, and gateway status.
2. **GitHub** — account connection, repository access, default branch, and Git identity.
3. **Agent** — OpenCode runtime selection, agent step limit, tool timeout, auto-debug behavior, checkpoints, and approval behavior.
4. **Runtime** — Linux environment, OpenCode installation, toolchains, Git, GitHub CLI, package manager, and runtime updates/status.
5. **Models** — models discovered from the gateway, default model, and model capability information where available.
6. **Security** — terminal permissions, destructive-action approvals, GitHub push approval, production deployment approval, and secret/log masking.
7. **Appearance** — Light, Dark, and System theme.
8. **Notifications** — task completion, failures, approval requests, and setup/runtime notifications.
9. **Storage** — workspace data, logs, checkpoints, cache, and storage management.
10. **About** — application version and product/runtime information.

### 7.3 Settings interaction

- Tapping a category opens a dedicated detail page for that category.
- The detail page has a back action to return to the Settings home list.
- Do not show the category list alongside the detail page.
- Keep each detail page focused on its own settings.

## 8. Visual design system

### 8.1 General design direction

The app should look like a professional developer tool: restrained, compact, clear, and information-dense without becoming cluttered.

Use:
- Consistent spacing based on an 8 dp rhythm.
- Compact controls and practical mobile typography.
- Subtle borders and clear surface hierarchy.
- Small, consistent icons.
- Flat, readable rows rather than oversized decorative cards.
- Clear loading, success, warning, error, and disabled states.

Avoid:
- Playful or toy-like styling.
- Oversized colorful cards.
- Excessive gradients and decoration.
- Unnecessary repeated titles or labels.
- Side-by-side Settings navigation.

### 8.2 Light theme — AntiGem Porcelain reference palette

Use the following recorded palette as the Light theme reference:

| Token | Hex | Intended use |
|---|---|---|
| Background | `#F9F9FB` | Main app background |
| Primary | `#D97706` | Primary actions and selected accents |
| Secondary | `#B45309` | Secondary amber accent |
| On Background | `#1C1917` | Main text |
| On Surface Variant | `#78716C` | Secondary text |
| Surface | `#FFFFFF` | Cards, sheets, and raised surfaces |
| Surface Variant | `#E8EAED` | Secondary surface |
| Outline | `#E5E7EB` | Subtle borders and dividers |

This is the agreed Light theme direction based on the AntiGem Porcelain palette reference. Keep the overall UI layout intact when applying the palette.

### 8.3 Dark theme

Dark mode is a separate theme, not an inverted Light theme. Maintain accessible contrast, clear surface levels, and a restrained accent. The exact final Dark palette can be tuned during implementation, but it must remain visually coherent with the Light theme.

### 8.4 Theme selection

Expose **Light**, **Dark**, and **System** under Settings → Appearance. Do not place a separate theme switch in Project Workspace.

## 9. Agent execution and safety UX

The UI should support the following real agent workflow:

1. Understand the user request and project context.
2. Plan and break work into dependent tasks.
3. Read and modify project files.
4. Run commands in the Linux runtime.
5. Observe command output and errors.
6. Debug and retry within configured limits.
7. Run builds and tests.
8. Summarize changes and validation results.
9. Prepare Git changes and request approval for sensitive operations.

Safety and reliability expectations:
- Provide an emergency Stop action for active work.
- Propagate cancellation to running tools/processes.
- Create checkpoints/backups before significant changes.
- Allow interrupted tasks to be resumed where feasible.
- Require approval for destructive operations, GitHub pushes, and production deployments unless an explicitly configured safe policy applies.
- Do not expose gateway tokens or provider secrets in prompts, logs, terminal output, or crash reports.
- Show meaningful progress and errors rather than simulated success states.

## 10. Integration and implementation constraints

- JH GetWay Pro remains the AI connectivity layer.
- OpenCode is the initial agent runtime; other runtimes can be added later without redesigning the core navigation.
- The gateway's actual model catalogue and capabilities must be discovered at runtime.
- Tool calling and streaming capabilities vary by provider/model; verify capabilities rather than assuming all models support every tool.
- Use secure storage for credentials, such as Android Keystore-backed protection.
- A client-side APK cannot safely contain a universal secret; gateway authentication must be designed for an untrusted client environment.
- Long-running runtime and agent tasks need an Android-appropriate foreground execution design.
- Keep the UI honest about unavailable, failed, or unconfigured integrations.

## 11. Demo versus production

A clickable prototype may use sample project names, illustrative files, or simulated statuses to demonstrate navigation. Those are not production behavior.

Production acceptance requires real implementations for:
- Setup downloads, extraction, installation, progress, retry, and failure recovery.
- GitHub OAuth and repository operations.
- Gateway authentication, model discovery, streaming, and tool-call compatibility.
- OpenCode runtime launch and lifecycle management.
- Real project filesystem and terminal operations.
- Build/test execution and output capture.
- Browser/live preview integration.
- Extension discovery, permissioning, installation, and lifecycle.
- Settings persistence and actual application of theme/security/runtime preferences.

Never label a simulated operation as completed or connected in the production app.

## 12. Acceptance checklist

- [ ] First launch opens Setup, not Home.
- [ ] Setup displays per-component installation progress and error states.
- [ ] GitHub OAuth can return to the app; Skip is available.
- [ ] Home shows Create Project and Recent Projects.
- [ ] Project Workspace has one horizontal row of Agent, Files, Terminal, Preview selectors.
- [ ] Agent view gets the largest workspace area.
- [ ] Composer auto-expands and includes attachment, extension, model, and send controls.
- [ ] Files, Terminal, and Preview are connected to the real project/runtime.
- [ ] App-level navigation contains Home, Extensions, Settings only.
- [ ] Extensions separates Skills, MCP Servers, and Plugins.
- [ ] Settings home is a vertical list, not side-by-side panes.
- [ ] Each Settings category opens a separate detail page and returns to the list.
- [ ] Light, Dark, and System themes are available in Appearance.
- [ ] Light theme follows the recorded Porcelain palette.
- [ ] Sensitive actions have clear approval and cancellation UX.
- [ ] Demo-only states are not represented as production integrations.

---

**Change control:** Treat this document as the approved UI/UX baseline. Update it when the product owner explicitly changes a requirement; keep implementation and future mockups aligned with the latest approved version.
