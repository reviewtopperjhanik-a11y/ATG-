#!/usr/bin/env python3
"""
AGT Linux Runtime Manifest Generator & Verifier.

Generates and strictly verifies hosted runtime manifests compatible with
`com.example.data.runtime.RuntimeManifestParser` and
`com.example.data.runtime.RuntimeInstallerRepository`, enforcing:
  - HTTPS-only, non-placeholder download URLs
  - Exact compressed byte sizes and lowercase 64-char SHA-256 checksums
  - ARM64 (`arm64-v8a`) coverage and archive path verification
  - Path-traversal-free archive entries (zero `..`, leading `/`, or unsafe symlinks)
  - Verification of all 5 AGT runtime components and dependency DAG
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tarfile
import urllib.parse
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Set, Tuple

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PLACEHOLDER_TOKENS = ("example.com", "example.org", "example.net", "placeholder", "your_")
REQUIRED_COMPONENT_IDS = (
    "linux_runtime",
    "base_toolchains",
    "opencode_agent",
    "github_cli",
    "base_skills_mcp",
)


def default_components(version: str) -> List[dict]:
    return [
        {
            "id": "linux_runtime",
            "name": "Linux Runtime (PRoot / Ubuntu)",
            "description": "ARM64 rootfs userspace for shells, compilers, and dev servers.",
            "version": version,
            "required_paths": ["bin/sh", "bin/proot", "etc/os-release"],
            "dependencies": [],
        },
        {
            "id": "base_toolchains",
            "name": "Base Toolchains",
            "description": "Shell utilities, coreutils, Git, Node.js, and essential build utilities.",
            "version": version,
            "required_paths": ["usr/bin/git", "usr/bin/node"],
            "dependencies": ["linux_runtime"],
        },
        {
            "id": "opencode_agent",
            "name": "OpenCode Agent Runtime",
            "description": "Primary autonomous coding runtime connected to JH GetWay Pro.",
            "version": version,
            "required_paths": ["usr/local/bin/opencode"],
            "dependencies": ["linux_runtime", "base_toolchains"],
        },
        {
            "id": "github_cli",
            "name": "GitHub CLI (gh)",
            "description": "Repository discovery, clone, commit, and pull request tooling.",
            "version": version,
            "required_paths": ["usr/bin/gh"],
            "dependencies": ["linux_runtime", "base_toolchains"],
        },
        {
            "id": "base_skills_mcp",
            "name": "Base Skills & MCP Registry",
            "description": "Repository inspection skills and Streamable HTTP / SSE MCP client support.",
            "version": version,
            "required_paths": ["usr/local/share/agt/skills/registry.json"],
            "dependencies": ["linux_runtime", "opencode_agent"],
        },
    ]


def default_licenses() -> List[dict]:
    return [
        {
            "component": "PRoot",
            "license": "GNU General Public License v2.0",
            "spdx_id": "GPL-2.0-only",
            "summary": "Userspace chroot/mount-bind layer (not a hardened VM boundary).",
        },
        {
            "component": "GNU Talloc",
            "license": "GNU Lesser General Public License v3.0 or later",
            "spdx_id": "LGPL-3.0-or-later",
            "summary": "Hierarchical pool memory allocator required by PRoot.",
        },
        {
            "component": "Git",
            "license": "GNU General Public License v2.0",
            "spdx_id": "GPL-2.0-only",
            "summary": "Distributed version control system bundled in base toolchains.",
        },
        {
            "component": "Node.js",
            "license": "MIT License",
            "spdx_id": "MIT",
            "summary": "JavaScript runtime for MCP tools and web project toolchains.",
        },
        {
            "component": "GitHub CLI (gh)",
            "license": "MIT License",
            "spdx_id": "MIT",
            "summary": "GitHub repository and pull-request command-line client.",
        },
    ]


def validate_https_url(url: str) -> str:
    clean = url.strip()
    if not clean:
        raise ValueError("Download URL cannot be empty.")
    lower = clean.lower()
    if any(tok in lower for tok in PLACEHOLDER_TOKENS):
        raise ValueError(f"Placeholder URL rejected: {clean}")
    parsed = urllib.parse.urlparse(clean)
    if parsed.scheme.lower() != "https":
        raise ValueError(f"Production manifest and bundle URLs must use HTTPS: {clean}")
    if not parsed.netloc:
        raise ValueError(f"URL missing host: {clean}")
    return clean


def sanitize_rel_path(path: str, allow_empty: bool = False) -> str:
    cleaned = path.strip().strip("/")
    if not cleaned:
        if allow_empty:
            return ""
        raise ValueError("Relative path cannot be empty.")
    if path.startswith("/") or "\\" in cleaned or "\x00" in cleaned:
        raise ValueError(f"Unsafe path rejected: {path}")
    segs = cleaned.split("/")
    if any(s in ("", ".", "..") for s in segs):
        raise ValueError(f"Path traversal segment rejected: {path}")
    return cleaned


def detect_archive_format(archive_path: Path) -> str:
    name = archive_path.name.lower()
    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        return "tar.gz"
    if name.endswith(".tar"):
        return "tar"
    if name.endswith(".zip"):
        return "zip"
    raise ValueError(f"Unsupported archive format for {archive_path} (expected .tar.gz, .tar, or .zip)")


def compute_sha256_and_size(path: Path) -> Tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest().lower(), size


def inspect_archive_entries(archive_path: Path, fmt: str, extraction_root: str) -> Set[str]:
    prefix = f"{extraction_root.strip('/')}/" if extraction_root.strip("/") else ""
    rel_paths: Set[str] = set()

    def record_entry(raw_name: str, linkname: str = "") -> None:
        normalized = raw_name.replace("\\", "/")
        if normalized.startswith("/") or "\x00" in normalized:
            raise ValueError(f"Archive contains absolute/null entry: {raw_name}")
        clean = normalized.lstrip("./")
        if any(seg == ".." for seg in clean.split("/")):
            raise ValueError(f"Archive contains path traversal entry: {raw_name}")
        if linkname:
            if linkname.startswith("/") or any(seg == ".." for seg in linkname.replace("\\", "/").split("/")):
                raise ValueError(f"Archive contains escaping symlink target '{linkname}' at '{raw_name}'")
        if prefix:
            if clean.startswith(prefix):
                rel = clean[len(prefix):].rstrip("/")
                if rel:
                    rel_paths.add(rel)
        else:
            rel = clean.rstrip("/")
            if rel:
                rel_paths.add(rel)

    if fmt in ("tar.gz", "tar"):
        mode = "r:gz" if fmt == "tar.gz" else "r:"
        with tarfile.open(archive_path, mode) as tf:
            for member in tf.getmembers():
                record_entry(member.name, member.linkname if (member.issym() or member.islnk()) else "")
    elif fmt == "zip":
        with zipfile.ZipFile(archive_path, "r") as zf:
            for info in zf.infolist():
                record_entry(info.filename)
    else:
        raise ValueError(f"Unsupported archive format: {fmt}")

    return rel_paths


def validate_component_graph(components: List[dict]) -> None:
    if not components:
        raise ValueError("Manifest must declare components.")
    seen: Dict[str, dict] = {}
    for comp in components:
        cid = comp.get("id", "").strip()
        cname = comp.get("name", "").strip()
        if not cid or not cname:
            raise ValueError(f"Component missing id or name: {comp}")
        if cid in seen:
            raise ValueError(f"Duplicate component id: {cid}")
        for req in comp.get("required_paths", []):
            sanitize_rel_path(req, allow_empty=False)
        seen[cid] = comp

    for cid in REQUIRED_COMPONENT_IDS:
        if cid not in seen:
            raise ValueError(f"Manifest missing required AGT component '{cid}'.")

    for cid, comp in seen.items():
        for dep in comp.get("dependencies", []):
            if dep == cid:
                raise ValueError(f"Component '{cid}' cannot depend on itself.")
            if dep not in seen:
                raise ValueError(f"Component '{cid}' depends on unknown component '{dep}'.")

    state: Dict[str, int] = {}

    def dfs(cid: str) -> None:
        st = state.get(cid, 0)
        if st == 1:
            raise ValueError(f"Circular component dependency detected at '{cid}'.")
        if st == 2:
            return
        state[cid] = 1
        for dep in seen[cid].get("dependencies", []):
            dfs(dep)
        state[cid] = 2

    for cid in seen:
        dfs(cid)


def cmd_generate(args: argparse.Namespace) -> int:
    extraction_root = sanitize_rel_path(args.extraction_root, allow_empty=True)
    if args.components_file:
        components = json.loads(Path(args.components_file).read_text(encoding="utf-8"))
    else:
        components = default_components(args.version)

    validate_component_graph(components)
    component_required = [p for c in components for p in c.get("required_paths", [])]
    base_required = [sanitize_rel_path(p) for p in args.required_path] if args.required_path else ["bin/sh", "bin/proot"]
    bundle_required = list(dict.fromkeys(base_required + component_required))

    bundles: Dict[str, dict] = {}
    for spec in args.bundle:
        parts = spec.split("=", 2)
        if len(parts) != 3:
            raise ValueError(f"--bundle must follow ABI=ARCHIVE_PATH=HTTPS_URL, got: {spec}")
        abi, archive_str, url_str = parts[0].strip(), parts[1].strip(), parts[2].strip()
        if not abi:
            raise ValueError("ABI cannot be empty.")
        url = validate_https_url(url_str)
        archive_path = Path(archive_str).resolve()
        if not archive_path.is_file():
            raise FileNotFoundError(f"Archive not found: {archive_path}")

        fmt = detect_archive_format(archive_path)
        sha256_hex, size_bytes = compute_sha256_and_size(archive_path)
        entries = inspect_archive_entries(archive_path, fmt, extraction_root)

        missing = [req for req in bundle_required if req not in entries]
        if missing:
            raise ValueError(
                f"Archive '{archive_path.name}' ({abi}) is missing required runtime/component paths "
                f"(under extraction_root='{extraction_root}'): {', '.join(missing)}"
            )

        bundles[abi] = {
            "download_url": url,
            "compressed_size_bytes": size_bytes,
            "sha256": sha256_hex,
            "archive_format": fmt,
            "extraction_root": extraction_root,
            "required_paths": base_required,
        }

    if "arm64-v8a" not in bundles:
        raise ValueError("Release manifest must include an arm64-v8a bundle.")

    manifest = {
        "manifest_schema_version": 1,
        "generated_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "runtime_id": args.runtime_id,
        "version": args.version,
        "release_channel": args.channel,
        "supported_abis": list(bundles.keys()),
        "min_android_sdk": args.min_android_sdk,
        "min_required_free_bytes": args.min_free_bytes,
        "bundles": bundles,
        "components": components,
        "license_notices": default_licenses(),
    }

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(manifest, indent=2) + "\n"
    out_path.write_text(rendered, encoding="utf-8")
    manifest_sha = hashlib.sha256(rendered.encode("utf-8")).hexdigest()
    sha_file = out_path.with_suffix(out_path.suffix + ".sha256")
    sha_file.write_text(f"{manifest_sha}  {out_path.name}\n", encoding="utf-8")

    print(f"Generated valid manifest: {out_path} (sha256={manifest_sha})")
    for abi, b in bundles.items():
        print(f"  - {abi}: {b['compressed_size_bytes']} bytes, sha256={b['sha256']}, url={b['download_url']}")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    data = json.loads(manifest_path.read_text(encoding="utf-8"))

    for key in ("runtime_id", "version", "release_channel", "supported_abis"):
        if not data.get(key):
            raise ValueError(f"Manifest missing '{key}'.")

    version = str(data["version"])
    if ".." in version or "/" in version or "\\" in version:
        raise ValueError(f"Unsafe manifest version: {version}")

    min_sdk = int(data.get("min_android_sdk", 26))
    if not (24 <= min_sdk <= 100):
        raise ValueError(f"min_android_sdk out of range: {min_sdk}")

    supported_abis = data.get("supported_abis", [])
    if args.require_abi not in supported_abis:
        raise ValueError(f"supported_abis {supported_abis} missing required ABI '{args.require_abi}'.")

    bundles = data.get("bundles") or {}
    if not bundles and "bundle" in data:
        bundles = {args.require_abi: data["bundle"]}
    if args.require_abi not in bundles:
        raise ValueError(f"Manifest bundles missing '{args.require_abi}'.")

    for abi, bundle in bundles.items():
        validate_https_url(bundle.get("download_url", ""))
        sha = str(bundle.get("sha256", "")).strip().lower()
        if not SHA256_RE.match(sha):
            raise ValueError(f"Bundle '{abi}' has invalid sha256: {sha}")
        if int(bundle.get("compressed_size_bytes", 0)) <= 0:
            raise ValueError(f"Bundle '{abi}' must have compressed_size_bytes > 0.")
        sanitize_rel_path(bundle.get("extraction_root", ""), allow_empty=True)
        reqs = bundle.get("required_paths", [])
        if not reqs:
            raise ValueError(f"Bundle '{abi}' missing required_paths.")
        for p in reqs:
            sanitize_rel_path(p, allow_empty=False)

    components = data.get("components", [])
    validate_component_graph(components)
    all_component_paths = [p for c in components for p in c.get("required_paths", [])]

    archive_map: Dict[str, Path] = {}
    for spec in args.archive:
        abi, p_str = spec.split("=", 1)
        archive_map[abi.strip()] = Path(p_str.strip()).resolve()

    for abi, archive_path in archive_map.items():
        if abi not in bundles:
            raise ValueError(f"Verified archive supplied for unknown ABI '{abi}'.")
        if not archive_path.is_file():
            raise FileNotFoundError(f"Archive not found: {archive_path}")
        bundle = bundles[abi]
        actual_sha, actual_size = compute_sha256_and_size(archive_path)
        if actual_size != bundle["compressed_size_bytes"]:
            raise ValueError(
                f"Size mismatch for '{abi}': manifest={bundle['compressed_size_bytes']}, actual={actual_size}"
            )
        if actual_sha != bundle["sha256"]:
            raise ValueError(f"SHA-256 mismatch for '{abi}': manifest={bundle['sha256']}, actual={actual_sha}")
        entries = inspect_archive_entries(archive_path, bundle["archive_format"], bundle.get("extraction_root", ""))
        expected = list(dict.fromkeys(bundle["required_paths"] + all_component_paths))
        missing = [req for req in expected if req not in entries]
        if missing:
            raise ValueError(f"Archive '{archive_path.name}' missing required files: {', '.join(missing)}")

    print(
        f"Manifest verified: runtime_id={data['runtime_id']} version={version} "
        f"abis={','.join(bundles.keys())} components={len(components)}"
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build and verify AGT Linux runtime manifests.")
    sub = parser.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("generate", help="Generate a manifest from verified ABI archive files.")
    gen.add_argument("--runtime-id", default="agt-ubuntu-arm64")
    gen.add_argument("--version", required=True)
    gen.add_argument("--channel", default="stable", choices=["stable", "beta", "canary", "dev"])
    gen.add_argument("--min-android-sdk", type=int, default=26)
    gen.add_argument("--min-free-bytes", type=int, default=268435456)
    gen.add_argument("--extraction-root", default="rootfs")
    gen.add_argument("--required-path", action="append", default=[])
    gen.add_argument("--components-file", default="")
    gen.add_argument(
        "--bundle",
        action="append",
        required=True,
        help="ABI=ARCHIVE_PATH=HTTPS_URL (e.g. arm64-v8a=./out/agt-arm64.tar.gz=https://cdn.../agt-arm64.tar.gz)",
    )
    gen.add_argument("--output", required=True)

    ver = sub.add_parser("verify", help="Verify an existing manifest.json and optional local archives.")
    ver.add_argument("--manifest", required=True)
    ver.add_argument("--require-abi", default="arm64-v8a")
    ver.add_argument("--archive", action="append", default=[], help="Optional ABI=ARCHIVE_PATH to checksum & inspect.")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        if args.command == "generate":
            return cmd_generate(args)
        if args.command == "verify":
            return cmd_verify(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
