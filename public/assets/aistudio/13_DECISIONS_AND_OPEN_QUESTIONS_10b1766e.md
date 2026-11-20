# 13 — Decisions and Open Questions

## 1. Confirmed product decisions

| Topic | Decision |
|---|---|
| Product | Full Autonomous AI Software Engineer for Android |
| AI gateway | Existing JH GetWay Pro |
| First Agent Runtime | OpenCode |
| Runtime reference | Mobile Harness |
| Agent/UI/extension reference | AntiGem concepts and architecture |
| Main app navigation | Home, Extensions, Settings |
| Workspace tools | Agent, Files, Terminal, Preview |
| Workspace tool layout | Four selectors in one horizontal row |
| Settings home | Vertical category list |
| Settings detail | Each category opens a separate page |
| Theme modes | Light, Dark, System |
| Light theme | AntiGem Porcelain palette |
| First-run sequence | Setup → GitHub Connect/Skip → Home |
| Extensions | Skills, MCP Servers, Plugins |

## 2. Proposed technical decisions to validate

- Kotlin + Jetpack Compose + Material 3.
- MVVM/StateFlow and modular/clean boundaries.
- Room and DataStore.
- Android Keystore-backed credential protection.
- PRoot Linux runtime as initial path, subject to device testing.
- Native process bridge behind Kotlin interface.
- Foreground service for long-running work.
- OpenCode adapter behind a generic AgentRuntime interface.
- One primary orchestrator initially; specialist profiles later.
- HTTP MCP support first; arbitrary plugin execution deferred.

## 3. Open questions requiring implementation research

### Runtime
1. Which Android versions and ARM64 device classes are the initial support target?
2. What minimum free storage is required for base runtime plus optional toolchains?
3. Which Ubuntu/rootfs bundle and package versions will be maintained?
4. Should the app use one PRoot environment per app or isolated environments per project?
5. Which native PTY/process implementation is most reliable across target devices?

### OpenCode and Gateway
6. Which exact OpenCode version will be pinned initially?
7. Which OpenCode provider configuration can reliably route through JH GetWay Pro?
8. Does the Gateway protocol preserve the tool-call streaming semantics OpenCode requires for each selected model?
9. What capability metadata does the actual running gateway return from `/v1/models`?
10. Which gateway auth scope is sufficient for normal AGT use?

### GitHub
11. Which OAuth app type and redirect strategy will be used?
12. What minimum GitHub scopes are required for repository listing, clone, commit, push, and PR creation?

### Extensions
13. Which registry source will be trusted initially?
14. Which MCP transport is supported in the first release?
15. Will plugins be deferred until a secure sandbox is available?

### Product
16. Which languages/frameworks are P0 for project templates and build detection?
17. Should plan approval be optional, or should the agent begin implementation immediately by default?
18. What is the desired default task step/retry/time limit after device testing?

## 4. Build & Release Configuration (Automatic Runtime + GitHub OAuth)

- Public app endpoints and identifiers are injected via Gradle properties or CI environment variables into `BuildConfig`:
  - `AGT_RUNTIME_MANIFEST_URL` (`AGT_RUNTIME_MANIFEST_URL_DEV` optional override for `debug` builds)
  - `AGT_GITHUB_OAUTH_CLIENT_ID` (`AGT_GITHUB_OAUTH_CLIENT_ID_DEV` optional override for `debug` builds)
- End users never enter a Runtime Manifest URL, GitHub OAuth Client ID, or Client Secret in the UI.
- GitHub OAuth uses PKCE (`code_challenge_method=S256`) and deep-link callback `agtcode://oauth/github` with **no** embedded `client_secret`.
- Run `./gradlew validateReleaseConfig` (or `-PAGT_STRICT_RELEASE_CONFIG=true` during release builds) to fail early if production values are missing, placeholders, non-HTTPS, or contain secret-like tokens.
- Troubleshooting: if Setup or GitHub Connect shows a missing-configuration banner, supply official `AGT_RUNTIME_MANIFEST_URL` and `AGT_GITHUB_OAUTH_CLIENT_ID` Gradle/environment properties and rebuild.

## 5. Decisions that must not be guessed

- Current gateway API behavior beyond the supplied source contract.
- Exact OpenCode config compatibility.
- Production runtime archive URLs and SHA-256 hashes (supplied via `AGT_RUNTIME_MANIFEST_URL` at build/release time).
- Official GitHub OAuth App Client ID for production release (`AGT_GITHUB_OAUTH_CLIENT_ID`).
- Extension registry trust policy.

Record decisions in this file when they are validated.
