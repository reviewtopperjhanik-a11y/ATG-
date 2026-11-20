package com.example.data.extensions

import com.example.core.security.GatewayUrlValidation
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionCatalogEntry
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionPackageFormat
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.McpTransportType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Curated Universal Extension Hub catalog covering AGT built-in Skills,
 * official/ecosystem OpenCode & Pi Coding Agent Skill packages (GitHub archives & raw SKILL.md),
 * Remote HTTP/SSE & Local PRoot `npx`/stdio MCP Server presets, and Sandboxed JS/TS Plugins.
 */
object ExtensionCatalog {

    const val OFFICIAL_CATALOG_CACHE_FILE = "catalog_cache.json"

    val BUILT_IN_SKILLS: List<ExtensionCatalogEntry> = listOf(
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "agt.core.repo-inspect",
                name = "Repository Inspector",
                description = "Guides agents to map manifests, entry points, and directory structure while respecting .gitignore.",
                category = ExtensionCategory.SKILLS,
                publisher = "AGT Built-in",
                version = "1.0.0",
                license = "Apache-2.0",
                compatibility = "AGT API >= 1.0",
                permissions = listOf("PROJECT_READ"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = true,
                sourceUri = "builtin://agt.core.repo-inspect",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("repo", "architecture", "mapping"),
                packageFormat = ExtensionPackageFormat.INLINE_SKILL,
            ),
            format = ExtensionPackageFormat.INLINE_SKILL,
            inlineSkillMarkdown = """
                # Repository Inspector Skill
                - Inspect manifest files (`package.json`, `build.gradle.kts`, `Cargo.toml`, `pyproject.toml`) before editing code.
                - Summarize module boundaries, entry points, and existing test scripts concisely.
                - Respect `.gitignore` and never inspect secret vaults or `.git/objects`.
            """.trimIndent(),
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "agt.core.build-validate",
                name = "Build & Test Runner",
                description = "Instructs the agent to detect project build/test scripts and verify changes with targeted checks.",
                category = ExtensionCategory.SKILLS,
                publisher = "AGT Built-in",
                version = "1.0.0",
                license = "Apache-2.0",
                compatibility = "AGT API >= 1.0",
                permissions = listOf("PROJECT_READ", "TERMINAL_EXEC"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = true,
                sourceUri = "builtin://agt.core.build-validate",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("build", "test", "ci"),
                packageFormat = ExtensionPackageFormat.INLINE_SKILL,
            ),
            format = ExtensionPackageFormat.INLINE_SKILL,
            inlineSkillMarkdown = """
                # Build & Test Runner Skill
                - After implementing workspace edits, inspect `package.json` or build files for real build/lint/test commands.
                - Execute the narrowest passing verification command inside the project root and resolve concrete errors before concluding.
            """.trimIndent(),
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "agt.core.compose-m3-review",
                name = "Accessibility & UI Review",
                description = "Checklist for 48dp touch targets, Material 3 tokens, content descriptions, and keyboard/screen-reader polish.",
                category = ExtensionCategory.SKILLS,
                publisher = "AGT Built-in",
                version = "1.0.0",
                license = "Apache-2.0",
                compatibility = "AGT API >= 1.0",
                permissions = listOf("PROJECT_READ"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = true,
                sourceUri = "builtin://agt.core.compose-m3-review",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("ui", "accessibility", "material3"),
                packageFormat = ExtensionPackageFormat.INLINE_SKILL,
            ),
            format = ExtensionPackageFormat.INLINE_SKILL,
            inlineSkillMarkdown = """
                # Accessibility & UI Review Skill
                - Verify interactive UI controls preserve >=48dp minimum touch targets and descriptive labels.
                - Ensure contrast ratios, semantic headings, and keyboard focus navigation work cleanly across screen sizes.
            """.trimIndent(),
        ),
    )

    val CURATED_ECOSYSTEM_SKILLS: List<ExtensionCatalogEntry> = listOf(
        // Pi Coding Agent Official Ecosystem (badlogic/pi-skills)
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.skills.brave-search",
                name = "Brave Web Search (Pi)",
                description = "Official Pi Agent skill for web search queries and readable page extraction workflows.",
                category = ExtensionCategory.SKILLS,
                publisher = "Pi Official (badlogic/pi-skills)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Agent · OpenCode SKILL.md",
                permissions = listOf("NETWORK_RESEARCH_GUIDE"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/badlogic/pi-skills/tree/main/brave-search",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "search", "web"),
                docsUrl = "https://github.com/badlogic/pi-skills/tree/main/brave-search",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/badlogic/pi-skills/archive/refs/heads/main.zip",
            archiveSubpath = "brave-search",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.skills.browser-tools",
                name = "Browser DevTools Automation (Pi)",
                description = "Official Pi Agent skill guiding headless Chromium / DevTools inspection, console capture, and DOM checks.",
                category = ExtensionCategory.SKILLS,
                publisher = "Pi Official (badlogic/pi-skills)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Agent · OpenCode SKILL.md",
                permissions = listOf("TERMINAL_EXEC"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/badlogic/pi-skills/tree/main/browser-tools",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "browser", "devtools"),
                docsUrl = "https://github.com/badlogic/pi-skills/tree/main/browser-tools",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/badlogic/pi-skills/archive/refs/heads/main.zip",
            archiveSubpath = "browser-tools",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.skills.youtube-transcript",
                name = "YouTube Transcript Skill (Pi)",
                description = "Official Pi Agent skill for fetching and summarizing technical video transcripts cleanly.",
                category = ExtensionCategory.SKILLS,
                publisher = "Pi Official (badlogic/pi-skills)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Agent · OpenCode SKILL.md",
                permissions = listOf("NETWORK_RESEARCH_GUIDE"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/badlogic/pi-skills/tree/main/youtube-transcript",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "transcript", "docs"),
                docsUrl = "https://github.com/badlogic/pi-skills/tree/main/youtube-transcript",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/badlogic/pi-skills/archive/refs/heads/main.zip",
            archiveSubpath = "youtube-transcript",
        ),
        // OpenCode & Universal Agent Skills
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "opencode.skills.systematic-debugging",
                name = "Systematic Debugging",
                description = "OpenCode / Superpowers workflow enforcing root-cause tracing, reproduction, and minimal verified fixes.",
                category = ExtensionCategory.SKILLS,
                publisher = "OpenCode Ecosystem (Superpowers)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ", "TERMINAL_EXEC"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/obra/superpowers/tree/main/skills/systematic-debugging",
                agentTarget = ExtensionAgentTarget.OPENCODE,
                tags = listOf("opencode", "debugging", "root-cause"),
                docsUrl = "https://github.com/obra/superpowers/tree/main/skills/systematic-debugging",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/obra/superpowers/archive/refs/heads/main.zip",
            archiveSubpath = "skills/systematic-debugging",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "opencode.skills.test-driven-dev",
                name = "Test-Driven Development (TDD)",
                description = "Guides OpenCode & Pi through red-green-refactor cycles with failing tests written before production code.",
                category = ExtensionCategory.SKILLS,
                publisher = "OpenCode Ecosystem (Superpowers)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ", "TERMINAL_EXEC"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/obra/superpowers/tree/main/skills/test-driven-development",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("tdd", "testing", "quality"),
                docsUrl = "https://github.com/obra/superpowers/tree/main/skills/test-driven-development",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/obra/superpowers/archive/refs/heads/main.zip",
            archiveSubpath = "skills/test-driven-development",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "opencode.skills.writing-plans",
                name = "Architecture & Task Planning",
                description = "Decomposes complex feature requests into crisp, testable implementation steps before editing files.",
                category = ExtensionCategory.SKILLS,
                publisher = "OpenCode Ecosystem (Superpowers)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/obra/superpowers/tree/main/skills/writing-plans",
                agentTarget = ExtensionAgentTarget.OPENCODE,
                tags = listOf("opencode", "planning", "architecture"),
                docsUrl = "https://github.com/obra/superpowers/tree/main/skills/writing-plans",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/obra/superpowers/archive/refs/heads/main.zip",
            archiveSubpath = "skills/writing-plans",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "opencode.skills.code-review",
                name = "Structured Code Review",
                description = "Audits git diffs for regressions, edge cases, security leaks, and missing tests prior to commit.",
                category = ExtensionCategory.SKILLS,
                publisher = "OpenCode Ecosystem (Superpowers)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/obra/superpowers/tree/main/skills/requesting-code-review",
                agentTarget = ExtensionAgentTarget.OPENCODE,
                tags = listOf("opencode", "review", "security"),
                docsUrl = "https://github.com/obra/superpowers/tree/main/skills/requesting-code-review",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/obra/superpowers/archive/refs/heads/main.zip",
            archiveSubpath = "skills/requesting-code-review",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "universal.skills.mcp-builder",
                name = "MCP Server Design Guide",
                description = "Official guide for designing clean, ergonomic Model Context Protocol tools, schemas, and prompts.",
                category = ExtensionCategory.SKILLS,
                publisher = "Anthropic Official Skills",
                version = "1.0.0",
                license = "Apache-2.0",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/anthropics/skills/tree/main/skills/mcp-builder",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("mcp", "architecture", "sdk"),
                docsUrl = "https://github.com/anthropics/skills/tree/main/skills/mcp-builder",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/anthropics/skills/archive/refs/heads/main.zip",
            archiveSubpath = "skills/mcp-builder",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "universal.skills.webapp-testing",
                name = "Web App Verification & Playwright",
                description = "Guides agents to launch dev servers, inspect DOM state, capture screenshots, and verify frontend flows.",
                category = ExtensionCategory.SKILLS,
                publisher = "Anthropic Official Skills",
                version = "1.0.0",
                license = "Apache-2.0",
                compatibility = "OpenCode · Pi Agent SKILL.md",
                permissions = listOf("PROJECT_READ", "TERMINAL_EXEC"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://github.com/anthropics/skills/tree/main/skills/webapp-testing",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("web", "playwright", "testing"),
                docsUrl = "https://github.com/anthropics/skills/tree/main/skills/webapp-testing",
                packageFormat = ExtensionPackageFormat.ZIP,
            ),
            format = ExtensionPackageFormat.ZIP,
            downloadUrl = "https://github.com/anthropics/skills/archive/refs/heads/main.zip",
            archiveSubpath = "skills/webapp-testing",
        ),
    )

    val CURATED_MCP_PRESETS: List<ExtensionCatalogEntry> = listOf(
        // Remote HTTP/SSE MCPs
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.preset.context7",
                name = "Context7 Live Docs MCP",
                description = "Official Upstash Context7 remote MCP server providing up-to-date framework & library documentation.",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "Upstash Context7 (Official Remote)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Streamable HTTP MCP · OpenCode & Pi",
                permissions = listOf("NETWORK_ACCESS", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://mcp.context7.com/mcp",
                endpointUrl = "https://mcp.context7.com/mcp",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("docs", "sdk", "mcp"),
                docsUrl = "https://context7.com",
                packageFormat = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
                requiresBearerToken = false,
                mcpTransportType = McpTransportType.HTTP_SSE,
            ),
            format = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.preset.deepwiki",
                name = "DeepWiki GitHub Docs MCP",
                description = "Official Devin DeepWiki remote MCP server for instant open-source repository docs & architecture Q&A.",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "Devin DeepWiki (Official Remote)",
                version = "1.0.0",
                license = "Server-Provided",
                compatibility = "Streamable HTTP MCP · OpenCode & Pi",
                permissions = listOf("NETWORK_ACCESS", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://mcp.deepwiki.com/mcp",
                endpointUrl = "https://mcp.deepwiki.com/mcp",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("github", "wiki", "docs"),
                docsUrl = "https://docs.devin.ai/work-with-devin/deepwiki-mcp",
                packageFormat = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
                requiresBearerToken = false,
                mcpTransportType = McpTransportType.HTTP_SSE,
            ),
            format = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.preset.github-official",
                name = "GitHub Official Remote MCP",
                description = "Official GitHub remote MCP server for repositories, issues, PRs, and code search (requires GitHub PAT).",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "GitHub Official",
                version = "1.0.0",
                license = "Server-Provided",
                compatibility = "Streamable HTTP MCP · OpenCode & Pi",
                permissions = listOf("NETWORK_ACCESS", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://api.githubcopilot.com/mcp/",
                endpointUrl = "https://api.githubcopilot.com/mcp/",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("github", "issues", "pr"),
                docsUrl = "https://github.com/github/github-mcp-server",
                packageFormat = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
                requiresBearerToken = true,
                mcpTransportType = McpTransportType.HTTP_SSE,
            ),
            format = ExtensionPackageFormat.REMOTE_MCP_TEMPLATE,
        ),
        // Phase 6.2 Local PRoot npx/stdio MCP Presets
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.local.filesystem",
                name = "Filesystem Local MCP (npx)",
                description = "Official MCP filesystem server running inside PRoot Linux rootfs scoped to project directory.",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "Model Context Protocol (PRoot npx)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "PRoot Local Stdio MCP · OpenCode & Pi",
                permissions = listOf("PROOT_SANDBOX_EXEC", "PROJECT_READ", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "stdio://npx -y @modelcontextprotocol/server-filesystem .",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("npx", "filesystem", "proot"),
                docsUrl = "https://github.com/modelcontextprotocol/servers",
                packageFormat = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
                mcpTransportType = McpTransportType.LOCAL_STDIO,
                localMcpCommand = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "."),
            ),
            format = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
            localCommand = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "."),
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.local.memory",
                name = "Memory Graph MCP (npx)",
                description = "Official persistent local entity-relation memory graph server via Node npx inside PRoot.",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "Model Context Protocol (PRoot npx)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "PRoot Local Stdio MCP · OpenCode & Pi",
                permissions = listOf("PROOT_SANDBOX_EXEC", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "stdio://npx -y @modelcontextprotocol/server-memory",
                agentTarget = ExtensionAgentTarget.UNIVERSAL,
                tags = listOf("npx", "memory", "graph"),
                docsUrl = "https://github.com/modelcontextprotocol/servers",
                packageFormat = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
                mcpTransportType = McpTransportType.LOCAL_STDIO,
                localMcpCommand = listOf("npx", "-y", "@modelcontextprotocol/server-memory"),
            ),
            format = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
            localCommand = listOf("npx", "-y", "@modelcontextprotocol/server-memory"),
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "mcp.local.sequential-thinking",
                name = "Sequential Thinking MCP (npx)",
                description = "Official reflective problem-solving & dynamic step-decomposition MCP via PRoot npx.",
                category = ExtensionCategory.MCP_SERVERS,
                publisher = "Model Context Protocol (PRoot npx)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "PRoot Local Stdio MCP · OpenCode & Pi",
                permissions = listOf("PROOT_SANDBOX_EXEC", "MCP_TOOL_CALL"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "stdio://npx -y @modelcontextprotocol/server-sequential-thinking",
                agentTarget = ExtensionAgentTarget.OPENCODE,
                tags = listOf("npx", "reasoning", "opencode"),
                docsUrl = "https://github.com/modelcontextprotocol/servers",
                packageFormat = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
                mcpTransportType = McpTransportType.LOCAL_STDIO,
                localMcpCommand = listOf("npx", "-y", "@modelcontextprotocol/server-sequential-thinking"),
            ),
            format = ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE,
            localCommand = listOf("npx", "-y", "@modelcontextprotocol/server-sequential-thinking"),
        ),
    )

    val CURATED_SANDBOXED_PLUGINS: List<ExtensionCatalogEntry> = listOf(
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.plugin.confirm-destructive",
                name = "Destructive Bash Guard (Pi Plugin)",
                description = "Official Pi TypeScript extension intercepting dangerous rm -rf / git reset commands inside PRoot.",
                category = ExtensionCategory.PLUGINS,
                publisher = "Pi Official (badlogic/pi-mono)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Coding Agent TS Extension · PRoot Sandbox",
                permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_TOOL_HOOK"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/confirm-destructive.ts",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "safety", "typescript"),
                docsUrl = "https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent/examples/extensions",
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = "confirm-destructive.ts",
            ),
            format = ExtensionPackageFormat.JS_TS_PLUGIN,
            downloadUrl = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/confirm-destructive.ts",
            pluginEntrypoint = "confirm-destructive.ts",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.plugin.protected-paths",
                name = "Protected Paths Guard (Pi Plugin)",
                description = "Official Pi TypeScript extension blocking accidental writes to .env, lockfiles, and protected configs.",
                category = ExtensionCategory.PLUGINS,
                publisher = "Pi Official (badlogic/pi-mono)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Coding Agent TS Extension · PRoot Sandbox",
                permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_TOOL_HOOK"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/protected-paths.ts",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "security", "typescript"),
                docsUrl = "https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent/examples/extensions",
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = "protected-paths.ts",
            ),
            format = ExtensionPackageFormat.JS_TS_PLUGIN,
            downloadUrl = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/protected-paths.ts",
            pluginEntrypoint = "protected-paths.ts",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "pi.plugin.preset-switcher",
                name = "Workflow Preset Command (Pi Plugin)",
                description = "Official Pi extension adding quick /preset switching for model, thinking level, and tool modes.",
                category = ExtensionCategory.PLUGINS,
                publisher = "Pi Official (badlogic/pi-mono)",
                version = "1.0.0",
                license = "MIT",
                compatibility = "Pi Coding Agent TS Extension · PRoot Sandbox",
                permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_SLASH_COMMAND"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/preset.ts",
                agentTarget = ExtensionAgentTarget.PI,
                tags = listOf("pi", "preset", "commands"),
                docsUrl = "https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent/examples/extensions",
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = "preset.ts",
            ),
            format = ExtensionPackageFormat.JS_TS_PLUGIN,
            downloadUrl = "https://raw.githubusercontent.com/badlogic/pi-mono/main/packages/coding-agent/examples/extensions/preset.ts",
            pluginEntrypoint = "preset.ts",
        ),
        ExtensionCatalogEntry(
            record = ExtensionRecord(
                id = "opencode.plugin.command-hooks",
                name = "OpenCode Tool Audit Plugin",
                description = "OpenCode TypeScript plugin lifecycle bundle logging session and tool-call execution hooks inside PRoot.",
                category = ExtensionCategory.PLUGINS,
                publisher = "OpenCode Ecosystem",
                version = "1.0.0",
                license = "MIT",
                compatibility = "OpenCode JS/TS Plugin · PRoot Sandbox",
                permissions = listOf("PROOT_SANDBOX_EXEC", "AGENT_TOOL_HOOK"),
                installState = ExtensionInstallState.NOT_INSTALLED,
                isBuiltIn = false,
                sourceUri = "builtin://opencode.plugin.command-hooks",
                agentTarget = ExtensionAgentTarget.OPENCODE,
                tags = listOf("opencode", "plugin", "hooks"),
                packageFormat = ExtensionPackageFormat.JS_TS_PLUGIN,
                pluginEntrypoint = "index.ts",
            ),
            format = ExtensionPackageFormat.JS_TS_PLUGIN,
            inlineSkillMarkdown = """
                // OpenCode Sandboxed Audit Plugin (PRoot Node/Bun worker only)
                export const AgtAuditPlugin = async ({ project }) => {
                  return {
                    "tool.execute.before": async (input) => {
                      if (process.env.AGT_IS_PROOT !== "1") return;
                      // Logged through stdout for AGT ToolBroker visibility
                      console.error("[opencode-plugin] tool:", input?.tool ?? "unknown");
                    }
                  };
                };
                export default AgtAuditPlugin;
            """.trimIndent(),
            pluginEntrypoint = "index.ts",
        ),
    )

    val DEFAULT_CATALOG: List<ExtensionCatalogEntry> =
        BUILT_IN_SKILLS + CURATED_ECOSYSTEM_SKILLS + CURATED_MCP_PRESETS + CURATED_SANDBOXED_PLUGINS

    fun parseRemoteCatalogJson(jsonText: String): Result<List<ExtensionCatalogEntry>> = runCatching {
        val root = JSONObject(jsonText)
        val rawArray = root.optJSONArray("extensions")
            ?: root.optJSONArray("skills")
            ?: throw IllegalArgumentException("Catalog JSON must contain an 'extensions' or 'skills' array.")
        val parsed = mutableListOf<ExtensionCatalogEntry>()
        for (i in 0 until rawArray.length()) {
            val obj = rawArray.optJSONObject(i) ?: continue
            val rawCat = obj.optString("category", "SKILLS").uppercase()
            val category = when {
                rawCat.contains("PLUGIN") -> ExtensionCategory.PLUGINS
                rawCat.contains("MCP") -> ExtensionCategory.MCP_SERVERS
                else -> ExtensionCategory.SKILLS
            }

            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            val id = ExtensionPackageValidator.validateExtensionId(
                obj.optString("id").trim().ifBlank { ExtensionPackageValidator.slugifyId(name) }
            ).getOrNull() ?: continue

            val targetStr = obj.optString("agentTarget", obj.optString("agent", "UNIVERSAL")).uppercase()
            val agentTarget = when {
                targetStr.contains("OPENCODE") -> ExtensionAgentTarget.OPENCODE
                targetStr.contains("PI") -> ExtensionAgentTarget.PI
                else -> ExtensionAgentTarget.UNIVERSAL
            }

            val tags = buildList {
                val arr = obj.optJSONArray("tags") ?: JSONArray()
                for (t in 0 until arr.length()) {
                    val v = arr.optString(t).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
            val permissions = buildList {
                val arr = obj.optJSONArray("permissions") ?: JSONArray()
                for (p in 0 until arr.length()) {
                    val v = arr.optString(p).trim()
                    if (v.isNotEmpty()) add(v)
                }
                if (isEmpty()) {
                    add(
                        when (category) {
                            ExtensionCategory.MCP_SERVERS -> "NETWORK_ACCESS"
                            ExtensionCategory.PLUGINS -> "PROOT_SANDBOX_EXEC"
                            ExtensionCategory.SKILLS -> "PROJECT_READ"
                        }
                    )
                }
            }

            val transportStr = obj.optString("transport", obj.optString("mcpTransportType", "")).uppercase()
            val mcpTransport = if (transportStr.contains("STDIO") || transportStr.contains("LOCAL")) {
                McpTransportType.LOCAL_STDIO
            } else {
                McpTransportType.HTTP_SSE
            }
            val localCommand = buildList {
                val cmdArr = obj.optJSONArray("command") ?: obj.optJSONArray("localCommand")
                if (cmdArr != null) {
                    for (c in 0 until cmdArr.length()) {
                        val tok = cmdArr.optString(c).trim()
                        if (tok.isNotEmpty()) add(tok)
                    }
                }
            }
            if (mcpTransport == McpTransportType.LOCAL_STDIO) {
                if (localCommand.isEmpty() ||
                    ExtensionPackageValidator.validateLocalMcpCommandTokens(localCommand).isFailure
                ) {
                    continue
                }
            }

            val downloadUrl = obj.optString("downloadUrl").trim().takeIf { it.isNotEmpty() }
            val endpointUrl = obj.optString("endpointUrl").trim().takeIf { it.isNotEmpty() }
            val urlToValidate = if (category == ExtensionCategory.MCP_SERVERS && mcpTransport == McpTransportType.HTTP_SSE) {
                endpointUrl
            } else {
                downloadUrl
            }
            if (urlToValidate != null &&
                ExtensionPackageValidator.validateResourceUrl(urlToValidate) !is GatewayUrlValidation.Valid
            ) {
                continue
            }

            val formatStr = obj.optString("format").trim().uppercase()
            val format = when {
                category == ExtensionCategory.MCP_SERVERS && mcpTransport == McpTransportType.LOCAL_STDIO ->
                    ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE
                category == ExtensionCategory.MCP_SERVERS ->
                    ExtensionPackageFormat.REMOTE_MCP_TEMPLATE
                formatStr == "TAR_GZ" || formatStr == "TGZ" -> ExtensionPackageFormat.TAR_GZ
                formatStr == "ZIP" -> ExtensionPackageFormat.ZIP
                formatStr == "JS_TS_PLUGIN" || category == ExtensionCategory.PLUGINS ->
                    ExtensionPackageFormat.JS_TS_PLUGIN
                formatStr == "RAW_SKILL_MD" || formatStr == "MARKDOWN" -> ExtensionPackageFormat.RAW_SKILL_MD
                downloadUrl?.endsWith(".tar.gz", ignoreCase = true) == true ||
                    downloadUrl?.endsWith(".tgz", ignoreCase = true) == true -> ExtensionPackageFormat.TAR_GZ
                downloadUrl?.endsWith(".zip", ignoreCase = true) == true -> ExtensionPackageFormat.ZIP
                else -> ExtensionPackageFormat.RAW_SKILL_MD
            }

            val version = obj.optString("version").trim().ifBlank { "1.0.0" }
            val publisher = obj.optString("publisher").trim().ifBlank { "Remote Registry" }
            val description = obj.optString("description").trim()
                .ifBlank { "Remote ${agentTarget.label} extension." }
            val license = obj.optString("license").trim().ifBlank { "MIT" }
            val compatibility = obj.optString("compatibility").trim()
                .ifBlank { "${agentTarget.label} · AGT API >= 1.0" }
            val docsUrl = obj.optString("docsUrl").trim().takeIf { it.isNotEmpty() }
            val sha256 = obj.optString("sha256").trim().takeIf { it.isNotEmpty() }
            val archiveSubpath = obj.optString("archiveSubpath").trim().takeIf { it.isNotEmpty() }
            val pluginEntrypoint = obj.optString("entrypoint").trim()
                .ifBlank { obj.optString("pluginEntrypoint").trim() }
                .takeIf { it.isNotEmpty() }
            val requiresToken = obj.optBoolean("requiresBearerToken", false)

            parsed += ExtensionCatalogEntry(
                record = ExtensionRecord(
                    id = id,
                    name = name,
                    description = description,
                    category = category,
                    publisher = publisher,
                    version = version,
                    license = license,
                    compatibility = compatibility,
                    permissions = permissions,
                    installState = ExtensionInstallState.NOT_INSTALLED,
                    isBuiltIn = false,
                    sourceUri = downloadUrl
                        ?: endpointUrl
                        ?: if (localCommand.isNotEmpty()) "stdio://${localCommand.joinToString(" ")}" else "catalog://$id",
                    sha256 = sha256.orEmpty(),
                    endpointUrl = endpointUrl,
                    agentTarget = agentTarget,
                    tags = tags,
                    docsUrl = docsUrl,
                    packageFormat = format,
                    requiresBearerToken = requiresToken,
                    mcpTransportType = mcpTransport,
                    localMcpCommand = localCommand,
                    pluginEntrypoint = pluginEntrypoint,
                ),
                format = format,
                downloadUrl = downloadUrl,
                archiveSubpath = archiveSubpath,
                expectedSha256 = sha256,
                localCommand = localCommand,
                pluginEntrypoint = pluginEntrypoint,
            )
        }
        require(parsed.isNotEmpty()) { "Catalog JSON contained no valid extensions." }
        parsed
    }

    fun serializeCatalogEntries(entries: List<ExtensionCatalogEntry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            val r = entry.record
            val obj = JSONObject()
                .put("id", r.id)
                .put("name", r.name)
                .put("description", r.description)
                .put("category", r.category.name)
                .put("publisher", r.publisher)
                .put("version", r.version)
                .put("license", r.license)
                .put("compatibility", r.compatibility)
                .put("agentTarget", r.agentTarget.name)
                .put("format", entry.format.name)
                .put("transport", r.mcpTransportType.name)
                .put("requiresBearerToken", r.requiresBearerToken)
                .put("permissions", JSONArray(r.permissions))
                .put("tags", JSONArray(r.tags))
            if (entry.localCommand.isNotEmpty()) {
                obj.put("command", JSONArray(entry.localCommand))
            }
            entry.pluginEntrypoint?.let { obj.put("entrypoint", it) }
            entry.downloadUrl?.let { obj.put("downloadUrl", it) }
            entry.archiveSubpath?.let { obj.put("archiveSubpath", it) }
            entry.expectedSha256?.let { obj.put("sha256", it) }
            r.endpointUrl?.let { obj.put("endpointUrl", it) }
            r.docsUrl?.let { obj.put("docsUrl", it) }
            arr.put(obj)
        }
        return JSONObject().put("extensions", arr).toString(2)
    }
}

