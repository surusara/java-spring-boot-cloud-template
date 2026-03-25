# Fortify SCA Security Scan — Complete Setup Guide

> **Version:** 1.0  
> **Last Updated:** 2026-03-25  
> **Scope:** Java (Spring Boot) + React UI projects  
> **Audience:** DevOps, Platform Engineering, Development Teams

---

## Table of Contents

1. [Overview](#1-overview)
2. [Common Problems & Root Causes](#2-common-problems--root-causes)
3. [JVM Memory Tuning](#3-jvm-memory-tuning)
4. [File Exclusion Strategy](#4-file-exclusion-strategy)
   - [Java / Spring Boot Exclusions](#41-java--spring-boot-exclusions)
   - [React UI Exclusions](#42-react-ui-exclusions)
   - [What to KEEP in the Scan](#43-what-to-keep-in-the-scan)
5. [Scan Modes: Full vs Incremental](#5-scan-modes-full-vs-incremental)
6. [Fortify SCA Properties Tuning](#6-fortify-sca-properties-tuning)
7. [Pipeline Setup](#7-pipeline-setup)
   - [Azure DevOps (YAML)](#71-azure-devops-yaml)
   - [Jenkins (Declarative)](#72-jenkins-declarative)
   - [GitHub Actions](#73-github-actions)
8. [Multi-Module Split Scan (Large Repos)](#8-multi-module-split-scan-large-repos)
9. [Upload Results to Fortify SSC](#9-upload-results-to-fortify-ssc)
10. [Troubleshooting](#10-troubleshooting)
11. [Quick Reference Cheat Sheet](#11-quick-reference-cheat-sheet)

---

## 1. Overview

Fortify Static Code Analyzer (SCA) performs static application security testing (SAST) by analyzing source code for vulnerabilities. The scan process has two phases:

```
┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│   Translation    │ ───► │      Scan        │ ───► │   FPR Report     │
│ (Parse sources)  │      │ (Run analyzers)  │      │ (Upload to SSC)  │
└──────────────────┘      └──────────────────┘      └──────────────────┘
```

| Phase | Command | What it does |
|-------|---------|--------------|
| Clean | `sourceanalyzer -b <id> -clean` | Removes stale intermediate files |
| Translation | `sourceanalyzer -b <id> <sources>` | Parses source into Fortify intermediate model |
| Scan | `sourceanalyzer -b <id> -scan -f out.fpr` | Runs vulnerability analyzers, produces FPR |
| Upload | `fortifyclient uploadFPR ...` | Pushes FPR to Fortify Software Security Center |

---

## 2. Common Problems & Root Causes

| Problem | Root Cause | Solution |
|---------|------------|----------|
| **OutOfMemoryError** during translation | Too many files in scope | Exclude non-essential files, increase `-Xmx` |
| **OutOfMemoryError** during scan | Large intermediate model | Increase `-Xmx`, split into modules |
| **Scan takes hours** | Scanning tests, generated code, dependencies | Exclude + use incremental scan for PRs |
| **Stale findings** | Old build ID cached | Run `-clean` before every scan |
| **32-bit JVM cap** | JVM limited to ~1.5 GB | Switch to 64-bit JVM |
| **False positives from tests** | Test code scanned unnecessarily | Exclude `**/test/**` |

### Verify 64-bit JVM

```bash
java -version
# Must show "64-Bit Server VM"
```

---

## 3. JVM Memory Tuning

### Sizing Guide

| Project Size | File Count | Recommended `-Xmx` | Recommended `-Xss` |
|-------------|-----------|--------------------|--------------------|
| Small | < 500 files | 4g | 4m |
| Medium | 500–2,000 files | 8g | 8m |
| Large | 2,000–10,000 files | 16g | 8m |
| Very Large / Monorepo | 10,000+ files | 24g+ or split scan | 16m |

### Set via Environment Variable (Global)

```bash
# Linux / macOS
export SCA_VM_OPTS="-Xmx16g -Xss8m"

# Windows (PowerShell)
$env:SCA_VM_OPTS = "-Xmx16g -Xss8m"
```

### Set via CLI (Per Command)

```bash
sourceanalyzer -Xmx16g -Xss8m -b my-build -scan -f output.fpr
```

> **Note:** `-Xmx` applies to BOTH the translation and scan phases. Set it on every `sourceanalyzer` invocation.

---

## 4. File Exclusion Strategy

The single most effective way to reduce memory usage and scan time is to **exclude files that don't need security analysis**.

### 4.1 Java / Spring Boot Exclusions

```bash
# ── Test code ──
-exclude "**/test/**"
-exclude "**/tests/**"
-exclude "**/src/test/**"

# ── Build output ──
-exclude "**/target/**"
-exclude "**/build/**"
-exclude "**/out/**"

# ── Generated code ──
-exclude "**/generated-sources/**"
-exclude "**/generated-test-sources/**"

# ── Dependencies ──
-exclude "**/node_modules/**"
-exclude "**/vendor/**"
-exclude "**/.gradle/**"
-exclude "**/.m2/**"

# ── Documentation ──
-exclude "**/docs/**"
-exclude "**/*.md"
-exclude "**/CHANGELOG*"
-exclude "**/LICENSE*"

# ── IDE / CI / OS ──
-exclude "**/.vscode/**"
-exclude "**/.idea/**"
-exclude "**/.github/**"
-exclude "**/.DS_Store"

# ── Kubernetes / Helm / Docker (infra, not app code) ──
-exclude "**/helm/**"
-exclude "**/k8s-*"
-exclude "**/docker-compose*"
-exclude "**/Dockerfile"

# ── Reports / Logs ──
-exclude "**/surefire-reports/**"
-exclude "**/coverage/**"
-exclude "**/*.log"

# ── Migrations (review separately) ──
-exclude "**/migrations/**"
-exclude "**/db/migrate/**"
```

### 4.2 React UI Exclusions

```bash
# ── Dependencies & lockfiles ──
-exclude "**/node_modules/**"
-exclude "**/package-lock.json"
-exclude "**/yarn.lock"
-exclude "**/pnpm-lock.yaml"

# ── Build output ──
-exclude "**/build/**"
-exclude "**/dist/**"
-exclude "**/.next/**"
-exclude "**/.nuxt/**"
-exclude "**/out/**"
-exclude "**/storybook-static/**"

# ── Minified / bundled / source maps ──
-exclude "**/*.min.js"
-exclude "**/*.min.css"
-exclude "**/*.bundle.js"
-exclude "**/*.chunk.js"
-exclude "**/*.map"

# ── Test files ──
-exclude "**/__tests__/**"
-exclude "**/__mocks__/**"
-exclude "**/__snapshots__/**"
-exclude "**/*.test.js"
-exclude "**/*.test.jsx"
-exclude "**/*.test.ts"
-exclude "**/*.test.tsx"
-exclude "**/*.spec.js"
-exclude "**/*.spec.jsx"
-exclude "**/*.spec.ts"
-exclude "**/*.spec.tsx"
-exclude "**/jest.config.*"
-exclude "**/jest.setup.*"
-exclude "**/setupTests.*"
-exclude "**/cypress/**"
-exclude "**/e2e/**"
-exclude "**/playwright/**"
-exclude "**/.storybook/**"
-exclude "**/*.stories.*"

# ── Coverage ──
-exclude "**/coverage/**"
-exclude "**/.nyc_output/**"

# ── Static assets (non-code) ──
-exclude "**/*.png"
-exclude "**/*.jpg"
-exclude "**/*.jpeg"
-exclude "**/*.gif"
-exclude "**/*.svg"
-exclude "**/*.ico"
-exclude "**/*.woff"
-exclude "**/*.woff2"
-exclude "**/*.ttf"
-exclude "**/*.eot"
-exclude "**/*.mp4"
-exclude "**/*.webp"
-exclude "**/*.pdf"

# ── Config / tooling ──
-exclude "**/.eslintrc*"
-exclude "**/.prettierrc*"
-exclude "**/tsconfig*.json"
-exclude "**/babel.config.*"
-exclude "**/webpack.config.*"
-exclude "**/vite.config.*"
-exclude "**/rollup.config.*"
-exclude "**/.browserslistrc"
-exclude "**/postcss.config.*"
-exclude "**/tailwind.config.*"

# ── IDE / OS / CI ──
-exclude "**/.vscode/**"
-exclude "**/.idea/**"
-exclude "**/.DS_Store"
-exclude "**/.github/**"
-exclude "**/.husky/**"

# ── Public folder static assets ──
-exclude "**/public/mockServiceWorker.js"
-exclude "**/public/*.ico"
-exclude "**/public/*.png"

# ── Documentation ──
-exclude "**/docs/**"
-exclude "**/*.md"
```

### 4.3 What to KEEP in the Scan

| Technology | Scan These | Why |
|------------|-----------|-----|
| **Java** | `src/main/**/*.java` | Application logic, controllers, services |
| **Java** | `src/main/resources/application*.yml` | Credential leaks, misconfig |
| **React** | `src/**/*.js`, `*.jsx`, `*.ts`, `*.tsx` | Application code |
| **React** | `src/**/*.html` | XSS vectors |
| **Both** | `server/**`, `api/**` | SSR / BFF / API routes |
| **Both** | Auth/crypto utilities | Token handling, encryption |
| **Both** | Custom middleware or proxy config | SSRF, auth bypass risks |

> **Rule of thumb:** Scan YOUR code. Exclude everything else.

---

## 5. Scan Modes: Full vs Incremental

| Mode | Flag | Use When | Memory | Speed |
|------|------|----------|--------|-------|
| **Full** | *(none)* | Main branch merge, release builds | High | Slow |
| **Incremental** | `-incremental` | Pull Requests, feature branches | Low | 60-80% faster |
| **Quick** | `-quick` | Emergency / smoke check | Low | Fastest (reduced depth) |

### Strategy

```
PR opened/updated    → Incremental scan (only changed code)
Merge to main        → Full scan (complete analysis)
Release branch       → Full scan + upload to SSC
Hotfix               → Quick scan for fast feedback
```

### Filtering Changed Files for PRs (Git-Based)

```bash
# Get only changed source files between PR branch and target
CHANGED_FILES=$(git diff --name-only origin/main...HEAD \
  | grep -E '\.(java|js|jsx|ts|tsx|py)$' \
  | grep -v '/test/' \
  | grep -v '/node_modules/' \
  | grep -v '\.spec\.' \
  | grep -v '\.test\.' \
  | tr '\n' ' ')

# Feed only changed files to translation
sourceanalyzer -Xmx8g -Xss8m -b "$BUILD_ID" $CHANGED_FILES
```

---

## 6. Fortify SCA Properties Tuning

Edit `<FORTIFY_INSTALL>/Core/config/fortify-sca.properties`:

```properties
# ── Thread stack size (helps with deep call graphs) ──
com.fortify.sca.ThreadStackSize=8m

# ── Limit analysis depth (reduces memory, slight accuracy trade-off) ──
com.fortify.sca.MaxPassThroughChainDepth=8

# ── Disable DOM modeling if not scanning HTML-heavy apps ──
com.fortify.sca.EnableDOMModeling=false

# ── Parallel analysis threads (match to CPU cores) ──
com.fortify.sca.MultithreadedAnalysis=true
com.fortify.sca.AnalysisThreadCount=4

# ── Limit data flow analysis depth ──
com.fortify.sca.MaxChainDepth=6
```

> **Warning:** Reducing `MaxPassThroughChainDepth` and `MaxChainDepth` can miss some deep injection paths. Use with judgment—tuning these is acceptable for builds that OOM even with exclusions and 16g heap.

---

## 7. Pipeline Setup

### 7.1 Azure DevOps (YAML)

```yaml
trigger:
  branches:
    include:
      - main
      - develop

pr:
  branches:
    include:
      - main
      - develop

variables:
  FORTIFY_BUILD_ID: '$(Build.Repository.Name)-$(Build.SourceBranchName)'
  # Scale memory by context
  ${{ if eq(variables['Build.Reason'], 'PullRequest') }}:
    SCAN_MODE: 'incremental'
    FORTIFY_XMX: '8g'
  ${{ else }}:
    SCAN_MODE: 'full'
    FORTIFY_XMX: '16g'

stages:
  - stage: FortifyScan
    displayName: 'Fortify Security Scan'
    pool:
      name: 'FortifyAgentPool'              # Dedicated pool with 16g+ RAM
      demands:
        - fortify
    jobs:
      - job: Scan
        timeoutInMinutes: 120
        steps:

          # ────────────────────────────────────────────
          # Step 1: Clean stale build IDs
          # ────────────────────────────────────────────
          - script: |
              sourceanalyzer -b $(FORTIFY_BUILD_ID) -clean
            displayName: '1. Clean stale Fortify build'

          # ────────────────────────────────────────────
          # Step 2: Translation (filtered sources only)
          # ────────────────────────────────────────────
          - script: |
              echo "Scan mode: $(SCAN_MODE)"
              echo "Heap: $(FORTIFY_XMX)"

              sourceanalyzer -Xmx$(FORTIFY_XMX) -Xss8m \
                -b $(FORTIFY_BUILD_ID) \
                -exclude "**/test/**" \
                -exclude "**/target/**" \
                -exclude "**/build/**" \
                -exclude "**/dist/**" \
                -exclude "**/node_modules/**" \
                -exclude "**/generated-sources/**" \
                -exclude "**/generated-test-sources/**" \
                -exclude "**/*.min.js" \
                -exclude "**/*.chunk.js" \
                -exclude "**/*.map" \
                -exclude "**/*.test.*" \
                -exclude "**/*.spec.*" \
                -exclude "**/*.stories.*" \
                -exclude "**/__tests__/**" \
                -exclude "**/__mocks__/**" \
                -exclude "**/__snapshots__/**" \
                -exclude "**/coverage/**" \
                -exclude "**/cypress/**" \
                -exclude "**/storybook-static/**" \
                -exclude "**/vendor/**" \
                -exclude "**/docs/**" \
                -exclude "**/*.md" \
                -exclude "**/*.png" \
                -exclude "**/*.jpg" \
                -exclude "**/*.svg" \
                -exclude "**/*.woff*" \
                -exclude "**/package-lock.json" \
                -exclude "**/yarn.lock" \
                -exclude "**/helm/**" \
                -exclude "**/.vscode/**" \
                -exclude "**/.idea/**" \
                -exclude "**/.github/**" \
                "src/main/**/*.java" "src/**/*.js" "src/**/*.jsx" "src/**/*.ts" "src/**/*.tsx"
            displayName: '2. Fortify Translation (filtered sources)'

          # ────────────────────────────────────────────
          # Step 3a: Incremental scan for PRs
          # ────────────────────────────────────────────
          - ${{ if eq(variables['Build.Reason'], 'PullRequest') }}:
            - script: |
                sourceanalyzer -Xmx$(FORTIFY_XMX) -Xss8m \
                  -b $(FORTIFY_BUILD_ID) \
                  -scan \
                  -incremental \
                  -f $(Build.ArtifactStagingDirectory)/fortify-pr.fpr
              displayName: '3. Fortify Incremental Scan (PR)'

          # ────────────────────────────────────────────
          # Step 3b: Full scan for main/develop merges
          # ────────────────────────────────────────────
          - ${{ if ne(variables['Build.Reason'], 'PullRequest') }}:
            - script: |
                sourceanalyzer -Xmx$(FORTIFY_XMX) -Xss8m \
                  -b $(FORTIFY_BUILD_ID) \
                  -scan \
                  -f $(Build.ArtifactStagingDirectory)/fortify-full.fpr
              displayName: '3. Fortify Full Scan (main branch)'

          # ────────────────────────────────────────────
          # Step 4: Upload to Fortify SSC
          # ────────────────────────────────────────────
          - script: |
              fortifyclient \
                -url $(FORTIFY_SSC_URL) \
                -authtoken $(FORTIFY_SSC_TOKEN) \
                uploadFPR \
                -project "$(Build.Repository.Name)" \
                -version "$(Build.SourceBranchName)" \
                -file $(Build.ArtifactStagingDirectory)/fortify-*.fpr
            displayName: '4. Upload FPR to Fortify SSC'

          # ────────────────────────────────────────────
          # Step 5: Publish artifact
          # ────────────────────────────────────────────
          - task: PublishBuildArtifacts@1
            inputs:
              pathToPublish: '$(Build.ArtifactStagingDirectory)'
              artifactName: 'fortify-results'
            displayName: '5. Publish FPR artifact'
```

---

### 7.2 Jenkins (Declarative)

```groovy
pipeline {
    agent { label 'fortify' }                // Dedicated agent with 16g+ RAM

    environment {
        FORTIFY_BUILD_ID = "${env.JOB_NAME}-${env.BRANCH_NAME}"
        IS_PR            = "${env.CHANGE_ID ? 'true' : 'false'}"
        FORTIFY_XMX      = "${env.CHANGE_ID ? '8g' : '16g'}"
    }

    stages {

        // ── Step 1: Clean ──
        stage('Clean Stale Build') {
            steps {
                sh "sourceanalyzer -b ${FORTIFY_BUILD_ID} -clean"
            }
        }

        // ── Step 2: Translation ──
        stage('Fortify Translation') {
            steps {
                sh """
                    sourceanalyzer -Xmx${FORTIFY_XMX} -Xss8m \\
                      -b ${FORTIFY_BUILD_ID} \\
                      -exclude '**/test/**' \\
                      -exclude '**/target/**' \\
                      -exclude '**/build/**' \\
                      -exclude '**/dist/**' \\
                      -exclude '**/node_modules/**' \\
                      -exclude '**/generated-sources/**' \\
                      -exclude '**/*.min.js' \\
                      -exclude '**/*.chunk.js' \\
                      -exclude '**/*.map' \\
                      -exclude '**/*.test.*' \\
                      -exclude '**/*.spec.*' \\
                      -exclude '**/__tests__/**' \\
                      -exclude '**/__mocks__/**' \\
                      -exclude '**/coverage/**' \\
                      -exclude '**/cypress/**' \\
                      -exclude '**/vendor/**' \\
                      -exclude '**/docs/**' \\
                      -exclude '**/*.md' \\
                      -exclude '**/*.png' \\
                      -exclude '**/*.jpg' \\
                      -exclude '**/*.svg' \\
                      -exclude '**/*.woff*' \\
                      -exclude '**/package-lock.json' \\
                      -exclude '**/helm/**' \\
                      -exclude '**/.vscode/**' \\
                      -exclude '**/.github/**' \\
                      'src/main/**/*.java' 'src/**/*.js' 'src/**/*.jsx' 'src/**/*.ts' 'src/**/*.tsx'
                """
            }
        }

        // ── Step 3a: Incremental Scan (PRs) ──
        stage('Fortify Scan - PR (Incremental)') {
            when { changeRequest() }
            steps {
                sh """
                    sourceanalyzer -Xmx${FORTIFY_XMX} -Xss8m \\
                      -b ${FORTIFY_BUILD_ID} \\
                      -scan \\
                      -incremental \\
                      -f fortify-pr.fpr
                """
            }
        }

        // ── Step 3b: Full Scan (Main) ──
        stage('Fortify Scan - Main (Full)') {
            when {
                not { changeRequest() }
                branch 'main'
            }
            steps {
                sh """
                    sourceanalyzer -Xmx${FORTIFY_XMX} -Xss8m \\
                      -b ${FORTIFY_BUILD_ID} \\
                      -scan \\
                      -f fortify-full.fpr
                """
            }
        }

        // ── Step 4: Upload to SSC ──
        stage('Upload to SSC') {
            steps {
                sh """
                    fortifyclient -url \${FORTIFY_SSC_URL} \\
                      -authtoken \${FORTIFY_SSC_TOKEN} \\
                      uploadFPR \\
                      -project '${JOB_NAME}' \\
                      -version '${BRANCH_NAME}' \\
                      -file fortify-*.fpr
                """
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.fpr', allowEmptyArchive: true
        }
        failure {
            echo "Fortify scan failed — check memory settings or file exclusions."
        }
    }
}
```

---

### 7.3 GitHub Actions

```yaml
name: Fortify Security Scan

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  fortify:
    runs-on: self-hosted                     # Must have Fortify installed + 16g RAM
    timeout-minutes: 120
    env:
      BUILD_ID: ${{ github.repository }}-${{ github.ref_name }}

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0                     # Full history for git diff

      # ── Step 1: Clean ──
      - name: Clean stale Fortify build
        run: sourceanalyzer -b "$BUILD_ID" -clean

      # ── Step 2a: Get changed files (PR only) ──
      - name: Get changed source files (PRs)
        if: github.event_name == 'pull_request'
        id: changed
        run: |
          FILES=$(git diff --name-only origin/${{ github.base_ref }}...HEAD \
            | grep -E '\.(java|js|jsx|ts|tsx)$' \
            | grep -v '/test/' \
            | grep -v '/target/' \
            | grep -v '/node_modules/' \
            | grep -v '\.spec\.' \
            | grep -v '\.test\.' \
            | tr '\n' ' ')
          echo "files=$FILES" >> "$GITHUB_OUTPUT"
          echo "Changed files: $FILES"

      # ── Step 2b: Translation — PR (changed files only) ──
      - name: Fortify Translation (PR — changed files)
        if: github.event_name == 'pull_request'
        run: |
          sourceanalyzer -Xmx8g -Xss8m \
            -b "$BUILD_ID" \
            ${{ steps.changed.outputs.files }}

      # ── Step 2c: Translation — Main (full, with exclusions) ──
      - name: Fortify Translation (Full — main branch)
        if: github.event_name == 'push'
        run: |
          sourceanalyzer -Xmx16g -Xss8m \
            -b "$BUILD_ID" \
            -exclude "**/test/**" \
            -exclude "**/target/**" \
            -exclude "**/build/**" \
            -exclude "**/dist/**" \
            -exclude "**/node_modules/**" \
            -exclude "**/generated-sources/**" \
            -exclude "**/*.min.js" \
            -exclude "**/*.chunk.js" \
            -exclude "**/*.map" \
            -exclude "**/*.test.*" \
            -exclude "**/*.spec.*" \
            -exclude "**/__tests__/**" \
            -exclude "**/coverage/**" \
            -exclude "**/cypress/**" \
            -exclude "**/vendor/**" \
            -exclude "**/docs/**" \
            -exclude "**/*.md" \
            -exclude "**/*.png" \
            -exclude "**/*.jpg" \
            -exclude "**/*.svg" \
            -exclude "**/*.woff*" \
            -exclude "**/package-lock.json" \
            -exclude "**/helm/**" \
            "src/main/**/*.java" "src/**/*.js" "src/**/*.jsx" "src/**/*.ts" "src/**/*.tsx"

      # ── Step 3a: Incremental Scan (PR) ──
      - name: Fortify Incremental Scan (PR)
        if: github.event_name == 'pull_request'
        run: |
          sourceanalyzer -Xmx8g -Xss8m \
            -b "$BUILD_ID" -scan -incremental \
            -f fortify-pr.fpr

      # ── Step 3b: Full Scan (Main) ──
      - name: Fortify Full Scan (main)
        if: github.event_name == 'push'
        run: |
          sourceanalyzer -Xmx16g -Xss8m \
            -b "$BUILD_ID" -scan \
            -f fortify-full.fpr

      # ── Step 4: Upload artifact ──
      - name: Upload FPR artifact
        uses: actions/upload-artifact@v4
        with:
          name: fortify-results
          path: "*.fpr"
          retention-days: 30
```

---

## 8. Multi-Module Split Scan (Large Repos)

When a single scan still OOMs even with 16g+ and exclusions, split into modules and merge.

### Split Scan Script

```bash
#!/bin/bash
# fortify-split-scan.sh
# Usage: ./fortify-split-scan.sh [heap_size]
# Example: ./fortify-split-scan.sh 16g

set -euo pipefail

XMX="${1:-16g}"
EXCLUDES=(
  -exclude "**/test/**"
  -exclude "**/target/**"
  -exclude "**/generated-sources/**"
  -exclude "**/node_modules/**"
  -exclude "**/*.min.js"
  -exclude "**/*.map"
  -exclude "**/coverage/**"
  -exclude "**/docs/**"
)

# Define your modules
MODULES=(
  "module-a/src/main"
  "module-b/src/main"
  "module-c/src/main"
  "frontend/src"
)

FPR_FILES=()

for MODULE in "${MODULES[@]}"; do
  MODULE_ID=$(echo "$MODULE" | tr '/' '-')
  echo ""
  echo "═══════════════════════════════════════"
  echo "  Scanning: $MODULE  (build: $MODULE_ID)"
  echo "═══════════════════════════════════════"

  # Clean
  sourceanalyzer -b "$MODULE_ID" -clean

  # Translate
  sourceanalyzer -Xmx"$XMX" -Xss8m \
    -b "$MODULE_ID" \
    "${EXCLUDES[@]}" \
    "${MODULE}/**/*.java" "${MODULE}/**/*.js" "${MODULE}/**/*.ts" "${MODULE}/**/*.tsx"

  # Scan
  sourceanalyzer -Xmx"$XMX" -Xss8m \
    -b "$MODULE_ID" -scan \
    -f "${MODULE_ID}.fpr"

  FPR_FILES+=("${MODULE_ID}.fpr")
  echo "  ✔ Produced ${MODULE_ID}.fpr"
done

# Merge all FPRs
echo ""
echo "═══════════════════════════════════════"
echo "  Merging ${#FPR_FILES[@]} FPR files"
echo "═══════════════════════════════════════"

MERGED="combined.fpr"
cp "${FPR_FILES[0]}" "$MERGED"

for ((i=1; i<${#FPR_FILES[@]}; i++)); do
  FPRUtility -merge -project "$MERGED" -source "${FPR_FILES[$i]}" -f "$MERGED"
done

echo ""
echo "✔ Final merged report: $MERGED"
echo "  Total modules scanned: ${#FPR_FILES[@]}"
```

### Call from Pipeline

```yaml
# Azure DevOps
- script: |
    chmod +x ./scripts/fortify-split-scan.sh
    ./scripts/fortify-split-scan.sh 16g
  displayName: 'Fortify Split Scan (Multi-Module)'
```

```groovy
// Jenkins
stage('Fortify Split Scan') {
    steps {
        sh './scripts/fortify-split-scan.sh 16g'
    }
}
```

---

## 9. Upload Results to Fortify SSC

### CLI Upload

```bash
fortifyclient \
  -url https://fortify-ssc.company.com/ssc \
  -authtoken $FORTIFY_SSC_TOKEN \
  uploadFPR \
  -project "my-app" \
  -version "main" \
  -file combined.fpr
```

### Generate Auth Token

```bash
fortifyclient \
  -url https://fortify-ssc.company.com/ssc \
  -user admin \
  -password $SSC_PASSWORD \
  token -gettoken AnalysisUploadToken
```

> Store the token as a pipeline secret (`FORTIFY_SSC_TOKEN`). Never hardcode it.

---

## 10. Troubleshooting

### OOM Still Happening After All Optimizations

```
Checklist:
□ Verified 64-bit JVM?                    → java -version
□ -Xmx set to at least 16g?              → on BOTH translation and scan
□ -clean run before scan?                 → sourceanalyzer -b <id> -clean
□ Excluded test/generated/deps/assets?    → Section 4 exclusions applied
□ Using -incremental for PRs?             → -incremental flag on scan step
□ Checked file count after exclusions?    → sourceanalyzer -b <id> -show-files
□ Tried -quick mode?                      → -quick flag for reduced depth
□ Split into modules?                     → Section 8 split scan
□ Tuned SCA properties?                   → Section 6 property changes
□ Pipeline agent has enough RAM?          → Minimum 16g physical RAM
```

### Useful Diagnostic Commands

```bash
# Show files in build scope (verify exclusions work)
sourceanalyzer -b my-build -show-files

# Show file count
sourceanalyzer -b my-build -show-files | wc -l

# Show build warnings
sourceanalyzer -b my-build -show-build-warnings

# Verbose scan for debugging
sourceanalyzer -Xmx16g -b my-build -scan -verbose -f output.fpr
```

### Common Error Messages

| Error | Cause | Fix |
|-------|-------|-----|
| `java.lang.OutOfMemoryError: Java heap space` | Heap too small or too many files | Increase `-Xmx`, add exclusions |
| `java.lang.OutOfMemoryError: GC overhead limit` | JVM spending >98% time in GC | Increase `-Xmx` significantly |
| `java.lang.StackOverflowError` | Deep call chains | Increase `-Xss` to 8m or 16m |
| `Build session not found` | Stale or missing build ID | Run `-clean` then re-translate |
| `No files matched the pattern` | Wrong glob or path | Verify paths with `-show-files` |

---

## 11. Quick Reference Cheat Sheet

### Commands

```bash
# Clean
sourceanalyzer -b BUILD_ID -clean

# Translate (with exclusions)
sourceanalyzer -Xmx16g -Xss8m -b BUILD_ID \
  -exclude "**/test/**" -exclude "**/target/**" \
  -exclude "**/node_modules/**" \
  "src/main/**/*.java"

# Full scan
sourceanalyzer -Xmx16g -Xss8m -b BUILD_ID -scan -f output.fpr

# Incremental scan (PRs)
sourceanalyzer -Xmx8g -Xss8m -b BUILD_ID -scan -incremental -f output.fpr

# Quick scan (emergency)
sourceanalyzer -Xmx8g -b BUILD_ID -scan -quick -f output.fpr

# Show files in scope
sourceanalyzer -b BUILD_ID -show-files

# Upload to SSC
fortifyclient -url $SSC_URL -authtoken $TOKEN uploadFPR \
  -project "app" -version "main" -file output.fpr

# Merge FPRs
FPRUtility -merge -project base.fpr -source extra.fpr -f merged.fpr
```

### Decision Matrix

```
Is it a PR?
  ├─ YES → Use -incremental, -Xmx8g, filter changed files via git diff
  └─ NO (main branch merge)
       ├─ Repo < 2000 files → Full scan, -Xmx8g
       ├─ Repo 2000-10000 files → Full scan, -Xmx16g, apply exclusions
       └─ Repo > 10000 files → Split into modules, -Xmx16g per module, merge FPRs
```

### Priority Actions Summary

| Priority | Action | Impact |
|----------|--------|--------|
| **1 (Critical)** | Bump `-Xmx` to at least 8g (16g for large repos) | Directly prevents OOM |
| **2 (Critical)** | Exclude test/generated/third-party/asset files | 70-90% file count reduction |
| **3 (High)** | Run `-clean` before every scan | Prevents stale data buildup |
| **4 (High)** | Use `-incremental` for PRs | 60-80% faster, much less memory |
| **5 (High)** | Filter file list via `git diff` for PRs | Minimal translation scope |
| **6 (Medium)** | Full scan only on main branch | Reduces overall CI load |
| **7 (Medium)** | Dedicated pipeline agent with 16g+ RAM | Ensures resources available |
| **8 (Low)** | Split into modules + merge FPRs | Last resort for very large repos |
| **9 (Low)** | Tune SCA properties (chain depth, threads) | Fine-tuning after other fixes |

---

> **Maintained by:** Platform Engineering  
> **Review cycle:** Quarterly or after Fortify version upgrades
