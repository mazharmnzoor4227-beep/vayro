# Mobile Harness Extended: In-App Updates & Private Release Architecture

This guide explains how **Mobile Harness Extended** delivers in-app updates for both **Online** and **Offline** editions, how to publish future builds from a **private repository**, and how to deploy a zero-token Cloudflare Worker release proxy.

---

## 1. Dual-Flavor Update Architecture

Mobile Harness Extended is distributed in two distinct flavors (`runtimeDelivery` dimension):

| Flavor | Artifact Name | Approximate Size | Characteristics |
| :--- | :--- | :--- | :--- |
| **Online Edition** | `app-online-release.apk` | ~85 MB | Lightweight base app. Downloads runtime components dynamically from verified mirrors. |
| **Offline Edition** | `app-offline-release.apk` | ~880 MB | Fully bundled standalone app. Includes all core Linux runtimes, Python, Node, Android SDK, and agent tools. Zero external downloads needed. |

### How Flavor Isolation Works:
1. Every APK compiles with its own `BuildConfig.APP_VARIANT` (`"online"` or `"offline"`).
2. The release manifest `mobile-harness-update.json` indexes both editions under `artifacts`:
   ```json
   {
     "versionCode": 28,
     "versionName": "2.2.0",
     "notes": "Feature notes...",
     "artifacts": {
       "online": {
         "url": "https://.../app-online-release.apk",
         "sha256": "4b68019e1fa3b07ae7e189873d6ebefae3e0ec05f013d2f928e469e38d77bfbb",
         "sizeBytes": 87421923
       },
       "offline": {
         "url": "https://.../app-offline-release.apk",
         "sha256": "8064a7c06ebfa507d8cf1f486d3029272898c69ee33ea8536f6d50ff0630b42b",
         "sizeBytes": 887721644
       }
     }
   }
   ```
3. `AppUpdater.check()` inspects `BuildConfig.APP_VARIANT`:
   - An **Online** user is strictly served `artifacts.online`.
   - An **Offline** user is strictly served `artifacts.offline`.
   - Neither flavor will ever mistakenly receive or install the other edition.

---

## 2. Managing Updates on a Private GitHub Repository

When your source repository (`sunmughan/Conzyx`) is **Private**, GitHub returns **HTTP 404** to unauthenticated download requests. Three production-grade strategies are supported:

### Strategy A: Direct GitHub Releases API with Personal Access Token (Internal Team / Testers)
- In the app, navigate to **Settings → App updates & release channel**.
- Enter:
  - **Manifest / API URL**: `https://api.github.com/repos/sunmughan/Conzyx/releases/latest`
  - **Private Repository Token**: A fine-grained GitHub Personal Access Token (PAT) with `Contents: Read-only` permission on `Conzyx`.
- `AppUpdater` connects via `Authorization: Bearer <token>`, fetches release assets, follows the AWS S3 redirect safely without leaking credentials, and downloads the APK.

---

### Strategy B: Free Cloudflare Worker Proxy (Zero-Token in App, Recommended for End-Users)
To distribute updates to users without requiring them to enter a GitHub token or exposing your private source code, use a 100% free Cloudflare Worker as a secure gateway.

#### 1. Deploy the Cloudflare Worker Script:
```javascript
// Cloudflare Worker: updates.yourdomain.workers.dev
const GITHUB_REPO = "sunmughan/Conzyx";
// Set GITHUB_TOKEN in Cloudflare Worker Environment Variables (Secrets)
// Token needs: Contents: Read-only on sunmughan/Conzyx

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // 1. Manifest Endpoint (/check or /mobile-harness-update.json)
    if (url.pathname === "/check" || url.pathname.endsWith("mobile-harness-update.json")) {
      const releaseRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
        headers: {
          "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
          "User-Agent": "Mobile-Harness-Updater",
          "Accept": "application/vnd.github+json"
        }
      });
      if (!releaseRes.ok) return new Response("Release not found", { status: 404 });
      const release = await releaseRes.json();
      const manifestAsset = release.assets.find(a => a.name === "mobile-harness-update.json");
      if (!manifestAsset) return new Response("Manifest asset missing", { status: 404 });

      // Fetch manifest binary stream
      const manifestRes = await fetch(manifestAsset.url, {
        headers: {
          "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
          "User-Agent": "Mobile-Harness-Updater",
          "Accept": "application/octet-stream"
        }
      });
      return new Response(manifestRes.body, {
        headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=300" }
      });
    }

    // 2. Direct APK Download Endpoint (/download/online or /download/offline)
    const match = url.pathname.match(/^\/download\/(online|offline)$/);
    if (match) {
      const variant = match[1];
      const targetName = `app-${variant}-release.apk`;
      const releaseRes = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
        headers: {
          "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
          "User-Agent": "Mobile-Harness-Updater",
          "Accept": "application/vnd.github+json"
        }
      });
      const release = await releaseRes.json();
      const apkAsset = release.assets.find(a => a.name === targetName);
      if (!apkAsset) return new Response(`Asset ${targetName} not found`, { status: 404 });

      // Fetch asset redirect with stream
      const downloadRes = await fetch(apkAsset.url, {
        headers: {
          "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
          "User-Agent": "Mobile-Harness-Updater",
          "Accept": "application/octet-stream"
        },
        redirect: "follow"
      });
      return new Response(downloadRes.body, {
        headers: {
          "Content-Type": "application/vnd.android.package-archive",
          "Content-Disposition": `attachment; filename="${targetName}"`
        }
      });
    }

    return new Response("Mobile Harness Extended Update Gateway Active", { status: 200 });
  }
};
```

#### 2. Set the Worker URL in the App:
- Set `appUpdateManifestUrl` in `app/build.gradle.kts` to `https://updates.yourdomain.workers.dev/check`.
- Users download updates instantly without any tokens required in their app!

---

### Strategy C: Dual-Repository Mirror (Private Source + Public Release Binaries)
1. Keep the source code repository (`sunmughan/Conzyx`) strictly private.
2. Create an empty public repository (e.g. `sunmughan/Mobile-Harness-Releases`).
3. In `.github/workflows/android.yml`, add a release step that uploads the compiled APKs and `mobile-harness-update.json` to the public releases repository using `gh release upload --repo sunmughan/Mobile-Harness-Releases`.

---

## 3. Cryptographic Verification & Tamper Protection

Every downloaded APK must pass three mandatory security checks before `AndroidAppInstaller` is invoked:
1. **Package ID Integrity**: The APK's package name in `AndroidManifest.xml` must match `com.codeair.mhe`.
2. **SHA-256 Checksum Match**: The file hash must exactly match the checksum specified in `mobile-harness-update.json`.
3. **Permanent Release Certificate Verification**:
   - `AppUpdater.verifyApk()` extracts the APK's signing certificate and compares its SHA-256 digest with the currently installed app's signing key:
   - Expected Release Key SHA-256:
     ```
     86:D4:21:7F:86:E4:0A:B2:58:46:04:C5:6D:C3:6A:FB:54:DF:83:5D:38:2A:A4:FC:65:07:40:C6:00:1D:E0:F5
     ```
   - If an APK was signed with an untrusted debug key or wrong key, installation is aborted immediately with an explicit error to protect users.

---

## 4. How to Cut a New Release in GitHub Actions

To release a new update:
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 28
   versionName = "2.2.0"
   ```
2. Commit and tag the commit:
   ```bash
   git add app/build.gradle.kts
   git commit -m "chore(release): v2.2.0"
   git tag v2.2.0
   git push origin main --tags
   ```
3. GitHub Actions (`.github/workflows/android.yml`) will:
   - Build `assembleOnlineRelease` and `assembleOfflineRelease`.
   - Sign both APKs with the permanent Codeair release key.
   - Compute SHA-256 checksums and file sizes.
   - Generate `mobile-harness-update.json` with both `online` and `offline` artifacts.
   - Publish the GitHub Release with the tag `v2.2.0`.
4. Users opening Mobile Harness Extended will automatically see the in-app update banner or dialog and download the exact matching flavor!
