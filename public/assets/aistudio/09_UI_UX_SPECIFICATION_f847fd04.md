# 09 — Approved UI/UX Specification

**Status:** Approved baseline. Preserve this information architecture unless the product owner explicitly changes it.

## 1. Design direction

Professional developer-tool UI: compact, restrained, readable, information-dense, and consistent. Avoid playful styling, oversized colorful cards, excessive gradients, and redundant headings.

Use a consistent spacing rhythm based on 8 dp, subtle borders, compact icons, and practical mobile typography.

## 2. First-launch flow

Setup → GitHub Connect (or Skip) → Home.

Setup must be the first screen on a fresh install. Normal users must not see manual `Runtime Manifest URL`, `GitHub OAuth Client ID`, or `GitHub OAuth Client Secret` text fields on Setup, GitHub Connect, or Settings.

## 3. Main app navigation

Exactly three app-level destinations:
- Home
- Extensions
- Settings

## 4. Home

- Create Project.
- Recent Projects.
- Project rows should be compact and information-rich.
- Creating a project asks for a project name.
- Opening a project opens Project Workspace.

## 5. Project Workspace

### Header
- Project name.
- Compact back/navigation icon.
- No oversized product branding.
- Avoid duplicate project titles and redundant status labels.

### Four horizontal workspace selectors
1. Agent
2. Files
3. Terminal
4. Preview

These appear side by side in one horizontal row.

### Agent
- Default workspace tab.
- Conversation occupies maximum practical space.
- Compact composer expands with text.
- Plus menu: Camera, Image, File, Skills, MCP Servers, Plugins.
- Separate model selector beside plus.
- Send action inside composer.
- Models are fetched from JH GetWay Pro.
- Tool activity, approval cards, diffs, and subagent/task progress use structured compact UI.

### Files
Compact project tree and file editor/navigation.

### Terminal
Compact terminal output, readable typography, session controls, Stop action.

### Preview
Built-in browser/live preview connected to the project's development server.

### Workspace theme
No separate theme switch in Project Workspace. Theme is global under Settings → Appearance.

## 6. Extensions

Three categories:
- Skills
- MCP Servers
- Plugins

Use compact rows/cards with source, version, state, and permissions. Do not make the catalog visually noisy.

## 7. Settings information architecture

### Settings home — vertical list only

The Settings home must show a single vertical list. Do not place categories in a side-by-side column beside their detail content.

Rows:
1. Gateway
2. GitHub
3. Agent
4. Runtime
5. Models
6. Security
7. Appearance
8. Notifications
9. Storage
10. About

Each row contains:
- Small icon.
- Category title.
- Short description.
- Chevron.

### Detail pages

Tapping a row navigates to that category's own page. Back returns to the list. The category list must not remain in a persistent side panel.

### Settings content

- **Gateway:** base URL, device token, protocol, connection test, health.
- **GitHub:** one-tap Connect GitHub (PKCE OAuth via build-time `AGT_GITHUB_OAUTH_CLIENT_ID`), connection status/disconnect/reconnect, scope selection, default branch, Git identity.
- **Agent:** OpenCode, step limit, timeout, auto-debug, checkpoints, approval policy.
- **Runtime:** one-tap Install/Update Runtime (auto-configured via `AGT_RUNTIME_MANIFEST_URL`), Linux state, installed ABI/version, health check, repair/remove, licenses.
- **Models:** live gateway models, default model, capability information.
- **Security:** permissions, destructive-operation approval, push/deploy approval, secret masking.
- **Appearance:** Light, Dark, System.
- **Notifications:** task completion, failures, approval, setup/runtime events.
- **Storage:** project data, logs, checkpoints, cache.
- **About:** app version, licenses, runtime details.

## 8. Light theme — Porcelain reference

Recorded AntiGem `AppTheme.kt` palette:

| Token | Hex |
|---|---|
| Background | `#F9F9FB` |
| Primary | `#D97706` |
| Secondary | `#B45309` |
| OnBackground | `#1C1917` |
| OnSurfaceVariant | `#78716C` |
| Surface | `#F9F9FB` |
| SurfaceVariant | `#E8EAED` |
| Outline | `#E5E7EB` |
| Raised/card surface | `#FFFFFF` |

Use `#FFFFFF` for white cards/raised surfaces as the agreed AGT design treatment.

## 9. Dark theme

Dark theme is separate, not a simple inversion. It should use a dark neutral background, elevated surfaces, readable text, subtle outlines, and a restrained warm accent. Final exact dark tokens can be tuned during implementation and accessibility testing.

## 10. Theme behavior

- Light
- Dark
- System

Persist the user's selection. System follows Android's system appearance.

## 11. Responsive and accessibility behavior

- Avoid horizontal overflow on small screens.
- Use scrollable vertical content where needed.
- Ensure accessible contrast.
- Use minimum practical touch targets.
- Support font scaling.
- Show loading, empty, error, success, and disabled states.
- Do not use color as the only status indicator.
