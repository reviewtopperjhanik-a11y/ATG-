# 10 — Security, Privacy, and License Requirements

## 1. Threat model

AGT executes model-suggested actions against user projects and a Linux environment. Treat:
- Model output as untrusted.
- Repository content as untrusted.
- Skills and extension packages as untrusted.
- MCP server responses as untrusted.
- Terminal output as untrusted.
- Downloaded runtime bundles as untrusted until verified.

## 2. Secrets

- Store gateway tokens and GitHub credentials using Android Keystore-backed protection.
- Do not store provider master API keys in AGT if they can remain inside JH GetWay Pro.
- Never place secrets in prompts, source files, shell history, logs, analytics, crash reports, or exported diagnostics.
- Redact tokens and authorization headers.
- Prefer short-lived/scoped credentials.
- Do not claim that a secret embedded in an APK is safe from extraction.

## 3. Gateway endpoint safety

- Require HTTPS for remote gateway endpoints.
- Validate URL scheme, host, DNS resolution, and private/local address policy.
- Protect against SSRF and DNS rebinding.
- Do not permit arbitrary endpoint changes from model tool calls.
- Local loopback access must be explicitly configured and authenticated.

The JH GetWay Pro README describes endpoint security and DNS-rebinding protections in its own networking layer. AGT must not assume those protections automatically cover AGT's own network requests.

## 4. Project filesystem security

- Confine normal agent file access to the selected project root.
- Canonicalize paths and resolve symlinks before operations.
- Reject `..` traversal and paths escaping the root.
- Exclude secrets and generated folders from indexing by default.
- Require approval for broad deletion or destructive replacement.
- Keep checkpoints before high-impact modifications.

## 5. Terminal security

PRoot is not a hardened virtualization boundary. Do not describe it as a secure VM or container boundary.

- Execute only within the intended runtime and project context.
- Apply command policy and approval checks.
- Limit time, output, and concurrent processes.
- Propagate Stop/cancellation.
- Do not expose privileged host paths.
- Keep Android app sandbox boundaries intact.
- Warn users to run only trusted projects and dependencies.

## 6. MCP and extension security

- Validate JSON schemas and tool arguments.
- Apply permission checks at execution time.
- Require interactive approval for dangerous tools.
- Limit response sizes and execution time.
- Verify extension package integrity.
- Inspect manifests and licenses.
- Do not run arbitrary plugin code with unrestricted host access.
- Provide extension disable/uninstall and permission revocation.

## 7. GitHub safety

- Use OAuth Authorization Code Flow with PKCE (`S256`) and CSRF `state`, configured centrally via `AGT_GITHUB_OAUTH_CLIENT_ID`.
- Never embed a GitHub OAuth Client Secret (`AGT_GITHUB_OAUTH_CLIENT_SECRET` / `GITHUB_OAUTH_CLIENT_SECRET`) in the APK, `.env`, or client source code.
- Keep tokens in Android Keystore (`GitHubCredentialVault`) and out of repository files.
- Require approval for push, release, pull request creation, repository deletion, and other external-account actions.
- Show branch and diff before commit/push.
- Do not push automatically unless the user explicitly configures that policy.

## 8. Runtime installer safety

- Inject the official runtime manifest endpoint centrally via `AGT_RUNTIME_MANIFEST_URL` (HTTPS required for remote release endpoints; loopback HTTP restricted to debug builds).
- Validate release builds via `validateReleaseConfig` Gradle checks and `AgtAppConfig.validateProductionConfiguration()`.
- Verify SHA-256 for downloaded bundles.
- Extract into staging and reject archive path traversal and symlink escapes.
- Validate required files before activation.
- Keep the previous valid runtime until the new runtime passes health checks.
- Make repair/retry safe and idempotent.

## 9. Privacy

- Explain what data is sent to the gateway/provider.
- Keep project source local unless the user requests an AI task that sends relevant context.
- Provide a way to inspect connected accounts and stored credentials.
- Redact logs.
- Avoid collecting analytics unless explicitly designed and disclosed.

## 10. Reference license observations

### Mobile Harness
The supplied archive root `LICENSE` is MIT. It also includes separate license notices for bundled components, including PRoot GPL-2.0 and Talloc LGPL-3.0-or-later. Each component must be audited separately before reuse or redistribution.

### AntiGem
The supplied archive root `LICENSE` is GNU GPL version 3. Do not copy/adapt its source into AGT without a deliberate GPL compliance decision. Its architecture and UX can be studied; independently implement the desired behavior.

### JH GetWay Pro
No top-level `LICENSE` file was present in the supplied ZIP root during this inspection. This is not proof that no license exists in the repository or online. Confirm ownership and license terms before copying or redistributing its code.

### General rule
A repository's root license does not automatically cover every bundled dependency, binary, icon, font, or asset. Maintain a dependency/license inventory and required notices.
