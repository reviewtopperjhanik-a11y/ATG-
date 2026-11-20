# Multi-Agent Council, 3-API Gateway Rotation & Editable Re-Council Specification

> **Status:** Planned (Phases 7–10 in `docs/DEVELOPMENT_INTELLIGENCE_ROADMAP.md`)  
> **Scope:** `R7 ATG` (Android Client) + `JH GetWay Pro` (Central Key-Rotation Gateway)

---

## 1. System Overview & Core Principles

To support deep autonomous research, multi-perspective adversarial analysis, and iterative human-in-the-loop planning on mobile devices without heavy external Python/Node orchestrator daemons:

1. **Pure Kotlin Graph Engine (`CouncilGraphEngine`):** Implements a LangGraph-style **Fan-Out / Fan-In DAG** natively using Kotlin Coroutines (`async` / `awaitAll`), Room persistence, and `TaskStateMachine`.
2. **3-API Separation of Concerns (Powered by JH GetWay Pro):**
   - **API 1 — LLM Gateway** (existing): Model discovery & streaming/non-streaming completions.
   - **API 2 — Web Search Gateway** (new): Multi-provider web search with server-side key rotation (Tavily, Brave, Serper, Exa, DuckDuckGo, etc.).
   - **API 3 — GitHub Search Gateway** (new): GitHub repository & code search with server-side PAT rotation (5–6+ free GitHub Personal Access Tokens).
3. **Zero File-Conflict Concurrency:**
   - Research and Council specialist agents run strictly **Read-Only** (using direct `GatewayClient` + Search APIs + `RepoMap`), never spawning competing PRoot file writers.
   - Only after user approval does a **single Coder Agent** (`OpenCode` or `Pi`) execute workspace edits and run `ValidationRunner`.
4. **Editable Report & Re-Council Loop:**
   - The synthesized final report is structured into **6 independently editable sections**.
   - Users can edit any section, add an amendment note, and trigger **Re-Council (Round 2, 3, ...)** before handing off to coding.

---

## 2. End-to-End Execution Workflow

```text
[User Prompt + Mode (Council+Code | Council Only)]
                     │
                     ▼
        [1. Context & RepoMap Snapshot]
   (RepositoryInspector + RepoMapEngine + ProjectMemory)
                     │
                     ▼
        [2. Task Decomposer Node]
   Generates: objective breakdown, web queries, GitHub queries
                     │
         ┌───────────┴───────────┐ (Parallel Fan-Out)
         ▼                       ▼
[3a. Web Search (API 2)]  [3b. GitHub Search (API 3)]
         └───────────┬───────────┘ (Fan-In)
                     ▼
        [4. Research Summarizer]
      Produces compact Research Dossier
                     │
   ┌─────────┬───────┼───────┬─────────┐ (Parallel Fan-Out: 5 Specialists)
   ▼         ▼       ▼       ▼         ▼
[Planner] [Cost] [Realist] [Critic] [Alternative]
   └─────────┴───────┼───────┴─────────┘ (Fan-In)
                     ▼
      [5. Synthesizer / Lead Judge Node]
   Produces 6-Section Editable Final Report (Round N)
                     │
                     ▼
      [6. AWAITING_COUNCIL_APPROVAL (UI Gate)]
       ├── User Edits Sections / Adds Feedback ──► [Re-Council (Round N+1)] ─┐
       │                                              ▲                       │
       │                                              └───────────────────────┘
       ├── [Council Only] ──► Save Report & Complete
       └── [Approve & Start Coding]
                     │
                     ▼
   [7. ModelHandoffPacket -> AgentOrchestrator (OpenCode/Pi) -> ValidationRunner]
```

---

## 3. 3-API Gateway Contract (JH GetWay Pro <-> R7 ATG)

### 3.1 Configuration Slots in `R7 ATG`
In `Settings -> Gateway & Search APIs`, configure 3 independent endpoints and encrypted tokens (`GatewayCredentialVault` + new `SearchCredentialVault`):

| Slot | Default Use | Auth Header | Preferences & Vault |
| :--- | :--- | :--- | :--- |
| **API 1: LLM Gateway** | `/health`, `/v1/models`, `/v1/chat/completions`, `/v1/responses` | `Authorization: Bearer <llm_token>` | Existing `gatewayBaseUrl` + `GatewayCredentialVault` |
| **API 2: Web Search API** | Web research & page content extraction | `Authorization: Bearer <web_search_token>` | `webSearchBaseUrl` + `SearchCredentialVault` |
| **API 3: GitHub Search API** | GitHub repo/code search + README excerpts | `Authorization: Bearer <github_search_token>` | `githubSearchBaseUrl` + `SearchCredentialVault` |

> **Security Rules (aligned with Spec `06_JH_GATEWAY_INTEGRATION`):**
> - Upstream API keys (Tavily, Brave, Serper, GitHub PATs) stay **exclusively inside JH GetWay Pro** — never inside `R7 ATG` or prompts.
> - `R7 ATG` stores only gateway-issued scoped bearer tokens in Android Keystore-backed encrypted storage, validates URLs via `GatewayUrlValidator`, and redacts tokens via `SecretRedactor`.

---

### 3.2 API 2 Contract: Web Search Gateway

#### Health Check: `GET /v1/search/web/health` (or `GET /health`)
Response (`200 OK`):
```json
{
  "status": "ok",
  "service": "web_search",
  "active_providers": ["tavily", "brave", "serper"],
  "available_keys_count": 6,
  "cache_enabled": true
}
```

#### Search Request: `POST /v1/search/web`
Headers:
- `Authorization: Bearer <web_search_token>`
- `Content-Type: application/json`

Request Body:
```json
{
  "query": "offline first collaborative notes kotlin multiplatform architecture",
  "max_results": 5,
  "search_depth": "basic",
  "include_content": true,
  "freshness_days": 365
}
```

Response (`200 OK`):
```json
{
  "query": "offline first collaborative notes kotlin multiplatform architecture",
  "provider_used": "tavily",
  "cached": false,
  "latency_ms": 420,
  "results": [
    {
      "title": "Building Offline-First Collaborative Apps with CRDTs",
      "url": "https://example.com/article",
      "snippet": "Short search snippet...",
      "content": "Extracted readable summary/excerpt (bounded ~1500 chars)...",
      "published_date": "2025-11-04",
      "score": 0.92
    }
  ]
}
```

---

### 3.3 API 3 Contract: GitHub Search Gateway

#### Health Check: `GET /v1/search/github/health` (or `GET /health`)
Response (`200 OK`):
```json
{
  "status": "ok",
  "service": "github_search",
  "pat_pool_size": 5,
  "healthy_tokens": 5,
  "cache_enabled": true
}
```

#### Search Request: `POST /v1/search/github`
Headers:
- `Authorization: Bearer <github_search_token>`
- `Content-Type: application/json`

Request Body:
```json
{
  "query": "collaborative markdown notes android crdt",
  "mode": "repositories",
  "language": "Kotlin",
  "sort": "stars",
  "max_results": 5,
  "include_readme": true
}
```
*(Supported `mode`: `"repositories"` | `"code"`)*

Response (`200 OK`):
```json
{
  "query": "collaborative markdown notes android crdt",
  "mode": "repositories",
  "cached": false,
  "latency_ms": 380,
  "total_count": 42,
  "results": [
    {
      "full_name": "owner/repo-name",
      "html_url": "https://github.com/owner/repo-name",
      "description": "Local-first CRDT collaborative notes app",
      "stars": 1840,
      "forks": 145,
      "language": "Kotlin",
      "license": "MIT",
      "updated_at": "2026-08-19T12:00:00Z",
      "topics": ["android", "crdt", "offline-first"],
      "readme_excerpt": "Bounded README summary (max ~2000 chars)..."
    }
  ]
}
```

---

### 3.4 Shared Error Contract (Matched to `JhGatewayHttpClient.mapHttpError`)
All non-2xx responses from API 2 and API 3 must return:
```json
{
  "error": {
    "code": "rate_limit_exhausted",
    "message": "All upstream search keys are temporarily rate-limited.",
    "retry_after_seconds": 30
  }
}
```
Mapped HTTP status codes in `R7 ATG`:
- `401` -> `AgtError.GatewayUnauthorized`
- `403` -> `AgtError.PermissionDenied` (missing scope such as `WEB_SEARCH` or `GITHUB_SEARCH`)
- `404` / `405` / `501` -> `AgtError.UnsupportedCapability`
- `429` -> `AgtError.NetworkUnavailable` (all rotated keys exhausted; Council degrades gracefully with warning)

---

## 4. Multi-Agent Council Roles & Structured Output

Each specialist produces bounded structured output (JSON preferred with Markdown fallback) to control token usage and enable clean UI rendering:

| Role ID | Responsibility | Input Context | Output Fields |
| :--- | :--- | :--- | :--- |
| `decomposer` | Break prompt into sub-questions + search queries | Objective + RepoMap | `subTasks`, `webQueries[2..3]`, `githubQueries[2..3]`, `activeRoles` |
| `web_researcher` | Query API 2 (`POST /v1/search/web`) | `webQueries` | Top cited articles/docs + key takeaways |
| `github_researcher` | Query API 3 (`POST /v1/search/github`) | `githubQueries` | Existing repos, stars, stack, license, pattern comparison |
| `planner` | Design step-by-step technical plan | Objective + RepoMap + Research Dossier (+ Edits on Round > 1) | `architectureSummary`, `steps[]`, `filesToTouch[]`, `acceptanceCriteria[]` |
| `cost_analyst` | Evaluate API, infra, token, licensing & time cost | Objective + Planner/Dossier (+ Edits on Round > 1) | `costTier`, `estimatedEffort`, `recurringCosts[]`, `costOptimizations[]` |
| `realist` | Ground plan in Android/PRoot/device constraints | Objective + RepoMap + Dossier (+ Edits on Round > 1) | `feasibilityScore(1..10)`, `blockers[]`, `realisticTimeline`, `constraints[]` |
| `critic` | Devil's Advocate — find flaws, edge cases & risks | Objective + Planner/Dossier (+ Edits on Round > 1) | `verdict(APPROVE_WITH_CHANGES|REJECT_RISKY)`, `flaws[]`, `securityRisks[]`, `failureModes[]` |
| `alternative` | Propose Plan B / Plan C simpler or safer paths | Objective + Dossier + Critic angles (+ Edits) | `alternatives[]` (title, pros, cons, whenToChoose) |
| `synthesizer` | Judge & merge all opinions into 6 editable sections | All Round N specialist outputs + prior user edits | `verdict(GO|MODIFY|RECONSIDER)`, `sections[6]` |

---

## 5. Editable Report & Re-Council Data Flow

### 5.1 The 6 Editable Sections (`CouncilSectionId`)
1. `SUMMARY_VERDICT` — Overall Verdict (`GO` / `MODIFY` / `RECONSIDER`) & Executive Summary
2. `RESEARCH_FINDINGS` — Similar GitHub Projects & Web References
3. `EXECUTION_PLAN` — Step-by-Step Implementation Plan & Target Files
4. `COST_RESOURCES` — Cost, Quota, Licensing & Effort Breakdown
5. `RISKS_CRITIQUE` — Critic Objections, Risks & Required Mitigations
6. `ALTERNATIVES` — Alternative Approaches & Selected Trade-offs

### 5.2 Re-Council Rules
When the user modifies one or more sections and/or writes a **User Amendment Note** and taps **Re-Council**:
- **Round Tracking:** A new `CouncilRound` (`roundNumber = prev + 1`) is created with `baseSections = userEditedSections` and `userFeedback = amendmentNote`.
- **Search Quota Conservation:**
  - By default (`refreshSearch = false`), Round N+1 **reuses the Research Dossier** from Round 1 so Web & GitHub Search quotas are not wasted unnecessarily.
  - A checkbox **"Re-run Web & GitHub Search"** (`refreshSearch = true`) lets the user force fresh searches when their edits significantly change the direction.
- **Specialist Focus on Round N+1:** Each specialist receives:
  1. Original objective & RepoMap
  2. The **User-Edited Report** as the new baseline
  3. The user's explicit `amendmentNote`
  4. Instruction to evaluate *specifically what changed*, verify whether Critic's prior objections are resolved, and flag any new risks or cost impacts.
- **Round Diff & History:** Users can switch between `Round 1`, `Round 2`, ... in the UI and inspect how risks/verdict evolved before tapping **Approve & Start Coding**.

---

## 6. Android Codebase Additions (Implementation Map for Phases 7–10)

### 6.1 Domain & Data Layer
- `domain/model/CouncilModels.kt`: `CouncilRole`, `CouncilExecutionMode` (`DIRECT_CODE`, `COUNCIL_AND_CODE`, `COUNCIL_ONLY`), `CouncilSectionId`, `CouncilReportSection`, `CouncilAgentOpinion`, `CouncilRound`, `CouncilSession`, `WebSearchResult`, `GitHubSearchResult`.
- `core/security/SearchCredentialVault.kt`: Keystore-backed encrypted tokens for `web_search_token` and `github_search_token`.
- `data/search/GatewaySearchClients.kt`: `GatewayWebSearchClient` (`POST /v1/search/web`) & `GatewayGitHubSearchClient` (`POST /v1/search/github`) with `GatewayUrlValidator`, `SecretRedactor`, and `GitHubApiClient` fallback.
- `data/preferences/AppPreferencesRepository.kt`: Store `webSearchBaseUrl`, `githubSearchBaseUrl`, `councilRoleModelsJson`, `enabledCouncilRoles`.
- `data/database/Entities.kt` & `Daos.kt` + `AgtDatabase` migration: `CouncilSessionEntity`, `CouncilRoundEntity`, plus `FlightRecorderEventType` entries (`COUNCIL_RESEARCH`, `COUNCIL_OPINION`, `COUNCIL_SYNTHESIS`, `COUNCIL_USER_EDIT`).

### 6.2 Orchestration & Graph Layer
- `agent/orchestrator/CouncilGraphEngine.kt`: Parallel Coroutine DAG runner (`Decomposer -> Dual Search -> 5-Role Council -> Synthesizer`), per-role model routing, timeout/partial-failure resilience, and Re-Council round executor.
- `agent/orchestrator/CouncilRolePrompts.kt`: Deterministic system prompts & structured JSON parsers for all 9 roles.
- `agent/orchestrator/TaskStateMachine.kt` & `AgentOrchestrator.kt`: New states (`RESEARCHING`, `COUNCIL_DELIBERATING`, `SYNTHESIZING`, `AWAITING_COUNCIL_APPROVAL`) and seamless `Approved Council Plan -> ModelHandoffPacket -> OpenCode/Pi Coder` transition.

### 6.3 UI Layer
- `feature/settings/SettingsDetailScreen.kt` & `SettingsViewModel.kt`:
  - **Web Search API** card (Endpoint URL, Token, Test button, provider status).
  - **GitHub Search API** card (Endpoint URL, Token, Test button, PAT pool status).
  - **Council Roles & Model Routing** configuration.
- `feature/workspace/AgentView.kt` & `CouncilTimelineCards.kt`:
  - Composer mode switcher (`Direct Code` / `Council + Code` / `Council Only`).
  - Live parallel council status strip + expandable specialist debate cards.
  - 6-section interactive report viewer with inline **Edit** mode, **Amendment Note** field, `Re-run Search` toggle, **Re-Council** button, Round selector, and **Approve & Start Coding** CTA.
