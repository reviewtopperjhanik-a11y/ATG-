# 08 — Extensions and Tool Registry

## 1. Purpose

The Extensions area allows AGT to discover and manage:
- Skills
- MCP Servers
- Plugins

The Agent should use only installed, enabled, compatible, and authorized tools.

## 2. Product UI

Extensions is an app-level navigation destination with three categories:
1. Skills
2. MCP Servers
3. Plugins

Each extension detail page should show:
- Name and description.
- Publisher/source.
- Version.
- Compatibility.
- License.
- Requested permissions.
- Installation state.
- Update availability.
- Enable/disable/uninstall actions.
- Changelog or source link where available.

## 3. Skills

Skills are instruction packages that teach the Agent how to perform a task or use a tool.

Suggested initial format:
- `SKILL.md` with clear metadata and instructions.
- Optional supporting reference files.
- Declared required tools.
- Version and source metadata.

Skills are not executable code by default. Do not silently execute scripts embedded in a Skill.

## 4. MCP Servers

MCP servers expose tools/resources/prompts through the Model Context Protocol.

Support transport types only where the host can securely implement them:
- Streamable HTTP.
- SSE where required by server compatibility.
- STDIO only through an explicitly supported isolated runtime process, never by assuming Android app process spawning is safe.

The JH GetWay Pro README describes an HTTP/SSE MCP bridge and explicitly disallows native STDIO subprocess spawning in its Android process sandbox. AGT must choose its own runtime-side MCP transport deliberately.

## 5. Plugins

A plugin package may contain:
- Manifest.
- Metadata and version.
- Declared permissions.
- Tool definitions.
- Optional Skill documents.
- Runtime entry point.

Do not execute arbitrary JavaScript with unrestricted host access. If a JavaScript worker host is implemented, it must be sandboxed, capability-based, resource-limited, and audited.

## 6. Registry model

Suggested extension record:

```json
{
  "id": "publisher.extension-id",
  "name": "Extension name",
  "type": "SKILL | MCP_SERVER | PLUGIN",
  "version": "1.0.0",
  "source": "registry-or-repository",
  "license": "SPDX identifier",
  "compatibility": {
    "agtApi": ">=1.0",
    "runtime": ["android", "linux"]
  },
  "permissions": ["PROJECT_READ"],
  "integrity": {
    "sha256": "..."
  }
}
```

This is a proposed schema, not an existing reference-project manifest contract.

## 7. Permission model

Use least privilege. Suggested permissions:
- PROJECT_READ
- PROJECT_WRITE
- TERMINAL_EXEC
- NETWORK_ACCESS
- GITHUB_READ
- GITHUB_WRITE
- BROWSER_CONTROL
- DEVICE_CAMERA
- DEVICE_FILES
- SECRET_ACCESS (avoid; prefer scoped secret handles)
- EXTERNAL_SERVICE_ACTION

Sensitive permissions must be clearly explained and approved.

## 8. Installation pipeline

1. Resolve extension source.
2. Fetch metadata.
3. Validate publisher/source and version.
4. Inspect license and compatibility.
5. Inspect manifest and requested permissions.
6. Download package.
7. Verify integrity.
8. Scan package structure and reject unsafe paths.
9. Install into an isolated extension directory.
10. Register tools and permissions.
11. Enable only after successful validation.

## 9. Tool discovery for Agent

Before each task:
- Build a list of enabled compatible tools.
- Include only tools allowed for the project/task.
- Provide schemas and concise descriptions.
- Enforce authorization again at execution time.
- Never rely solely on model-side tool descriptions for security.

## 10. Lifecycle

Support:
- Install
- Configure
- Enable
- Disable
- Update
- Rollback where feasible
- Uninstall
- Permission review
- Health check

## 11. MVP recommendation

Start with:
1. Built-in Skills loader.
2. HTTP MCP client/registry.
3. No third-party arbitrary code execution in MVP.
4. Add a constrained plugin host only after sandbox and permission architecture is proven.
