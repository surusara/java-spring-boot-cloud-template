# code-review-graph — Enterprise Adoption Guide

**Purpose**: Reduce AI coding assistant token consumption by 4.9x–27.3x through structural codebase indexing, enabling faster reviews, lower costs, and precise context delivery.

**Version evaluated**: v2.1.0 (April 2026)  
**License**: MIT (permissive, no copyleft obligations)  
**Repository**: https://github.com/tirth8205/code-review-graph

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Security Assessment](#2-security-assessment)
3. [Platform Compatibility](#3-platform-compatibility)
4. [How It Works](#4-how-it-works)
5. [Cost-Benefit Analysis](#5-cost-benefit-analysis)
6. [Installation & Setup](#6-installation--setup)
7. [Getting the Best Out of It](#7-getting-the-best-out-of-it)
8. [Enterprise Deployment Considerations](#8-enterprise-deployment-considerations)
9. [Limitations & Risks](#9-limitations--risks)
10. [Recommendation](#10-recommendation)

---

## 1. Executive Summary

Every time an AI coding assistant performs a task (code review, debugging, refactoring), it reads files from your codebase into its context window. Without guidance, it reads **far more files than necessary**, burning tokens on irrelevant code.

`code-review-graph` solves this by:
- Parsing your codebase once with **Tree-sitter** into a structural graph (functions, classes, imports, call sites)
- Storing the graph in a **local SQLite database** (no cloud, no external service)
- Exposing the graph via **MCP (Model Context Protocol)** so AI assistants can query "what files are affected by this change?" and read only those
- Updating incrementally on every file save/git commit in **< 2 seconds**

**Result**: AI reads 6–15 files instead of 50–27,000+. Benchmarked at **8.2x average token reduction**.

---

## 2. Security Assessment

### Source Code Audit Results

We performed a line-by-line audit of all Python source files in `code-review-graph v2.1.0`. Here are the findings:

#### Does it send code externally?

**NO — not during normal operation.** The tool is fully local by default.

| Check | Result | Evidence |
|-------|--------|----------|
| HTTP client libraries | **Not used in core** | No `requests`, `httpx`, `aiohttp` imports in build/query/review paths |
| Telemetry / analytics | **None** | No Sentry, Datadog, Mixpanel, PostHog, or any tracking SDK |
| Phone-home behavior | **None** | No version check pings, no usage reporting |
| Data storage | **Local SQLite only** | `.code-review-graph/graph.db` inside your repo |
| Network listeners | **None** | MCP server uses stdio (stdin/stdout), not HTTP/TCP |
| Source code exfiltration | **None** | Graph stores structural metadata (function names, line numbers, call edges), NOT source code content |

#### Optional features that DO make network calls (all opt-in)

| Feature | When it calls out | What is sent | How to prevent |
|---------|-------------------|--------------|----------------|
| Google Gemini embeddings | Only if `GOOGLE_API_KEY` env var is set AND semantic search is used | Function/class name + docstring snippets for vectorization | **Don't set `GOOGLE_API_KEY`** |
| MiniMax embeddings | Only if `MINIMAX_API_KEY` env var is set | Same as above | **Don't set `MINIMAX_API_KEY`** |
| Local embeddings model | One-time download from HuggingFace on first use | Nothing sent, model downloaded | Use `--no-embeddings` or pre-cache the model |
| D3.js visualization | `graph.html` loads D3.js from CDN with SRI hash | No code sent; loads a JS library | Open only on internal network or serve D3 locally |

**Recommendation for enterprise**: Do not set any API key environment variables. Use the tool in its default mode — all core functionality (build, blast-radius, review context, incremental updates) is **100% offline**.

#### Security mitigations built into the tool

| Threat | Mitigation |
|--------|------------|
| SQL Injection | All queries use parameterized `?` placeholders |
| Path Traversal | `_validate_repo_root()` requires `.git` or `.code-review-graph` directory |
| Prompt Injection | `_sanitize_name()` strips control characters, caps at 256 chars |
| XSS (visualization) | HTML entities escaped; `</script>` escaped in JSON payloads |
| Subprocess Injection | No `shell=True`; all git commands use list arguments |
| Supply Chain | Dependencies pinned with upper bounds; `uv.lock` has SHA-256 hashes |
| CDN Tampering | D3.js loaded with Subresource Integrity (SRI) hash verification |

#### What is stored in the graph database?

The SQLite database stores **structural metadata**, not source code:

```
Nodes: kind, name, qualified_name, file_path, line_start, line_end, language, params, return_type, modifiers
Edges: kind (CALLS, IMPORTS_FROM, CONTAINS, INHERITS), source, target, file_path, line
```

**No function bodies, no variable values, no business logic, no secrets** are stored.

#### Environment variables the tool reads

| Variable | Purpose | Required? |
|----------|---------|-----------|
| `CRG_GIT_TIMEOUT` | Git command timeout (seconds) | No (default: 30) |
| `CRG_EMBEDDING_MODEL` | Local embedding model name | No |
| `NO_COLOR` | Disable terminal colors | No |
| `GOOGLE_API_KEY` | Google Gemini embeddings (cloud) | No — **do not set in enterprise** |
| `MINIMAX_API_KEY` | MiniMax embeddings (cloud) | No — **do not set in enterprise** |

---

## 3. Platform Compatibility

### Does it work only with Claude? **No.**

`code-review-graph` uses the **Model Context Protocol (MCP)**, an open standard. It works with any AI tool that supports MCP:

| Platform | Support | Auto-configured by `install` |
|----------|---------|------------------------------|
| **Claude Code** | Full | Yes |
| **Cursor** | Full | Yes |
| **Windsurf** | Full | Yes |
| **Zed** | Full | Yes |
| **Continue** | Full | Yes |
| **OpenCode** | Full | Yes |
| **Antigravity** | Full | Yes |
| **VS Code + GitHub Copilot** | Via MCP | Manual `.mcp.json` setup |
| **Any MCP-compatible tool** | Via stdio | Manual config |

The `install` command auto-detects which platforms you have installed and writes the correct configuration for each.

```bash
code-review-graph install                     # configure all detected platforms
code-review-graph install --platform cursor   # configure Cursor only
```

### Language Support

19 languages with full Tree-sitter grammar support:

**Web**: TypeScript, TSX, JavaScript, Vue  
**Backend**: Python, Java, Go, Rust, Scala, C#, Ruby, Kotlin, PHP  
**Systems**: C, C++  
**Mobile**: Swift, Dart  
**Scripting**: R, Perl, Lua  
**Notebooks**: Jupyter/Databricks (.ipynb) with multi-language cell support

---

## 4. How It Works

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Your Repository                     │
│  .java, .py, .ts, .go files                         │
└──────────────────┬──────────────────────────────────┘
                   │ Tree-sitter parse
                   ▼
┌─────────────────────────────────────────────────────┐
│              SQLite Graph Database                    │
│  .code-review-graph/graph.db (LOCAL)                │
│                                                      │
│  341 nodes: functions, classes, files                │
│  1,786 edges: calls, imports, contains, inherits    │
└──────────────────┬──────────────────────────────────┘
                   │ MCP (stdio, local)
                   ▼
┌─────────────────────────────────────────────────────┐
│           AI Coding Assistant                        │
│  Queries: "What is affected by this change?"        │
│  Receives: 6 specific files instead of 52           │
└─────────────────────────────────────────────────────┘
```

### Data Flow (No external calls)

```
1. Developer saves file / commits
2. File watcher triggers incremental re-parse (< 2 seconds)
3. Only changed files are re-parsed via Tree-sitter
4. Graph updates nodes/edges in local SQLite
5. AI assistant queries graph via MCP (stdin/stdout)
6. Graph returns minimal file set + risk scores
7. AI reads only those files into context
```

### Key Concepts

| Concept | What it means |
|---------|---------------|
| **Blast radius** | All files affected by a change: callers, dependents, tests, inherited classes |
| **Incremental update** | SHA-256 hash per file; only re-parses files whose hash changed |
| **Execution flows** | Traced call chains from entry points (e.g., REST controller → service → repository) |
| **Community detection** | Clusters related code using graph algorithms (Leiden) for architectural overview |
| **Risk scoring** | Function complexity + change frequency + test coverage gaps |

---

## 5. Cost-Benefit Analysis

### Token cost savings

| Scenario | Without graph | With graph | Reduction |
|----------|---------------|------------|-----------|
| Single file code review (50-file project) | ~50 files read | ~6 files | **8.3x** |
| PR review (500-file project) | ~100+ files | ~15 files | **6.7x** |
| Monorepo review (27,000+ files) | ~500+ files | ~15 files | **33x** |
| Daily coding tasks | Full workspace scan | Targeted reads | **up to 49x** |

### What this means financially

Assuming a team of 20 developers, each making 5 AI-assisted code reviews/day:

| Metric | Without graph | With graph (8x reduction) |
|--------|---------------|---------------------------|
| Tokens per review (est.) | 50,000 | 6,250 |
| Daily tokens (team) | 5,000,000 | 625,000 |
| Monthly tokens | ~100M | ~12.5M |
| **Monthly cost saving** | — | **~87.5% reduction** |

*Actual savings depend on your LLM provider pricing, context window sizes, and repository structure.*

### Non-financial benefits

- **Faster responses**: Less context = faster LLM inference
- **Better accuracy**: Precise context = fewer hallucinations and missed dependencies
- **Longer conversations**: More token budget available for actual reasoning
- **Consistent reviews**: Graph ensures no affected file is missed (100% recall in benchmarks)

---

## 6. Installation & Setup

### Prerequisites

- Python 3.10+ (verified working with Python 3.13)
- Git (repository must be a git repo)
- Any MCP-compatible AI coding tool

### Step 1: Install

```bash
pip install code-review-graph
```

### Step 2: Configure platforms

```bash
cd /path/to/your/project
code-review-graph install          # auto-detects and configures all platforms
```

This creates:
- `.mcp.json` — MCP server configuration
- `.claude/skills/` — AI skill files (if Claude Code detected)
- `.cursorrules` / `.windsurfrules` — Platform-specific instruction injection

### Step 3: Build the graph

```bash
code-review-graph build
```

First build parses all files (~10 seconds for 500 files). After that, updates are incremental (< 2 seconds).

### Step 4: Verify

```bash
code-review-graph status
```

Expected output:
```
Nodes: 341
Edges: 1786
Files: 52
Languages: java
Last updated: 2026-04-06T13:29:29
```

### Step 5: Restart your AI coding tool

The tool needs to pick up the new `.mcp.json` configuration.

### Known Issue: Python 3.13

If you see `"cannot start a transaction within a transaction"` errors during build, this is a Python 3.13 SQLite compatibility issue. Fix by editing `graph.py` in the installed package:

```python
# Change this:
self._conn = sqlite3.connect(str(self.db_path), timeout=30, check_same_thread=False)

# To this:
self._conn = sqlite3.connect(str(self.db_path), timeout=30, check_same_thread=False, isolation_level=None)
```

---

## 7. Getting the Best Out of It

### Daily workflow

| When | What to do | What happens |
|------|-----------|--------------|
| **Start of day** | Nothing | Graph auto-updates on file saves if watch mode is active |
| **Before PR** | Ask AI: *"Review my changes"* | AI queries graph for blast radius, reads only affected files |
| **During development** | Ask AI: *"What would break if I change this function?"* | Graph returns all callers, dependents, and test files |
| **After refactoring** | `code-review-graph update` | Incremental re-parse in < 2 seconds |

### High-value AI prompts that leverage the graph

1. **"Review my changes"** — AI uses `detect_changes` tool to find blast radius, then reads only impacted files
2. **"What's the impact of changing X?"** — `get_impact_radius` traces all dependents
3. **"Show the execution flow from this endpoint"** — `get_flow` traces entry point → service → DB
4. **"Find untested functions"** — Graph knows which functions have test edges and which don't
5. **"Explain the architecture"** — `get_architecture_overview` generates component map with coupling warnings
6. **"Find dead code"** — Identifies functions with no incoming call edges

### Recommended commands

```bash
# Full rebuild (after major refactoring or branch switch)
code-review-graph build

# Incremental update (after small changes)
code-review-graph update

# Watch mode (continuous updates while coding)
code-review-graph watch

# Interactive visualization (open in browser)
code-review-graph visualize

# Show stats
code-review-graph status
```

### MCP tools available to your AI assistant (22 tools)

| Tool | Purpose |
|------|---------|
| `build_or_update_graph_tool` | Build/update the graph |
| `get_impact_radius_tool` | Blast-radius analysis |
| `get_review_context_tool` | Prioritized review items with risk scores |
| `detect_changes_tool` | Analyze change impact from git diff |
| `query_graph_tool` | Query nodes and edges directly |
| `list_graph_stats_tool` | Graph statistics |
| `get_flow_tool` | Trace execution flows |
| `list_flows_tool` | List all detected execution flows |
| `get_architecture_overview_tool` | Component map with coupling warnings |
| `semantic_search_nodes_tool` | Semantic search (if embeddings enabled) |
| `refactor_tool` | Rename preview, dead code detection |
| `list_communities_tool` | Code clusters/modules |
| `get_community_tool` | Details of a specific community |
| `generate_wiki_tool` | Auto-generate markdown documentation |
| `get_wiki_page_tool` | Retrieve generated wiki page |
| `get_docs_section_tool` | LLM-oriented documentation |
| `get_affected_flows_tool` | Flows impacted by changes |
| `find_large_functions_tool` | Complexity hotspots |
| `apply_refactor_tool` | Execute refactoring suggestions |
| `embed_graph_tool` | Generate semantic embeddings |
| `cross_repo_search_tool` | Search across multiple registered repos |
| `list_repos_tool` | List registered repositories |

---

## 8. Enterprise Deployment Considerations

### Git integration

Add to `.gitignore` (auto-generated by the tool):
```
.code-review-graph/
```

The graph database is **local and per-developer**. It is NOT shared or committed.

### Multi-repo support

```bash
# Register multiple repos
code-review-graph register --repo /path/to/repo1 --alias payments
code-review-graph register --repo /path/to/repo2 --alias accounts

# Search across all
code-review-graph repos
```

### CI/CD integration

The tool is designed for **developer workstations**, not CI pipelines. However, you could:
- Run `code-review-graph build` in CI to validate the graph builds cleanly
- Use `code-review-graph detect-changes` in PR checks to generate impact reports

### Team rollout checklist

- [ ] Verify Python 3.10+ is available on developer machines
- [ ] Install via `pip install code-review-graph` (or internal PyPI mirror)
- [ ] Run `code-review-graph install` in each project root
- [ ] Run `code-review-graph build`
- [ ] Restart AI coding tools
- [ ] **Do NOT set `GOOGLE_API_KEY` or `MINIMAX_API_KEY`** (keeps everything local)
- [ ] Add `.code-review-graph/` to each project's `.gitignore`
- [ ] Share this guide with team

---

## 9. Limitations & Risks

### Limitations

| Limitation | Impact | Mitigation |
|-----------|--------|------------|
| Tree-sitter parse may miss some Java generics/annotations | Some edges could be missed | Generally high accuracy; review `status` output |
| New/young project (5K stars, 18 contributors) | API could change between versions | Pin version in requirements |
| Python 3.13 SQLite bug | Build fails without patch | Apply `isolation_level=None` fix (see Section 6) |
| No VS Code marketplace extension | Can't install from Extensions panel | Install via pip + manual `.mcp.json` |
| Graph doesn't store code content | Can't search by code patterns | Use alongside IDE search; graph focuses on structure |

### Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Dependency supply chain | Low | All deps are well-known (Tree-sitter, NetworkX, FastMCP); pinned with upper bounds |
| Data stored on developer machine | Low | Graph contains structural metadata only, not source code |
| Tool goes unmaintained | Medium | MIT license allows forking; graph is deterministic and rebuildable |
| False sense of completeness | Medium | Graph covers structural dependencies; semantic dependencies (e.g., shared config, runtime reflection) are not captured |

---

## 10. Recommendation

### Verdict: **Recommended for adoption** with the following conditions:

1. **Enterprise-safe in default mode** — No external calls, no telemetry, no code exfiltration
2. **Do not enable cloud embeddings** — Keep `GOOGLE_API_KEY` and `MINIMAX_API_KEY` unset
3. **Pin the version** — Use `code-review-graph==2.1.0` in your requirements until the project stabilizes
4. **Start with one team** — Pilot on a medium-sized project (50–500 files) to measure actual token savings
5. **Works with your existing tools** — Not Claude-only; supports Cursor, Windsurf, Zed, Continue, VS Code Copilot via MCP

### Expected ROI

For a team of 20 developers using AI-assisted code reviews daily:
- **Token reduction**: 80–90% fewer tokens per review
- **Time savings**: Faster AI responses (less context to process)
- **Quality improvement**: AI focuses on affected code, not the whole repo
- **Break-even**: Immediate — the tool is free (MIT license), setup takes < 5 minutes per project
