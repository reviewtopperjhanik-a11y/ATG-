# 06 — JH GetWay Pro Integration

## 1. Integration principle

AGT Code must use the existing JH GetWay Pro as its central AI connectivity layer. Do not build a second provider gateway inside AGT.

JH GetWay Pro owns provider credentials, routing, provider adapters, gateway authentication, model discovery, and gateway-side telemetry.

AGT owns project context, agent task orchestration, runtime execution, tool permissions, and project lifecycle.

## 2. Supported API surface observed in supplied source

The supplied `GatewayApiContract.kt` lists:

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Health check |
| GET | `/v1/models` | Model discovery |
| POST | `/v1/chat/completions` | OpenAI Chat Completions-compatible requests |
| POST | `/v1/responses` | Responses-style requests |
| POST | `/v1/messages` | Anthropic Messages-style requests |

The source contract explicitly lists these unsupported:
- `/v1/embeddings`
- `/v1/images/generations`
- `/v1/audio/transcriptions`
- `/v1/audio/speech`

Re-check the exact gateway build and configuration at integration time. The API contract can evolve.

## 3. Connection configuration

Settings → Gateway should include:
- Gateway Base URL.
- Device token / bearer credential.
- Protocol or compatibility mode where required.
- Connection test.
- Gateway health/status.
- Last successful connection time.
- Safe error detail.

Do not log or display the raw token after save.

## 4. Authentication and scopes

The Gateway README describes:
- Bearer authentication.
- Device scopes: `CHAT`, `MODELS_READ`, `ADMIN`.
- Model allowlists.
- Token-bucket rate limiting.

AGT should request only the scopes it needs. Normal agent usage should not require ADMIN unless a documented operation requires it.

## 5. Model discovery

1. Call `/v1/models`.
2. Parse actual model identifiers and metadata returned by the gateway.
3. Cache the last successful result with timestamp.
4. Refresh on demand and periodically according to policy.
5. Handle unavailable/unauthorized responses.
6. Do not fabricate model names in production UI.

Model capability metadata should include, where available:
- Streaming support.
- Tool/function calling support.
- Vision/image input support.
- Context window.
- Provider/model identity.
- Known limitations.

If capability metadata is not returned, use conservative capability checks or a controlled test. Do not infer full tool support from OpenAI-compatible URL shape alone.

## 6. Agent runtime relationship

OpenCode may have its own provider configuration mechanism. Determine the supported configuration path for the installed OpenCode version.

Preferred approach:
- Configure OpenCode to use JH GetWay Pro as its provider endpoint when the gateway protocol and OpenCode provider adapter are compatible.
- Use gateway-issued scoped credentials where supported.
- Keep provider secrets inside JH GetWay Pro, not in the AGT prompt or project files.
- If OpenCode requires a protocol adapter or config translation, implement it as an explicit adapter.
- Do not assume every upstream provider supports identical tool calls, streaming, or response formats.

The Gateway source includes a `CodingAgentIntegration.kt` module and README-described ready-to-copy OpenCode/Codex/Cline/Continue/Aider configuration snippets. Validate the exact generated configuration against the OpenCode version used by AGT.

## 7. Streaming

Use the gateway's supported streaming path and parse events incrementally. Requirements:
- Bounded buffers.
- Cancellation propagation.
- Reconnection/error handling where appropriate.
- Distinguish partial output from completed response.
- Preserve tool-call deltas and structured tool-call IDs.
- Avoid duplicate event application on reconnect.

## 8. Error handling

Map gateway errors into typed AGT errors:
- Unauthorized / invalid token.
- Missing scope.
- Model not allowed.
- Model unavailable.
- Provider rate limit.
- Provider timeout.
- Unsupported endpoint.
- Invalid request.
- Tool-call unsupported.
- Stream interrupted.
- Gateway unavailable.

Show user-safe errors and keep detailed redacted diagnostics for debugging.

## 9. Gateway security boundaries

- Use HTTPS for remote gateway connections.
- If connecting to a local gateway, restrict to the intended host and authenticated channel.
- Never expose upstream provider API keys to the agent.
- Store gateway device tokens using Android Keystore-backed protection.
- Do not include credentials in prompts, shell commands, logs, analytics, crash reports, or exported project files.
- Avoid broad ADMIN credentials on the mobile client.
- Validate endpoint URLs and protect against SSRF/DNS rebinding if AGT itself accepts configurable remote endpoints.

## 10. Integration test checklist

- Health check succeeds/fails correctly.
- Invalid token is rejected.
- Scope restrictions are enforced.
- Model list is fetched from the real gateway.
- Disallowed model is handled.
- Streaming text is parsed correctly.
- Tool-call payloads are preserved.
- Unsupported capability produces a clear error.
- Stop cancels an active stream.
- Secrets are redacted from logs.
