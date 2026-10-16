# Mobile Harness Extended — Agentic Mobile IDE Architecture

## Current architecture audit

Mobile Harness Extended is an Android application with applicationId `com.codeair.mhe`.

- Kotlin + Jetpack Compose UI.
- AndroidX lifecycle/ViewModel state ownership.
- MainActivity owns the Compose entry point and theme.
- MainViewModel currently coordinates application state, projects, chats, runtime sessions, GitHub, skills, terminal, changes and recovery.
- Runtime work is kept outside the Compose process through Android foreground services.
- RuntimeExecutionService owns the long-running task notification, stop action and bounded wake lock.
- RuntimeSetupService owns Linux/runtime installation and setup progress.
- Android notification permission is already declared.
- The app uses an ARM64-only runtime because the embedded Linux execution environment is ARM64.

### Local Linux execution boundary

Compose UI -> MainViewModel -> RuntimeBridge -> RuntimeInstaller/PRoot -> Linux agent process -> workspace

The Android process owns orchestration and UX state. The Linux environment is the execution substrate for coding agents.

### Agent abstraction

RuntimeBridge is the provider-neutral execution contract. Antigravity has explicit session recovery support because its provider conversation ID can be preserved.

Provider credentials/session state remain owned by the respective runtime/provider; Mobile Harness Extended should not scrape or duplicate provider credentials.

### Workspace/change review

WorkspaceCheckpoints snapshots the workspace before an agent task, computes file-level diffs afterward, and supports Undo/Keep at task/file level. Internal runtime directories are excluded from project diffs.

### Persistence

Project/chat/setup state is stored in Android app-private files/preferences/JSON. Runtime assets and workspaces are also app-private. IDE state must remain Android-owned and survive Linux runtime process restarts.

## Architectural rule

Do not turn MainViewModel into a larger god-object. Introduce explicit domain services:

- ProjectIndex — file/symbol/search index.
- ContextEngine — ranks project context for an agent request.
- AgentSessionManager — session lifecycle/checkpoints/resume.
- ToolPermissionManager — tool approval/policy.
- CommandRegistry — command palette.
- NotificationCoordinator — in-app + Android system notification abstraction.
- ProjectMemoryStore — durable project instructions/decisions.
- CheckpointStore — multi-checkpoint history.
- GitWorkspaceService — git status/branch/diff/commit/push/PR operations.
- SkillManager — portable Agent Skills registry.
- McpToolRegistry — future MCP discovery/permission/runtime integration.
- TestRunner — build/test/lint execution through the Linux runtime.
- AgentOrchestrator — future plan/implement/test/review loop.

UI composables should consume state exposed by these services rather than implement business logic themselves.

## Six-phase implementation target

### Phase 1 — IDE foundation
- production code editor
- syntax highlighting
- line numbers
- search/replace
- symbol outline
- file explorer
- @mentions
- command palette
- Git-aware workspace state

### Phase 2 — Agent intelligence
- repository/file index
- symbol index
- import/dependency relationships
- relevance-ranked context
- project memory
- AGENTS.md/rules loading
- Plan mode
- durable checkpoints

### Phase 3 — Agentic development
- build/test execution
- structured test results
- failure diagnosis
- bounded self-repair loop
- debugging/deep links from stack traces
- detailed agent activity timeline

### Phase 4 — AI ecosystem
- portable Skills
- MCP tool registry
- provider/model routing
- multi-agent orchestration
- tool permission policies
- secure approval UX

### Phase 5 — Mobile-first capabilities
- background agent jobs
- resumable sessions
- intelligent Android notifications
- voice commands
- offline-first project/editor state
- mobile command center

### Phase 6 — Advanced IDE
- browser/research tool
- visual preview inspection
- screenshot-aware UI iteration
- AI code review
- dependency intelligence
- AI commit/PR generation
- extension architecture

## Notification architecture

Use two separate notification planes.

### Local Android notifications — default

Agent event -> NotificationCoordinator -> Android NotificationManager

Use this for task started, progress, approval required, task completed, task failed, tests, network recovery exhaustion, and runtime setup results.

The existing foreground-service notification remains the required Android runtime notification and should be upgraded rather than replaced.

### Optional FCM remote notifications

FCM is only needed when a future backend needs to notify a device when the app/runtime is not the source of the event. FCM must not replace local runtime notifications.

No provider OAuth/session token should be reused as Firebase credentials.

## Firebase security rule

`google-services.json` is Android Firebase configuration, not a server credential. Never put a Firebase service-account private-key JSON in the APK, Git repository, or Linux runtime bundle.

If server-side FCM sending is added later, credentials belong on a trusted server or Cloud Function using ADC/service-account credentials.

## Android/Linux boundary

The Linux environment emits structured events: tool started/completed, file changed, command started/completed, build/test result, agent message, session state, and failure/recovery state.

Android translates those events into UI state, checkpoints/diffs, notifications, persistence, permissions, and navigation/deep links.

This prevents Linux process restarts from destroying Android UX state.

## UX quality requirements

Every new component must use the existing Material 3 theme and spacing system, respect edge-to-edge and IME insets, avoid unnecessary overlays, avoid floating controls covering content, keep touch targets accessible, avoid duplicate toolbars, use semantic icons/content descriptions, make destructive actions explicit, and preserve state across recreation where appropriate.

No feature is complete if it only works as a demo or mock.

## Completion criteria

A phase is complete only when production implementation exists, no placeholder/stub/demo implementation remains, persistence/recovery behavior is defined, Android/Linux boundaries are respected, UI is integrated into existing navigation/theme, tests cover core behavior, build/lint/test are executed where the environment permits, and changed files/known limitations are reported.

## Firebase / FCM remote notification boundary

The notification system is local-first. Android/Linux runtime work continues to publish local foreground-service and task-result notifications through `NotificationCoordinator`; Firebase is not placed on that execution-critical path.

For the `online` flavor, Firebase Cloud Messaging is an additional remote-event transport. The online-only `MobileHarnessFirebaseMessagingService` accepts data-oriented messages, normalizes their title/body, and delegates presentation to the same `NotificationCoordinator`. The service also persists the current FCM registration token in app-private storage for a future authenticated backend registration flow.

The `offline` flavor does not include the Firebase Messaging runtime or messaging service. This preserves the offline runtime's dependency boundary while allowing the shared Android notification policy to remain unchanged.

Background FCM notification payloads may be displayed directly by the FCM SDK rather than invoking `onMessageReceived`. Remote backends that need Mobile Harness Extended to control notification presentation should therefore use data messages and the documented route/title/body contract.

The supplied Firebase configuration contains a matching `com.codeair.mhe` Android client; the project configuration is scoped to that client rather than carrying unrelated sibling-app clients.
