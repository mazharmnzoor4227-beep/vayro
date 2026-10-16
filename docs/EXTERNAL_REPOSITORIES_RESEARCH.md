# External Repositories & Agent Skills Research

Comprehensive architectural analysis, classification, and integration reference for the surveyed external GitHub repositories, agent skills, and standalone system solutions.

---

## 1. Quick Classification Matrix

| Project | Category | Integrated in Mobile Harness Extended As | Official Repository / Source |
| :--- | :--- | :--- | :--- |
| **`geo-sleuth`** | **Agent Skill** | Native Agent Skill (`.agents/skills/geo-sleuth`) | [`Oldcircle/geo-sleuth`](https://github.com/Oldcircle/geo-sleuth) |
| **`stickman-video-director`** | **Agent Skill** | Native Agent Skill (`.agents/skills/stickman-video-director`) | [`kaomei/stickman-video-director`](https://github.com/kaomei/stickman-video-director) |
| **`api-anything`** | **Agent Skill / Tool** | Native Agent Skill (`.agents/skills/api-anything`) | [`goodnight000/api-anything`](https://github.com/goodnight000/api-anything) |
| **`logo-design-skill`** | **Agent Skill** | Native Agent Skill (`.agents/skills/logo-design-skill`) | [`kaankiziltug/logo-design-skill`](https://github.com/kaankiziltug/logo-design-skill) |
| **`hermes3d`** | Standalone System | Browser Quick Shortcut (Virtual Office) | [`iamlukethedev/Hermes3D`](https://github.com/iamlukethedev/Hermes3D) |
| **`opengym`** | Standalone System | Browser Quick Shortcut (Workout Tracker) | [`DuarteSantos8/openGym`](https://github.com/DuarteSantos8/openGym) |
| **`tiktok-5.6b-videos`** | Standalone System / Dataset | Browser Quick Shortcut (Dataset Analytics) | [`datasocial/tiktok-5.6B-videos`](https://huggingface.co/datasets/datasocial/tiktok-5.6B-videos) |
| **`gitframes`** | Standalone System / Engine | Browser Quick Shortcut (WebGPU Video) | [`gatewai-dev/gitframes`](https://github.com/gatewai-dev/gitframes) |
| **`auto-social`** | Standalone System | Browser Quick Shortcut (Video Automation) | [`Katzca/AutoSocial`](https://github.com/Katzca/AutoSocial) |
| **`munder difflin`** | Standalone System / Harness | Browser Quick Shortcut (Office Harness) | [`chaitanyagiri/munder-difflin`](https://github.com/chaitanyagiri/munder-difflin) |

---

## 2. Integrated Agent Skills (Auto-Update Enabled)

These skills are natively provisioned by `SkillManager` into `File(context.filesDir, "skills")` and synchronized into project workspaces at `.agents/skills/`. Each skill is configured with its upstream GitHub repository URL in `SkillManager.kt`, enabling automatic update tracking via **Settings → Skills → Check updates**.

### 1. `geo-sleuth`
* **Repository:** [Oldcircle/geo-sleuth](https://github.com/Oldcircle/geo-sleuth)
* **Author:** Oldcircle
* **Role:** Autonomous Photograph Geolocation
* **Architecture:** Combines OpenStreetMap Overpass spatial queries, Digital Elevation Models (DEM) horizon matching, and satellite/street-view verification.
* **Agent Commands:** Prompt the agent with an image: `"Where was this photo taken? Investigate the location using geo-sleuth."`

### 2. `stickman-video-director`
* **Repository:** [kaomei/stickman-video-director](https://github.com/kaomei/stickman-video-director)
* **Author:** kaomei
* **Role:** Stickman Explainer Animation Director
* **Architecture:** 2-phase pipeline converting copy or notes into a 6-scene structured visual storyboard sequence (10s/scene) followed by 6 synchronized prompts optimized for Google Gemini Omni Flash / Veo.
* **Agent Commands:** `"Act as stickman video director and create a 60-second explainer storyboard for this topic."`

### 3. `api-anything`
* **Repository:** [goodnight000/api-anything](https://github.com/goodnight000/api-anything)
* **Author:** goodnight000 (Tianjun Zheng)
* **Role:** Dynamic Self-Healing Web API Generator
* **Architecture:** Reverse-engineers web frontends by analyzing client network traffic and synthesizes programmatic API endpoints callable via Model Context Protocol (MCP), CLI, or TypeScript.
* **Agent Commands:** `"Use api-anything to learn this web interface and generate callable API endpoints."`

### 4. `logo-design-skill`
* **Repository:** [kaankiziltug/logo-design-skill](https://github.com/kaankiziltug/logo-design-skill)
* **Author:** Kaan Kiziltug
* **Role:** Professional SVG Brand Identity Workflow
* **Architecture:** Disciplined vector design process covering brand briefs, 8–12 concept variations, 16px favicon legibility audits, monochrome inversion stress tests, and scalable SVG asset kits.
* **Agent Commands:** `"Design a modern geometric vector logo in SVG for [Brand Name] following logo-design-skill."`

---

## 3. Standalone Systems (Browser Quick Shortcuts)

These projects are independent applications, self-hosted services, or datasets that run outside the agent skill sandbox. They are accessible in Mobile Harness Extended via the in-app Chromium **Preview** homescreen under **Standalone Systems & Tools**:

### 1. `hermes3d`
* **Repository:** [iamlukethedev/Hermes3D](https://github.com/iamlukethedev/Hermes3D)
* **Description:** Self-hosted 3D virtual office simulator using Three.js/WebGL to visualize autonomous AI agents walking, collaborating, and holding standups.

### 2. `opengym`
* **Repository:** [DuarteSantos8/openGym](https://github.com/DuarteSantos8/openGym)
* **Description:** Modern, self-hosted gym and bodyweight workout tracker deployable via Docker with WebAuthn Passkey authentication and progression analytics.

### 3. `tiktok-5.6b-videos`
* **Repository:** [datasocial/tiktok-5.6B-videos](https://huggingface.co/datasets/datasocial/tiktok-5.6B-videos)
* **Description:** 5.6 Billion public TikTok video metadata records spanning 2014–2026, stored in monthly Parquet files with direct ClickHouse query support.

### 4. `gitframes` (framefields)
* **Repository:** [gatewai-dev/gitframes](https://github.com/gatewai-dev/gitframes)
* **Description:** High-performance, code-first video engine executing native WebGPU shaders and compute pipelines for programmatic video generation and VFX.

### 5. `auto-social`
* **Repository:** [Katzca/AutoSocial](https://github.com/Katzca/AutoSocial)
* **Description:** Local multi-account automation dashboard utilizing Playwright, FFmpeg deduplication algorithms, and scheduling queues for TikTok, Reels, and Shorts.

### 6. `munder difflin`
* **Repository:** [chaitanyagiri/munder-difflin](https://github.com/chaitanyagiri/munder-difflin)
* **Description:** "The Office"-themed local desktop harness orchestrating teams of coding agents (Claude Code, Codex) with local mailboxes, shared blackboard, and cost telemetry.

---

## 4. Unorthodox, Daily Life, Academic & Developer Skills Suite

The following skills are provisioned in `SkillManager.kt` and installed in `.agents/skills/`:

| Skill Name | Upstream Repository / Source | Domain | Key Capabilities |
| :--- | :--- | :--- | :--- |
| **`anyps5-director`** | [`boykopovar/AnyPS5`](https://github.com/boykopovar/AnyPS5) | Gaming / Binary Translation | Native PS5 ELF relinking, Vulkan SPIR-V shader recompilation, PE (.exe) conversion without full emulation. |
| **`youtube-video-intel`** | [`jarves/youtube-video-intel`](https://github.com/jarves/youtube-video-intel) | Video Understanding | Multimodal YouTube analysis, timestamped chaptering, code/formula extraction, and deep summary briefings. |
| **`fridge-vision-chef`** | [`jarves/fridge-vision-chef`](https://github.com/jarves/fridge-vision-chef) | Daily Life / Culinary | Refrigerator/pantry image analysis, expiration tracking, macro/calorie calculations, gourmet recipes. |
| **`receipt-expense-sleuth`**| [`jarves/receipt-expense-sleuth`](https://github.com/jarves/receipt-expense-sleuth) | Daily Life / Personal Finance | OCR receipt & invoice auditing, recurring subscription spike detection, tax tagging, CSV/ledger export. |
| **`contract-legal-buster`** | [`jarves/contract-legal-buster`](https://github.com/jarves/contract-legal-buster) | Daily Life / Legal | Rental lease & contract analysis, predatory clause identification, penalty risk scorecard, negotiation redlines. |
| **`smarthome-iot-commander`**| [`jarves/smarthome-iot-commander`](https://github.com/jarves/smarthome-iot-commander)| Daily Life / Home Automation | Home Assistant / ESPHome orchestrator, automated scene blueprinting, energy optimization routines. |
| **`academic-paper-architect`**|[`jarves/academic-paper-architect`](https://github.com/jarves/academic-paper-architect)| PhD Research & Journals | LaTeX/BibTeX structuring, related works matrix synthesis, mathematical formulations, peer-review rebuttal engine. |
| **`document-transmuter`** | [`jarves/document-transmuter`](https://github.com/jarves/document-transmuter) | Universal Docs | Markdown, PDF, DOCX, LaTeX conversion, table preservation, OCR artifact cleaning. |
| **`marp-presentation-deck`**| [`jarves/marp-presentation-deck`](https://github.com/jarves/marp-presentation-deck)| Presentations | Marp / Reveal.js markdown slide decks, custom CSS directives, visual hierarchies, PDF/HTML exports. |
| **`creative-image-director`**| [`jarves/creative-image-director`](https://github.com/jarves/creative-image-director)| Visual Prompting & SVG | Cinematic camera angle prompt engineering for Google Imagen/Midjourney, style consistency, vector SVG output. |
| **`viral-growth-creator`** | [`jarves/viral-growth-creator`](https://github.com/jarves/viral-growth-creator) | Content Creators / Growth | 15-second YouTube video hooks, high-CTR title variations, viral X threads, LinkedIn carousels, algorithmic retention. |
| **`fullstack-dev-accelerator`**|[`jarves/fullstack-dev-accelerator`](https://github.com/jarves/fullstack-dev-accelerator)| Developers / Engineering | OpenAPI architecture design, AST refactoring, unit test generation, CI/CD Docker pipelines. |

---

## 5. YouTube Video Understanding via Gemini

Integrated in the main prompt bar (`PocketDevApp.kt`):
- **Access**: Red YouTube icon (`Icons.Default.SmartDisplay`, `#FF0000`) placed next to the `Mic` icon in `ChatTab`.
- **Validation**: Strict validation via `YouTubeUrlValidator.kt` permitting only legitimate YouTube links (`watch?v=`, `youtu.be/`, `shorts/`, `live/`) and rejecting all non-YouTube domains.
- **Dialog (`YouTubeVideoDialog.kt`)**: Clipboard paste, live error/success badges, inquiry presets (Deep Summary, Key Timestamps, Action Items, Code Walkthrough), and custom query input.
- **Execution**: Assembles a high-precision multi-modal prompt dispatched directly to Gemini to ingest the video and output a detailed analysis.

