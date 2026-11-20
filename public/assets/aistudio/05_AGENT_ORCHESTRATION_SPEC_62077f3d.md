# 05 — Agent Orchestration Specification

## 1. Purpose

The AGT Software Engineering Orchestrator is the product's main autonomous engineering subsystem. It is distinct from the JH GetWay Pro provider gateway and distinct from OpenCode itself.

- JH GetWay Pro provides model/provider connectivity.
- OpenCode is the first Agent Runtime.
- AGT Orchestrator manages the product-level task lifecycle, safety, checkpoints, validation, and user-facing status.

## 2. Initial execution model

Start with one primary orchestrator and one OpenCode runtime adapter. Specialist roles can initially be prompt/tool profiles rather than separate autonomous processes.

Potential specialist profiles:
- Architect
- Frontend Engineer
- Backend Engineer
- Database Engineer
- Debugger
- Test Engineer
- Security Reviewer

Do not introduce parallel multi-agent execution until single-agent execution, tool authorization, and recovery are reliable.

## 3. Task state machine

Suggested states:
- CREATED
- CONTEXT_LOADING
- ANALYZING
- PLANNING
- AWAITING_PLAN_APPROVAL (optional policy)
- EXECUTING
- AWAITING_TOOL_APPROVAL
- VALIDATING
- FIXING
- COMPLETED
- FAILED
- CANCELLED
- PAUSED

Every transition must be persisted. Illegal transitions should be rejected.

## 4. Core task loop

1. Parse user objective and constraints.
2. Identify project root and current Git state.
3. Inspect relevant repository files.
4. Build or refresh a compact project map.
5. Produce a plan with acceptance criteria.
6. Execute one bounded step at a time.
7. Request tools through the Tool Broker.
8. Observe tool results and update task state.
9. Continue, revise plan, or ask the user when blocked.
10. Run build/test/lint/security checks relevant to the change.
11. Fix failures within configured limits.
12. Create final summary with files changed, checks run, outcomes, and unresolved issues.

## 5. Tool Broker

The Agent must not execute tools directly from untrusted model text. Tool calls must be parsed, schema-validated, permission-checked, and routed through a Tool Broker.

Initial tool families:
- Project filesystem
- Terminal/process
- Git
- GitHub
- MCP
- Preview/server
- Project search/index
- Checkpoint/diff
- User approval

Each tool must define:
- Stable tool ID
- JSON input schema
- Permission class
- Timeout
- Cancellation behavior
- Output size limit
- Audit/log policy
- Error mapping

## 6. Permission classes

Suggested classes:
- READ_ONLY
- PROJECT_WRITE
- LOCAL_EXECUTION
- NETWORK_ACCESS
- EXTERNAL_ACCOUNT_ACTION
- DESTRUCTIVE
- PRODUCTION_DEPLOYMENT

Default policy:
- Read project files: allowed within selected project.
- Write project files: allowed during an active task, with checkpoint and diff.
- Run ordinary project build/test commands: allowed under configured policy.
- Install packages or access arbitrary network endpoints: policy-controlled.
- Delete broad paths, modify files outside project, expose secrets, push Git changes, or deploy production: require approval.
- Dangerous MCP tools: require interactive approval.

## 7. Checkpoints and recovery

Create checkpoints:
- Before broad or high-impact edits.
- Before migrations or refactors.
- Before applying large generated patches.
- Before restore/cleanup operations.

Checkpoint should capture:
- Project ID and root identity.
- Timestamp and reason.
- Changed/untracked file state.
- File hashes and/or snapshot data.
- Git HEAD and working-tree status where applicable.

Recovery:
- Restore a checkpoint after user confirmation.
- Show a diff before restore.
- Do not overwrite user changes silently.
- Keep checkpoint retention bounded and configurable.

## 8. Execution limits

Initial proposed defaults (tune after device testing):
- Maximum agent steps: 20.
- Terminal command timeout: 120 seconds.
- Bounded output size per tool call.
- Maximum concurrent commands: 1 initially, unless a safe use case requires more.
- Maximum retries per failed validation step: 2 or 3.
- User can Stop at any point.

These are initial product defaults, not source-derived facts.

## 9. Validation strategy

Select checks based on detected project type:
- Build
- Unit tests
- Lint/format
- Type check
- Static analysis
- Security checks

The agent must not claim a build/test passed unless the command actually ran and returned a successful result. Record command, exit code, duration, and bounded output.

## 10. Project understanding

Maintain:
- Project manifest and detected stack.
- Directory map.
- Key entry points.
- Build/test commands.
- Dependency manifest summaries.
- Important architecture notes.
- Recent task summaries.
- User-approved project instructions.

Do not index secrets or large generated directories by default. Respect `.gitignore` and configurable exclusions.

## 11. Event model for UI

Suggested event types:
- TaskStarted
- PlanCreated
- PlanUpdated
- AgentThinkingStatus
- ToolRequested
- ApprovalRequested
- ToolStarted
- ToolOutput
- ToolCompleted
- FileChanged
- DiffAvailable
- ValidationStarted
- ValidationResult
- CheckpointCreated
- TaskPaused
- TaskCompleted
- TaskFailed
- TaskCancelled

Use structured events to render progress and tool cards. Do not parse raw terminal prose as the only source of UI state.

## 12. Completion response

A completed task summary must include:
- What changed.
- Important files changed.
- Build/test/lint checks actually executed.
- Pass/fail results.
- Git status and whether a commit/push occurred.
- Any remaining limitations or manual actions.
