package com.example.data.runtime

import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.domain.model.DetectedPreviewServer
import org.json.JSONObject
import java.io.File

/**
 * Inspects workspace files to determine the appropriate local development server command,
 * extracts dynamically bound loopback URLs/ports from server stdout/stderr logs, and
 * validates loopback-only preview URLs for the built-in WebView.
 */
object PreviewServerManager {

    private val urlInOutputRegex = Regex(
        """https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[::\]):(\d{2,5})(/[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val listeningPortRegex = Regex(
        """(?:listening\s+on(?:\s+port)?\s+|serving\s+http\s+on\s+\S+\s+port\s+|started\s+server\s+on\s+\S*:|port\s*[=:]\s*)(\d{2,5})""",
        RegexOption.IGNORE_CASE,
    )

    fun detectPreviewServer(
        projectRoot: File,
        hasPython3: Boolean = true,
    ): DetectedPreviewServer? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return null

        val packageJson = File(projectRoot, "package.json")
        if (packageJson.exists() && packageJson.isFile) {
            detectNodePreviewServer(packageJson)?.let { return it }
        }

        val managePy = File(projectRoot, "manage.py")
        if (managePy.exists()) {
            return DetectedPreviewServer(
                frameworkLabel = "Django",
                command = "python3 manage.py runserver 127.0.0.1:8000",
                suggestedUrl = "http://127.0.0.1:8000",
            )
        }

        val pyproject = File(projectRoot, "pyproject.toml")
        if (pyproject.exists()) {
            val content = runCatching { pyproject.readText() }.getOrDefault("").lowercase()
            if ("uvicorn" in content || "fastapi" in content) {
                return DetectedPreviewServer(
                    frameworkLabel = "FastAPI / Uvicorn",
                    command = "uvicorn main:app --host 127.0.0.1 --port 8000",
                    suggestedUrl = "http://127.0.0.1:8000",
                )
            }
        }

        val goMod = File(projectRoot, "go.mod")
        if (goMod.exists()) {
            return DetectedPreviewServer(
                frameworkLabel = "Go HTTP Server",
                command = "go run .",
                suggestedUrl = "http://127.0.0.1:8080",
            )
        }

        if (File(projectRoot, "index.html").exists() || File(projectRoot, "public/index.html").exists()) {
            val serveDir = if (File(projectRoot, "index.html").exists()) "." else "public"
            val command = if (hasPython3) {
                "python3 -m http.server 8080 --bind 127.0.0.1 -d $serveDir"
            } else {
                buildNodeStaticServerCommand(serveDir)
            }
            return DetectedPreviewServer(
                frameworkLabel = "Static HTML",
                command = command,
                suggestedUrl = "http://127.0.0.1:8080",
            )
        }

        return null
    }

    private fun buildNodeStaticServerCommand(serveDir: String): String {
        val safeDir = if (serveDir == "public") "public" else "."
        val script = "const http=require('http'),fs=require('fs'),path=require('path');" +
            "const root=path.resolve('$safeDir');" +
            "const mime={'.html':'text/html','.js':'application/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png'};" +
            "http.createServer((q,s)=>{const u=decodeURIComponent((q.url||'/').split('?')[0]);" +
            "let f=path.resolve(root,'.'+(u==='/'?'/index.html':u));" +
            "if(!f.startsWith(root)){s.writeHead(403);return s.end();}" +
            "if(fs.existsSync(f)&&fs.statSync(f).isDirectory())f=path.join(f,'index.html');" +
            "fs.readFile(f,(e,d)=>{if(e){s.writeHead(404);return s.end('Not found');}" +
            "s.writeHead(200,{'Content-Type':mime[path.extname(f)]||'application/octet-stream'});s.end(d);});" +
            "}).listen(8080,'127.0.0.1',()=>console.log('Serving http://127.0.0.1:8080'));"
        return "node -e \"$script\""
    }

    private fun detectNodePreviewServer(packageJson: File): DetectedPreviewServer? {
        val raw = runCatching { packageJson.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val scripts = json.optJSONObject("scripts") ?: JSONObject()
        val deps = buildString {
            append(json.optJSONObject("dependencies")?.toString().orEmpty())
            append(' ')
            append(json.optJSONObject("devDependencies")?.toString().orEmpty())
        }.lowercase()

        val hasDev = scripts.has("dev")
        val hasStart = scripts.has("start")
        val hasPreview = scripts.has("preview")
        if (!hasDev && !hasStart && !hasPreview) return null

        return when {
            "vite" in deps -> DetectedPreviewServer(
                frameworkLabel = "Vite",
                command = if (hasDev) "npm run dev -- --host 127.0.0.1" else "npm run preview -- --host 127.0.0.1",
                suggestedUrl = "http://127.0.0.1:5173",
            )
            "next" in deps -> DetectedPreviewServer(
                frameworkLabel = "Next.js",
                command = if (hasDev) "npm run dev -- -H 127.0.0.1" else "npm run start -- -H 127.0.0.1",
                suggestedUrl = "http://127.0.0.1:3000",
            )
            "astro" in deps -> DetectedPreviewServer(
                frameworkLabel = "Astro",
                command = "npm run dev -- --host 127.0.0.1",
                suggestedUrl = "http://127.0.0.1:4321",
            )
            "react-scripts" in deps -> DetectedPreviewServer(
                frameworkLabel = "Create React App",
                command = "HOST=127.0.0.1 npm start",
                suggestedUrl = "http://127.0.0.1:3000",
            )
            else -> {
                val scriptName = when {
                    hasDev -> "dev"
                    hasStart -> "start"
                    else -> "preview"
                }
                DetectedPreviewServer(
                    frameworkLabel = "Node.js",
                    command = "npm run $scriptName",
                    suggestedUrl = "",
                )
            }
        }
    }

    /**
     * Parses a local development server's stdout/stderr line and returns a normalized
     * loopback URL if the server announced its bound host/port.
     */
    fun extractLocalUrlFromOutput(line: String): String? {
        val directMatch = urlInOutputRegex.find(line)
        if (directMatch != null) {
            val port = directMatch.groupValues[1].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val path = directMatch.groupValues.getOrNull(2).orEmpty().trimEnd('/')
            return "http://127.0.0.1:$port$path"
        }
        val portMatch = listeningPortRegex.find(line)
        if (portMatch != null) {
            val port = portMatch.groupValues[1].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return "http://127.0.0.1:$port"
        }
        return null
    }

    /**
     * Ensures preview WebView URLs target loopback HTTP/HTTPS endpoints only.
     */
    fun validatePreviewUrl(rawUrl: String): Result<String> = runCatching {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "Enter a local preview URL (e.g. http://127.0.0.1:5173)." }
        when (val validation = GatewayUrlValidator.validate(trimmed, allowQueryAndFragment = true)) {
            is GatewayUrlValidation.Invalid -> throw IllegalArgumentException(validation.reason)
            is GatewayUrlValidation.Valid -> {
                if (!validation.isLoopback) {
                    throw IllegalArgumentException(
                        "Built-in preview browser only loads local loopback URLs (127.0.0.1 or localhost)."
                    )
                }
                validation.normalizedBaseUrl
            }
        }
    }
}
