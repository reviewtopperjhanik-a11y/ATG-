# 02 — Reference Source Audit

This document separates facts observed in the three supplied ZIP archives from AGT design decisions.

## 1. JH GetWay Pro (`jh-getway- (3).zip`)

### 1.1 Repository facts observed

The README describes JH GetWay as a personal AI API gateway, key vault, smart router, MCP bridge, and workflow orchestrator for Android.

The supplied source includes Kotlin modules for:
- `gateway/LocalGatewayHttpServer.kt`
- `gateway/GatewayForegroundService.kt`
- `gateway/GatewayApiContract.kt`
- `gateway/SmartRouterEngine.kt`
- `gateway/WorkflowEngine.kt`
- `gateway/CodingAgentIntegration.kt`
- `network/ProviderHttpClient.kt`
- `network/McpHttpClient.kt`
- `network/adapters/`
- `core/security/CryptoManager.kt`
- `core/security/AccessControl.kt`
- `core/security/MaskingUtils.kt`
- Room entities/DAOs/database and DataStore preferences.

### 1.2 API contract observed in source

`GatewayApiContract.supportedEndpoints` lists:
- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /v1/responses`
- `POST /v1/messages`

The same contract lists these as unsupported:
- `/v1/embeddings`
- `/v1/images/generations`
- `/v1/audio/transcriptions`
- `/v1/audio/speech`

Do not assume unsupported endpoints are available.

### 1.3 README-described capabilities

- Ktor CIO local HTTP gateway, default loopback address `127.0.0.1:8787`.
- Bearer authentication and device scopes `CHAT`, `MODELS_READ`, `ADMIN`.
- Model allowlists and token-bucket rate limiting.
- Remote Gateway HTTPS client mode.
- Android Keystore AES-256-GCM credential encryption.
- Multi-key provider selection: Priority, Round Robin, Failover.
- Smart routing modes: Automatic, Lowest Cost, Fastest, Priority Order, Round Robin, Manual.
- MCP discovery over Streamable HTTP/SSE JSON-RPC 2.0.
- MCP dangerous-tool approval policy `ASK_BEFORE_RUNNING`.
- Visual DAG workflow node types include INPUT, PROMPT, LLM_CALL, CONDITION, ROUTER, MCP_TOOL, HTTP_REQUEST, CODE_TRANSFORM, MERGE, OUTPUT.
- DAG validation, cycle detection, bounded parallel fan-out, retries, cancellation, branch-skip propagation, and versioning.
- Provider families named in README include OpenAI, Anthropic, Gemini, OpenRouter, Groq, DeepSeek, Mistral, Cohere, Hugging Face, Ollama, LM Studio, and custom OpenAI-compatible endpoints.

### 1.4 Important integration constraints

- JH GetWay Pro is the designated AI connectivity layer for AGT Code.
- Query `/v1/models` for the real model catalogue; do not hard-code fake model availability.
- The actual contract must be verified against the running gateway version before implementation.
- Provider/model tool-call and streaming support must be capability-tested.
- The Gateway's DAG workflow engine is not the same as AGT's software-engineering task orchestrator.
- The gateway's own README says STDIO MCP subprocess spawning is not supported inside the Android process sandbox; its MCP bridge uses HTTP transports.

### 1.5 License observation

No top-level `LICENSE` file was present in the supplied JH GetWay ZIP root during this inspection. This does **not** establish that the repository has no license elsewhere or online. Confirm the repository's current license and ownership terms before copying or redistributing code.

## 2. Mobile Harness (`Mobile-Harness-main.zip`)

### 2.1 Repository facts observed

The README describes an Android coding environment with an Ubuntu 20.04 LTS userspace running in PRoot on ARM64 Android.

Relevant Kotlin files observed:
- `runtime/RuntimeInstaller.kt`
- `runtime/RuntimeSetupService.kt`
- `runtime/RuntimeExecutionService.kt`
- `runtime/NativeSpawnProcess.kt`
- `runtime/RuntimeBridge.kt`
- `runtime/AgentDriver.kt`
- `runtime/WorkspaceCheckpoints.kt`
- `runtime/RootfsRepair.kt`
- `runtime/RuntimeCompatibility.kt`
- `runtime/AndroidBuildSupport.kt`
- Agent-specific runtime bridge files.

Native C/C++ source exists under `app/src/main/cpp/`, including process launcher/spawn components.

### 2.2 Installer behavior observed

`RuntimeInstaller`:
- Checks ARM64 compatibility.
- Checks embedded PRoot availability.
- Uses an Ubuntu rootfs directory and staging directory.
- Obtains a runtime bundle, extracts a Zstandard-compressed tar archive, and activates it using a repair/backup strategy.
- Uses marker files to track rootfs, core tools, language tools, and agent versions.
- Includes progress events for stages, commands, output, downloads, command completion, and completion.
- Installs or validates Node.js and Git as core tools.
- Supports optional development stacks.

The supplied source indicates staged install and recovery behavior; it should be studied before adapting.

### 2.3 Runtime architecture observations

- Android Kotlin layer coordinates runtime and UI.
- Native process bridge is used for Linux process execution.
- Foreground services handle long-running execution.
- Runtime bridge abstraction separates the app from agent-specific launch details.
- Workspace checkpoints provide file snapshot/recovery functionality.
- The README warns that PRoot is not a virtualization boundary or hardened security jail.

### 2.4 License observations

The root `LICENSE` is MIT. The repository also includes separate bundled license notices for components such as PRoot (GPL-2.0) and Talloc (LGPL-3.0-or-later). Each reused dependency and binary must retain its own license obligations.

## 3. AntiGem (`antigem-master (2).zip`)

### 3.1 Repository facts observed

The README describes:
- Agent chat and multiple agent backends.
- Local terminal and development environment.
- Code editor, Git, browser/live preview.
- Skills and MCP integrations.
- Custom plugins with a manifest and JavaScript worker.
- Plugin tools exposed to the agent.
- Memory, checkpoints, approvals, and agent history.
- A Go IDE Bridge under `agy_ide_bridge/`.

Observed Android source areas include:
- `data/agent/opencode/`
- `data/agent/claude/`
- `data/agent/codex/`
- `data/agent/agy/`
- `data/skills/`
- `data/memory/`
- `ui/agents/`
- `ui/components/`
- `ui/settings/`
- `theme/AppTheme.kt`

Observed Go Bridge package areas include checkpoint, agent/protocol, plugin, security, and HTTP handling packages.

### 3.2 Runtime nuance

AntiGem README describes its recommended `termux` variant as using a self-contained Termux-compatible Linux environment without PRoot. It separately describes a standard variant and server mode. The README also discusses Ubuntu PRoot for particular Android development/reverse-engineering workflows. Therefore, do not describe AntiGem's entire normal terminal as PRoot-based.

### 3.3 Agent protocol lesson

AntiGem maintains agent-specific backends and transcript/protocol handling. Its UI represents tool calls, approvals, progress, and agent output as structured events rather than treating all output as plain text.

AGT should use the same general principle, but initially implement only the OpenCode adapter.

### 3.4 Plugin lesson

AntiGem's README describes plugins with a `manifest.json`, JavaScript worker, MCP tools, and optional Markdown skills. It supports installation from ZIP or GitHub repository and plugin lifecycle operations. This is a useful architectural reference, not permission to copy GPL-licensed implementation.

### 3.5 Theme facts observed

The supplied `AppTheme.kt` defines a Porcelain Light theme. Recorded values include:
- Background: `#F9F9FB`
- Primary: `#D97706`
- Secondary: `#B45309`
- OnBackground: `#1C1917`
- OnSurfaceVariant: `#78716C`
- Surface: `#F9F9FB`
- SurfaceVariant: `#E8EAED`
- Outline: `#E5E7EB`

The product UI specification separately uses white `#FFFFFF` as a card/raised surface.

### 3.6 License observation

The root `LICENSE` is GNU GPL version 3. Do not copy or adapt AntiGem code into AGT without a deliberate GPL compliance decision. Prefer independently implementing the desired UX and architecture concepts. Audit bundled assets and dependencies separately.

## 4. Cross-project synthesis

| AGT concern | Best reference | AGT decision |
|---|---|---|
| AI provider connectivity | JH GetWay Pro | Use existing gateway; do not build another |
| Model discovery and routing | JH GetWay Pro | Query gateway and respect capabilities |
| Linux rootfs installation | Mobile Harness | Adapt staged installer/recovery concepts |
| Android process execution | Mobile Harness | Use a runtime/process abstraction and native bridge as needed |
| Long-running work | Mobile Harness | Foreground execution and cancellation |
| Checkpoints | Mobile Harness + AntiGem | Implement project snapshots/diffs/recovery |
| Agent protocol UI | AntiGem | Structured OpenCode events, tool cards, approvals |
| Extension concepts | AntiGem + Gateway MCP bridge | Build a secured registry/host with explicit permissions |
| Light theme | AntiGem | Use approved Porcelain palette |
| Product workflow | AGT requirements | Implement agreed Setup → Home → Workspace UX |

## 5. Source audit limitations

- This is a source-level architectural review, not a full security audit or line-by-line correctness certification.
- Buildability, runtime compatibility, provider behavior, and Android version support must be verified with actual builds and device tests.
- No code should be copied before license and dependency review.
