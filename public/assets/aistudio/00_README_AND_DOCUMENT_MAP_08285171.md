# AGT Code — Engineering Documentation Pack

**Version:** 1.0  
**Date:** 2026-11-18  
**Purpose:** Source-grounded product and engineering specification for building AGT Code with Google AI Studio-assisted development.

## What this pack is

This documentation pack defines the intended product, architecture, user workflows, integration contracts, engineering boundaries, security requirements, delivery sequence, and acceptance criteria for **AGT Code — Full Autonomous AI Software Engineer for Android**.

It is intended to be supplied as context to Google AI Studio and used by developers as the implementation baseline.

## Read in this order

| File | Purpose |
|---|---|
| `01_PRODUCT_REQUIREMENTS.md` | Product vision, scope, actors, functional and non-functional requirements |
| `02_REFERENCE_SOURCE_AUDIT.md` | Findings from the three supplied source archives; facts vs decisions |
| `03_SYSTEM_ARCHITECTURE.md` | Proposed component architecture and boundaries |
| `04_USER_WORKFLOWS.md` | Setup, GitHub, Home, project, agent, extensions, and settings flows |
| `05_AGENT_ORCHESTRATION_SPEC.md` | Autonomous coding agent lifecycle, task loop, tools, checkpoints, and approvals |
| `06_JH_GATEWAY_INTEGRATION.md` | Integration with the existing JH GetWay Pro gateway |
| `07_ANDROID_LINUX_RUNTIME_SPEC.md` | Android runtime, Linux environment, process execution, terminal, and preview |
| `08_EXTENSIONS_AND_TOOL_REGISTRY.md` | Skills, MCP servers, plugins, registry, permissions, and lifecycle |
| `09_UI_UX_SPECIFICATION.md` | Approved UI layout, navigation, typography, and theme tokens |
| `10_SECURITY_PRIVACY_AND_LICENSES.md` | Security controls, threat boundaries, secrets, permissions, and license notes |
| `11_IMPLEMENTATION_PLAN_GOOGLE_AI_STUDIO.md` | Staged build plan and master instructions for AI Studio |
| `12_ACCEPTANCE_TEST_PLAN.md` | Definition of done and test scenarios |
| `13_DECISIONS_AND_OPEN_QUESTIONS.md` | Confirmed decisions, assumptions, and items requiring validation |

## Important interpretation rules

1. **Existing source facts** are explicitly labelled as findings from the supplied repositories.
2. **AGT design decisions** are requirements agreed for the new product.
3. **Proposals** are recommendations that still require implementation validation.
4. Do not assume a feature exists merely because it appears in a reference project.
5. Do not copy source code from a reference repository without checking its license and the specific dependency's license.
6. Do not replace JH GetWay Pro with another gateway. It is the designated AI connectivity layer.
7. OpenCode is the first Agent Runtime. Keep the runtime boundary extensible for future runtimes.
8. The Settings home must be a vertical list. Selecting a category opens a separate detail page; never use a persistent side-by-side settings pane.
9. The four Project Workspace tools are Agent, Files, Terminal, and Preview, arranged in one horizontal row.
10. Demo-only states must never be presented as real production integrations.

## Reference archives inspected

- `jh-getway- (3).zip`
- `Mobile-Harness-main.zip`
- `antigem-master (2).zip`

See `02_REFERENCE_SOURCE_AUDIT.md` for details and limitations.
