# Mobile Harness Extended — Environment Update Manager Roadmap

## Goal
Make the Linux development environment independently updateable from inside the app, without requiring an APK rebuild for normal tool/runtime version changes.

APK updates remain required for Kotlin/Java UI, native/JNI/PRoot bootstrap, security-critical app code, and other components that cannot safely self-replace.

## Architecture

### Runtime generations
- Runtime V1: existing Ubuntu 20.04.5 ARM64 environment; preserve for legacy projects.
- Runtime V2: modern ARM64 Linux base; introduced side-by-side and activated only after health checks.
- Never overwrite a known-good runtime in place.

### Managed components
- PHP
- Python
- Node.js / npm
- Composer
- Git
- Android SDK / Build Tools / Gradle
- C/C++ / CMake / GDB
- Claude Code
- DeepSeek Harness
- Antigravity CLI
- future user-space developer tools

### Update transaction
1. Fetch signed environment manifest.
2. Check app version, runtime generation, ABI, channel and compatibility.
3. Check free storage and require headroom for download + staging + rollback archive.
4. Download with retry/resume into staging.
5. Verify HTTPS response, exact SHA-256 and package metadata.
6. Verify Ed25519 authorization for Stable releases.
7. Stop/lock conflicting runtime processes.
8. Create a compressed rollback archive of every file that will be replaced/removed.
9. Install the new component into an isolated staging/version directory.
10. Run component health checks.
11. Atomically switch the active selector.
12. Run post-activation health checks.
13. Mark the update successful.
14. Keep one compressed rollback archive until retention expires.
15. On failure, restore the previous version and remove the failed version.

### Storage policy
- Keep at most one rollback archive per component by default.
- Rollback archive is compressed ZIP, not an uncompressed duplicate.
- Failed downloads/staging directories are deleted automatically.
- Successful updates can garbage-collect the previous rollback archive after retention.
- Never delete the active environment.
- Never delete a project/workspace to reclaim runtime storage.
- Show estimated temporary and final storage impact before update.

### Rollback
Every managed component exposes current version, previous version, backup size, backup availability and Restore.

Restore flow:
verify backup -> stage extraction -> health check -> atomic activation -> delete failed version -> keep restored version active.

Automatic rollback is triggered when package verification, extraction, health check, activation, or first-launch runtime probe fails.

### Health checks
PHP: version, modules, CLI execution, required extensions, Composer compatibility.
Python: version, ssl, json, pip/venv smoke test.
Node: version, npm version, module resolution.
Android: JDK, Gradle, AAPT2, SDK and minimal compile probe.
Agents: version and non-interactive startup.
Git: version and repository smoke test.

### Manifest
Stable manifest fields:
schemaVersion, channel, minimumAppVersion, runtimeGeneration, ABI, generatedAt, signing key id, signature, components, version, package URL, compressed size, SHA-256, package format, minimum runtime, dependencies, replaced/removed paths, health checks, rollback compatibility, release notes and security status.

### Channels
- Stable — default; signed packages only.
- Beta — signed packages, explicit opt-in.
- Nightly — development/testing only.

### Project compatibility
Inspect composer.json/composer.lock, Python requirements/pyproject, package.json and Gradle metadata before major changes. Projects can select a compatible runtime instead of forcing a global major upgrade.

## Implementation phases

### Phase 0 — Foundation
- Existing runtime bundle checks and SHA-256 verification
- Existing agent update flow
- Environment manifest schema
- Component identity/version model
- Transactional update engine
- Compressed rollback store
- Storage preflight
- Recovery journal

### Phase 1 — Update Center
- Settings -> Developer Environment -> Updates
- Current/latest version cards
- Update
- View changes
- Rollback
- Progress/log view
- Channel selector
- Storage impact

### Phase 2 — First managed component
- PHP versioned package format
- PHP 8.5 ARM64 production bundle
- PHP extension compatibility
- Composer compatibility
- PHP 8.5 smoke tests
- PHP 7.4 rollback test
- Release automation

### Phase 3 — Language/tool updates
Node 24.x, Python 3.14.x, Composer 2.x, Git.

### Phase 4 — Heavy toolchains
Android SDK, Build Tools, Gradle, CMake/NDK and offline Maven cache.

### Phase 5 — AI agents
Claude Code, DSH and Antigravity managed packages with rollback.

### Phase 6 — Runtime V2
Ubuntu 24.04/26.04 ARM64, side-by-side V1/V2, migration tests and project-specific runtime selection.

### Phase 7 — Production hardening
Signed Stable manifests, crash-loop rollback, interrupted-update recovery, low-storage protection, battery policy for large updates, garbage collection, offline signed update import and complete CI/emulator smoke suite.

## Non-negotiable safety rules
1. No direct overwrite of the active runtime.
2. No activation before verification and health checks.
3. No unsigned Stable update.
4. No update when storage headroom is insufficient.
5. Never delete the only known-good version.
6. Never force a major runtime migration for an incompatible project.
7. Native APK/PRoot changes remain APK-controlled.
