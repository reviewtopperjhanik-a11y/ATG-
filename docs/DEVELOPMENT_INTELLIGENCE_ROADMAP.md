# AGT Code — Master Engineering & Development Intelligence Roadmap

This roadmap aligns AGT's Android on-device Linux execution layer with a native Kotlin + Room **Development Intelligence Core** (inspired by **Aider Repo Map**, **LangGraph** state graphs, **DBOS** SQLite durable execution, and **Model Handoff** protocols — implemented natively without heavy Python/Node daemon dependencies).

---

## Architecture Principles

1. **Pure Kotlin + Room Intelligence Core (No Heavy External Orchestrator Daemons)**
   - Rather than embedding LangGraph, Restate, or DBOS runtimes directly on Android, AGT adopts their core engineering patterns natively over Android's **Room (SQLite)** database, **Kotlin Coroutines**, and **ForegroundService**.
2. **Strict Separation of 4 Data Layers** (preventing context bloat and stale-summary corruption):
   - **Chat History (`chat_messages`)**: Human-readable UI transcript only; never replayed raw across model switches.
   - **Persistent Task State (`task_state_snapshots`)**: Authoritative machine-readable objective, step plan, step statuses, architectural decisions, pending approvals, and active resume cursor.
   - **Project Memory (`project_memory_entries`)**: Compact, curated per-project conventions, detected build/validation commands, recurring error fixes, and cached Repo Map index.
   - **Execution Checkpoints & Flight Recorder (`checkpoints` + `flight_recorder_events`)**: Pre-task workspace snapshots paired with an append-only SQLite event journal of every tool call, command, exit code, file change, and failure.
3. **Model-Agnostic Continuity**:
   - Switching models (e.g., Gemini -> Grok -> Claude) or switching execution backends (`OpenCode` <-> `Pi Coding Agent`) transfers a compact, deterministic **Model Handoff Packet** rather than thousands of raw chat turns.

---

## Phased Implementation Plan

### Phase 0 — Completed Foundations
- [x] **Verify-First Runtime Installer**: Verify existing on-device rootfs before downloading; hide missing component breakdown until checked; automatic zero-input upstream bootstrap (Ubuntu 24.04 + Node.js 22 + GitHub CLI + OpenCode) with GitHub Web release fallback on API rate limits.
- [x] **Symlink & Canonical Path Confinement Fix**: Fixed `/data/user/0` vs `/data/data` mismatch during `mergeDirectoryInto` so Node.js `npm` / `npx` symlinks extract cleanly while rejecting escaping symlinks.
- [x] **Android 10+ PRoot Integration**: Bundled patched `libproot.so`, `libtalloc.so`, `libproot_loader.so`, and `libproot_loader32.so` (`arm64-v8a` & `x86_64`) via `jniLibs` with `useLegacyPackaging = true`, `--link2symlink`, `--kill-on-exit`, `PROOT_TMP_DIR`, and `PROOT_LOADER`.
- [x] **Live Agent Verification & UI Cleanup**: Wired `opencode --version` / `pi --version` execution checks in `Verify` and deduplicated error rendering in Agent settings cards.

---

### Phase 1 — PRoot Runtime Hardening, Live Health & Git Toolchain (COMPLETED)
**Goal:** Guarantee rock-solid on-device Linux command execution across Android kernels so Node.js, npm, Git, OpenCode, Pi, Terminal, and Preview run reliably.

- [x] **1.1 PRoot Host Compatibility Hardening (`ProcessBridge`, `LocalRuntimeExecutor`)**:
  - Bind `<runtime>/tmp:/dev/shm` overlay when host `/dev/shm` is unavailable/read-only (needed by Bun/Node/V8 tools like OpenCode & Vite).
  - Detect kernel seccomp/ptrace failures (`PROOT_NO_SECCOMP=1` automatic fallback toggle persisted across restarts).
  - Ensure `/etc/hosts` (`127.0.0.1 localhost`, `::1 localhost`), `/etc/resolv.conf`, `apt` partial dirs, and `ca-certificates.crt` are always provisioned on install, recovery, and repair.
- [x] **1.2 Live PRoot Execution Health Probe (`RuntimeInstallerRepository`)**:
  - Extend runtime health checks to execute a real PRoot smoke probe (`node --version && npm --version`, plus `git --version` status) when `libproot.so` is active, instead of relying solely on `File.exists()`.
- [x] **1.3 Automatic `git` Toolchain Setup inside Ubuntu Rootfs**:
  - Provision `git` inside PRoot (`apt-get update && apt-get install -y -o APT::Sandbox::User=root --no-install-recommends git`) best-effort during setup, repair, and automatically on-demand when `GitRepositoryService` clones/inits a repo.

---

### Phase 2 — 4-Layer Room Schema & Flight Recorder (DBOS-Inspired) (COMPLETED)
**Goal:** Persist every workflow step, decision, and tool execution transactionally in Room so tasks survive app kills, network drops, and model switches.

- [x] **2.1 Room Schema Migration (v4 → v5 / `MIGRATION_4_5`)**:
  - Add `TaskStateSnapshotEntity` (active step index, serialized plan steps, key decisions, modified files, resume payload, last model/backend).
  - Add `ProjectMemoryEntity` (curated project rules, stack/validation metadata, repo map cache, known fixes).
  - Add `FlightRecorderEventEntity` (append-only log: `taskId`, `seq`, `eventType`, `toolName`, `commandOrPath`, `outcome`, `exitCode`, `outputExcerpt`, `modelId`, `timestampMs`).
  - Keep `ChatMessage` UI transcripts decoupled from agent handoff state.
- [x] **2.2 Flight Recorder Engine & Inspector UI**:
  - Record every `AgentOrchestrator` state transition, `ToolBroker` check, `ValidationRunner` run, and runtime error in real time.
  - Surface a filterable **Flight Recorder Timeline** inside Workspace so users can inspect failures and exact resume points.

---

### Phase 3 — Aider-Style Kotlin Repo Map Engine (COMPLETED)
**Goal:** Replace raw file-list dumps with a compact, dependency-ranked symbol map inside a strict token budget.

- [x] **3.1 Multi-Language Lightweight Symbol Extractor**:
  - Parse classes, interfaces, objects, functions, methods, and imports across Kotlin, Java, TypeScript/JavaScript, Python, Go, and Rust files (no external native tree-sitter binary required initially).
- [x] **3.2 Reference Graph Scoring & Token Budgeting**:
  - Score files and symbols by cross-file references + recent git/checkpoint edits + entry points.
  - Render a compact `RepoMap` within a configurable token budget (e.g., 1,500–3,000 tokens) cached in `ProjectMemoryEntity` and invalidated incrementally on file edits.

---

### Phase 4 — Model Handoff Protocol & Durable Task Resume (COMPLETED)
**Goal:** Seamless continuation across models (Gemini <-> Grok <-> Claude) and agent backends (`OpenCode` <-> `Pi`) without context bloat or lost progress.

- [x] **4.1 Structured `ModelHandoffPacket` & `HandoffContextBuilder`**:
  - Assemble compact handoff context containing:
    1. Objective & Acceptance Criteria
    2. Current Plan Step & Completed/Remaining Steps
    3. Architectural Decisions & Constraints (from `ProjectMemory`)
    4. Recent Workspace Diff & Unresolved Validation Failures (from `FlightRecorder` + `CheckpointManager`)
    5. Compact `RepoMap` + Targeted Code Slices for affected files
- [x] **4.2 Durable `AgentOrchestrator` Resume Engine**:
  - Persist state transitions at every phase (`CONTEXT_LOADING`, `PLANNING`, `EXECUTING`, `VALIDATING`, `FIXING`, `PAUSED`).
  - Allow resuming a `PAUSED`, interrupted, or failed task with the same or a **newly selected model/agent** directly from the last durable checkpoint & step cursor.

---

### Phase 5 — Long-Term Project Knowledge Retrieval (COMPLETED)
**Goal:** Scale multi-session knowledge across large, long-lived repositories.

- [x] Add SQLite **FTS5** full-text indexing (`project_knowledge_fts` with Porter stemming + BM25 ranking and automatic FTS4 fallback) and **temporal validity / superseding rules** (`validFromEpochMs`, `validUntilEpochMs`, `supersededById` inspired by Mem0 / Graphiti) over `ProjectKnowledgeEntity`, `ProjectMemoryEntity`, and `FlightRecorder` resolutions so AGT recalls prior architectural decisions and fixed bugs cleanly within strict token budgets without growing prompt size.

---

### Phase 6 — Universal Extension Hub (OpenCode & Pi Ecosystems)
**Goal:** Transform Extensions into a curated, remote-syncable **Universal Extension Hub** supporting official **OpenCode** and **Pi Coding Agent** skills, presets, archives, and MCP integrations with safe Download → Verify → Extract → Install workflows.

- [x] **6.1 Curated Official Hub, Download/Extract Pipeline & Native Dual-Agent Sync (Step 1)**:
  - Curated **Extension Catalog** covering OpenCode & Pi Coding Agent skills (`SKILL.md`), built-in AGT skills, and 1-tap official Remote HTTP/SSE MCP Server presets (`Context7`, `DeepWiki`, `GitHub MCP`), plus remote `extensions-catalog.json` registry syncing & local caching.
  - Real **Download (%) → SHA-256 Verify → Confined Archive Extract (`.zip` / `.tar.gz` via `RuntimeArchiveExtractor`) → Install** pipeline supporting `github.com/owner/repo`, `/tree/` subfolder URLs, `/blob/` raw `SKILL.md` URLs, archive URLs, and Skill JSON manifests while strictly filtering out native binaries and executable DCL payloads.
  - Native Dual-Agent Skill Discovery: automatically sync enabled non-executable `SKILL.md` folders into OpenCode's session `$OPENCODE_CONFIG_DIR/skills/<id>/` and Pi's `$PI_CODING_AGENT_DIR/skills/<id>/` alongside prompt/system-append injection.
  - Extensions Hub UI: search bar, Agent Ecosystem filter chips (`All`, `OpenCode`, `Pi Agent`, `Universal`), live download/extract progress card, Remote Catalog Sync dialog, and prefilled Remote MCP Preset connect flow.
- [x] **6.2 Sandboxed PRoot Local `npx`/stdio MCP & OpenCode/Pi JS/TS Plugin Runner (Step 2)**:
  - Enabled explicit opt-in Sandboxed PRoot Extension Host (`allowProotSandboxExtensions`) with policy gating across `ExtensionRegistryRepository`, `ExtensionsViewModel`, `WorkspaceViewModel`, `OpenCodeAgentRuntime`, and `PiAgentRuntime`.
  - Added local PRoot stdio/`npx` MCP server presets & custom registration with executable allowlisting (`npx`, `node`, `uvx`, `python3`, `bunx`), real PRoot stdio JSON-RPC handshake (`McpStdioClient`), Keystore token isolation (`{env:...}`), OpenCode `"type": "local"` config emission, and Pi system-append integration.
  - Added interpreted JS/TS plugin importer & bundle validator (`.ts`/`.js`/GitHub/`.zip`/`.tar.gz`, strict SHA-256 + zero-native-binary Google Play compliance), native OpenCode (`$OPENCODE_CONFIG_DIR/plugins/`) & Pi (`$PI_CODING_AGENT_DIR/extensions/`) sync, and Workspace composer plugin attachments.

---

### Phase 7 — 3-API Gateway Connectivity (LLM + Rotated Web Search + Rotated GitHub Search) (COMPLETED)
**Goal:** Extend AGT's gateway architecture from a single LLM API to 3 independent, Keystore-protected APIs powered by **JH GetWay Pro** key rotation—giving AGT long-lived, quota-resilient Web Research and GitHub Repository Research without embedding provider keys in the APK.

- [x] **7.1 Independent 3-API Configuration & Keystore Vaults**:
  - Keep existing **API 1: LLM Gateway** (`gatewayBaseUrl` + `GatewayCredentialVault`) untouched for model discovery & streaming chat.
  - Add **API 2: Web Search Gateway** (`webSearchEndpointUrl` + dedicated encrypted token slot in `SearchCredentialVault`), supporting rotated multi-provider search (Tavily, Brave, Serper, Exa, DuckDuckGo) via JH GetWay Pro.
  - Add **API 3: GitHub Search Gateway** (`githubSearchEndpointUrl` + dedicated encrypted token slot in `SearchCredentialVault`), supporting rotated GitHub PAT pools in JH GetWay Pro (with optional fallback to AGT's `GitHubApiClient` when unconfigured).
- [x] **7.2 Gateway Search Clients & Health Verification (`GatewayWebSearchClient`, `GatewayGitHubSearchClient`)**:
  - Implement typed OkHttp clients matching AGT's `GatewayUrlValidator` SSRF guard, `SecretRedactor`, and `AgtError` mapping (`401`, `403`, `429`, `404`/`501`).
  - Support `GET /health` capability discovery + dedicated **"Test Web Search API"** and **"Test GitHub Search API"** actions in Settings.
  - Ensure graceful degradation (`Skipped with warning`) when either search API is unconfigured, offline, or rate-limited so the Council never crashes.

---

### Phase 8 — Native Kotlin Multi-Agent Council Graph Engine (COMPLETED)
**Goal:** Introduce a LangGraph-inspired **Parallel Fan-Out / Fan-In + Adversarial Debate** orchestrator directly in Kotlin (`CouncilGraphEngine`) that decomposes tasks, conducts dual Web+GitHub research, runs specialist analysts concurrently, and synthesizes a structured decision report.

- [x] **8.1 Task Decomposer & Parallel Dual-Research Node**:
  - Use `RepositoryInspector` + `RepoMapEngine` once at task start, then run a lightweight **Decomposer** step to generate focused web search queries and GitHub repository search queries.
  - Execute **Web Researcher** (API 2) and **GitHub Repo Researcher** (API 3) concurrently (`async`/`awaitAll`) and distill findings into a compact **Research Dossier**.
- [x] **8.2 Parallel 5-Role Specialist Council (Read-Only Analysis)**:
  - Run 5 specialist personas concurrently via `GatewayClient` (no heavy PRoot process spawning per analyst, zero file-write conflicts):
    1. **Architect / Planner** — Step-by-step architecture & implementation blueprint.
    2. **Cost & Resource Analyst** — API/infrastructure/token cost, licensing, and effort estimation.
    3. **Realist / Pragmatist** — Practical feasibility under Android, PRoot, device, and stack constraints.
    4. **Devil's Advocate / Critic** — Adversarial red-team review finding flaws, failure modes, and security/edge-case risks.
    5. **Alternative Proposer** — Simpler, cheaper, or more resilient Plan B / Plan C alternatives.
- [x] **8.3 Synthesizer / Lead Judge & Structured 6-Section Report**:
  - Synthesize all specialist opinions + research dossier into a deterministic, 6-section report:
    1. `Summary & Verdict` (`GO` / `MODIFY` / `RECONSIDER`)
    2. `Research & Existing Projects`
    3. `Step-by-Step Execution Plan`
    4. `Cost & Effort Estimate`
    5. `Risks & Critic Objections`
    6. `Alternatives & Trade-offs`

---

### Phase 9 — Editable Council Sections, Re-Council Loop & Durable Persistence (COMPLETED)
**Goal:** Enable Human-in-the-Loop (HITL) iterative refinement where the user can edit any section of the Council's report, trigger **Re-Council (Round 2, 3, ...)** on their amendments, and hand off the final approved plan seamlessly to the Coder agent.

- [x] **9.1 Per-Section Inline Editing & User Amendment Notes**:
  - Represent each of the 6 report sections as independently editable blocks (`CouncilReportSection`), tracking `originalContent`, `editedContent`, `isModifiedByUser`, and an optional `userAmendmentNote`.
- [x] **9.2 Iterative Re-Council Engine (`Round N -> Edit -> Round N+1`)**:
  - Extend `TaskState` / `TaskStateMachine` (`RESEARCHING`, `COUNCIL_DELIBERATING`, `SYNTHESIZING`, `AWAITING_COUNCIL_APPROVAL`) and Room (`CouncilSessionEntity`, `CouncilRoundEntity`, `FlightRecorderEventType.COUNCIL_*`).
  - Support **Re-Council** with selectable scope:
    - *Fast Re-Council (Reuse Research)*: Re-runs Architect, Cost, Realist, Critic, Alternative, and Synthesizer against the user's edited sections without re-burning Web/GitHub search quota.
    - *Full Re-Council (Refresh Search)*: Also re-queries Web/GitHub Search based on updated requirements.
  - Preserve full round history and highlight changes between Round N and Round N+1.
- [x] **9.3 Approved Plan -> `ModelHandoffPacket` -> Coder Agent Handoff**:
  - Clicking **Approve & Start Coding** locks the latest edited/re-conciled plan into a `ModelHandoffPacket` and launches `AgentOrchestrator` (`OpenCode` or `Pi`) + `ValidationRunner`.
  - Support **Council Only (Save Plan)** mode for pure architecture/feasibility analysis without modifying workspace files.

---

### Phase 10 — Per-Role Model Routing, Budget Guardrails & Council Workspace UI
**Goal:** Expose intuitive controls, cost/latency visibility, and rich debate cards in the Workspace and Settings UIs.

- [ ] **10.1 Per-Role Model Routing & Toggle Controls**:
  - Allow assigning distinct gateway models per role in Settings (e.g., fast/low-cost model for Decomposer, Cost, Realist, Critic; flagship model for Planner, Synthesizer, and Coder) plus toggles to enable/disable specific council roles.
- [ ] **10.2 Token, Latency & Search Quota Guardrails**:
  - Track per-agent token usage, latency, and search provider attribution in `CouncilRoundEntity` / `FlightRecorder`, enforcing bounded structured outputs to avoid context bloat.
- [ ] **10.3 Council Workspace UI (`AgentView`, `CouncilTimelineCards`, `Settings`)**:
  - Add execution mode selector in Workspace composer: `Direct Code`, `Council + Code`, `Council Only`.
  - Render live parallel progress chips (`Researcher ✓`, `Planner ✓`, `Critic ⏳`...), expandable individual agent reports, 6-section inline editors, Round switcher (`Round 1` / `Round 2`), and `Re-Council` / `Approve & Start Coding` actions.

> Detailed technical architecture, JSON contracts, and database/UI specifications are documented in `docs/MULTI_AGENT_COUNCIL_AND_3API_SPEC.md`.


