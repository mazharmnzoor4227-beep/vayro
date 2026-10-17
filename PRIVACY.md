# Kodex Privacy Policy

**Effective date:** October 3, 2026

Kodex is a local-first Android development workspace for building and managing software projects from an Android device. This policy explains what information the app handles, where it is stored, and when information leaves the device.

## Information handled by the app

Kodex may handle information you choose to provide or generate while using the app, including:

- AI-provider API keys, authentication details, model selections, and connection settings
- Prompts, AI responses, conversation history, and agent task context
- Project files, source code, imported ZIP files, Git/GitHub repository data, documents, images, audio, and other attachments you explicitly provide
- Terminal commands, command output, working-directory information, file changes, diffs, checkpoints, and diagnostic information
- Build and runtime information needed to operate the local development environment, such as Android version, processor architecture, memory, storage, and installed runtime/agent versions
- Information required to download, install, update, and verify runtime components and app updates

## Local storage

Projects, conversations, attachments, imported repositories, editor content, terminal history, checkpoints, runtime files, and diagnostics are stored in the app's private storage on your device unless you explicitly use a feature that sends information elsewhere.

Provider credentials are protected using Android Keystore-backed encryption before they are persisted.

Kodex does not include advertising SDKs and does not operate a first-party user account system. It does include Firebase Crashlytics for crash reporting, described below.

## Code, projects, and AI requests

The app provides features including local project management, Git/GitHub import, ZIP import, an integrated terminal, file editing, search and replace, code navigation, AI coding agents, diffs/checkpoints, web preview, and Android build/install workflows.

When you use an AI feature, content required to perform the requested task may be transmitted to the AI provider you configured. Depending on the task, this can include prompts, conversation context, relevant source code, file paths, file contents, tool results, and attachments.

Kodex does not send project contents to its developer as part of ordinary local project use. Information can leave the device when you explicitly use an external service such as an AI provider, Git/GitHub operation, repository import, runtime/package download, update check, web resource, or another network-backed feature.

## Network services and downloads

The app may connect to external services to:

- Communicate with the AI provider selected by you
- Access Git/GitHub repositories and repository metadata when you request repository operations
- Download Linux/runtime components, development toolchains, package dependencies, and agent assets required by the selected configuration
- Check for Kodex app updates and retrieve release metadata
- Load web previews or other network resources when you explicitly use those features

Those services may receive standard network information such as your IP address, request time, requested resource, and user-agent information. The third-party service's own privacy policy and terms govern information it receives.

Kodex does not sell personal information.

## Crash reporting

The app uses Firebase Crashlytics to collect crash and diagnostic reports so stability issues can be diagnosed and fixed.

- **What is collected:** When the app crashes or records a non-fatal error, the report includes the exception type, the exception message, and the stack trace, plus app and device metadata needed to reproduce the issue (app version and variant, Android version and API level, device manufacturer and model, processor architectures, and memory statistics).
- **Secret redaction:** Before a report leaves the device, the app redacts API keys, bearer tokens, passwords, and private key material from exception messages and stack traces. Redaction is pattern-based and best-effort; avoid pasting secrets into fields or inputs that could surface in an error message.
- **Purpose:** Crash reports are used only to diagnose and fix app stability and reliability issues.
- **Third-party processing:** Reports are uploaded to Firebase Crashlytics, a Google service. Google receives the report content along with standard network information (such as IP address and request time). Google's privacy policy and terms govern its handling of this information.
- **Retention:** Crash report retention follows Firebase Crashlytics' data retention policies. Information already uploaded cannot be deleted from the device; contact Google for requests concerning data held by Firebase.
- **Local crash log:** Separately, the app keeps a `crash.log` file in its private on-device storage (with a user-visible mirror in the app-specific external files directory) so recent startup and crash history can be inspected without any upload. This log stays on the device.
- **User control:** Crash reporting is enabled by default. It can be disabled programmatically through the crash logger's reporting flag (`AppCrashLogger.setCrashReportingEnabled(false)`), which also stops the Firebase Crashlytics SDK from collecting. Disabling reporting does not delete reports already uploaded and does not affect the on-device `crash.log`.

## Permissions

The app may request permissions or Android capabilities needed for the features you choose:

- **Internet and network state:** Communicate with selected AI providers, Git/GitHub, runtime/package sources, update services, and web resources.
- **Notifications:** Report user-started setup, builds, agent tasks, downloads, and other foreground/background work.
- **Foreground service and wake lock:** Keep a user-started task active long enough to complete and prevent interruption while work is actively running.
- **Document picker access:** Import only files and folders you explicitly select through Android's system picker.
- **Camera/microphone or media access:** Only when a feature explicitly requires user-selected media or device input and Android grants the corresponding permission.
- **Install packages:** Submit an Android APK built from your selected project to Android's system installer. Android requires user confirmation; Kodex cannot silently install an app.

Notification permission and battery-optimization exemption may be requested in context and can be declined. Some background features may be less reliable without them.

## Retention and deletion

Local projects, conversations, terminal history, editor data, attachments, checkpoints, runtime files, and diagnostics remain on the device until you delete the relevant data, clear it from the app, or uninstall Kodex. Uninstalling the app removes its private local data according to Android behavior.

Information already transmitted to an AI provider, Git/GitHub, package/runtime host, or another third-party service is governed by that service's retention and deletion policies. Contact the relevant provider directly for requests concerning information held by that provider.

## Security

Kodex uses app-private Android storage, Android Keystore-backed credential encryption, HTTPS where supported by external services, and checksum verification for supported runtime downloads. Local Linux commands and project code execute with the capabilities granted to the app/runtime environment. No system can guarantee complete security; only run code and install dependencies you trust.

## Children

Kodex is a developer tool and is not directed to children under 13. The app does not knowingly collect personal information from children.

## Changes to this policy

This policy may be updated as Kodex changes. Material changes will be reflected by updating the effective date and publishing the revised policy at this public repository location.

## Contact

For privacy questions or requests, open an issue in the Kodex repository:
https://github.com/mazharmnzoor4227-beep/vayro/issues
