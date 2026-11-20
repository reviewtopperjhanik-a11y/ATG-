# AGT Runtime Hosting & Release Configuration

This directory defines the manifest schema, archive verification tooling, and CI/CD release contract for AGT's local Linux runtime (`AGT_RUNTIME_MANIFEST_URL`) and public GitHub OAuth configuration (`AGT_GITHUB_OAUTH_CLIENT_ID`).

> **Why preview builds show `AGT_RUNTIME_MANIFEST_URL` unconfigured:**
> This repository does not embed fabricated, placeholder, or mock manifest endpoints. Before users can install the runtime automatically from the **Install Runtime** button without configuration errors, release operators must host verified ARM64 rootfs archives and publish the signed `manifest.json` over HTTPS.

## Required External Infrastructure

Supply two **public (non-secret)** HTTPS/client values at APK build time:

1. **`AGT_RUNTIME_MANIFEST_URL`**
   - Hosted HTTPS endpoint serving `manifest.json` (matching `manifest.schema.json`).
   - Must not use `example.com` / `example.org` / `placeholder`, and must serve over HTTPS with standard TLS.
   - Referenced `download_url` bundles must be hosted on HTTPS object storage / CDN / GitHub Releases supporting HTTP `Range` requests for resumable downloads.

2. **`AGT_GITHUB_OAUTH_CLIENT_ID`**
   - Official GitHub OAuth App Client ID registered with callback URI `agtcode://oauth/github` and Authorization Code Flow with PKCE.
   - Never embed `GITHUB_OAUTH_CLIENT_SECRET` in the Android project or APK.

## Expected ARM64 Bundle Layout (`arm64-v8a`)

Inside the archive (`tar.gz`, `tar`, or `zip`) under `"extraction_root": "rootfs"`, include all five components:

1. `linux_runtime`: `bin/sh`, `bin/proot`, `etc/os-release`
2. `base_toolchains`: `usr/bin/git`, `usr/bin/node` (depends on `linux_runtime`)
3. `opencode_agent`: `usr/local/bin/opencode` (depends on `linux_runtime`, `base_toolchains`)
4. `github_cli`: `usr/bin/gh` (depends on `linux_runtime`, `base_toolchains`)
5. `base_skills_mcp`: `usr/local/share/agt/skills/registry.json` (depends on `linux_runtime`, `opencode_agent`)

## Generating & Verifying `manifest.json`

```bash
python3 ops/runtime-release/build_runtime_manifest.py generate \
  --runtime-id agt-ubuntu-arm64 \
  --version 1.0.0 \
  --channel stable \
  --extraction-root rootfs \
  --bundle arm64-v8a=./dist/agt-runtime-1.0.0-arm64-v8a.tar.gz=https://<YOUR_RELEASE_HOST>/v1.0.0/agt-runtime-1.0.0-arm64-v8a.tar.gz \
  --output ./dist/manifest.json

python3 ops/runtime-release/build_runtime_manifest.py verify \
  --manifest ./dist/manifest.json \
  --archive arm64-v8a=./dist/agt-runtime-1.0.0-arm64-v8a.tar.gz
```

Upload `./dist/agt-runtime-1.0.0-arm64-v8a.tar.gz` and `./dist/manifest.json` to your HTTPS release host so the archive's live URL matches `download_url` in `manifest.json`.

## Building & Validating Production Android APKs

Validate configuration and build with strict checks enabled:

```bash
gradle :app:validateReleaseConfig \
  -PAGT_RUNTIME_MANIFEST_URL="https://<YOUR_RELEASE_HOST>/v1.0.0/manifest.json" \
  -PAGT_GITHUB_OAUTH_CLIENT_ID="<YOUR_GITHUB_OAUTH_CLIENT_ID>"

gradle :app:assembleRelease \
  -PAGT_STRICT_RELEASE_CONFIG=true \
  -PAGT_RUNTIME_MANIFEST_URL="https://<YOUR_RELEASE_HOST>/v1.0.0/manifest.json" \
  -PAGT_GITHUB_OAUTH_CLIENT_ID="<YOUR_GITHUB_OAUTH_CLIENT_ID>"
```

For local/dev builds testing a hosted manifest, pass `-PAGT_RUNTIME_MANIFEST_URL_DEV` (or `AGT_RUNTIME_MANIFEST_URL`) to `:app:assembleDebug`.
