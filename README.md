# PostSweeper

[![Android CI](https://github.com/abusulayman710/post-sweeper/actions/workflows/android.yml/badge.svg)](https://github.com/abusulayman710/post-sweeper/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-blue.svg)](https://developer.android.com/jetpack/compose)

PostSweeper is an open-source Android application that autonomously purges your own X (formerly Twitter) posts, replies, reposts, and likes directly from your mobile device.

It operates safely in the background at a pace X tolerates, adapts dynamically to live rate limits, and **only marks an item as deleted after an independent read verification confirms it is gone**.

---

## Key Features

* **Multi-Content Sweeping**: Target posts, replies, reposts, quotes, and likes independently or all together.
* **Independent Read Verification**: Unlike naive scripts that assume HTTP 200 means success, PostSweeper queries `TweetResultByRestId` after every operation. A post is marked deleted only when confirmed gone (or a tombstone is returned).
* **The Rate-Limit Governor (`RateGovernor.kt`)**:
  * Individual token buckets for each operation type (delete, un-repost, un-like, timeline reads, single reads).
  * Smooth request pacing with randomized jitter and periodic "breather" pauses.
  * Honors `x-rate-limit-remaining` and `x-rate-limit-reset` headers before hitting walls.
  * Adaptive learning: if throttled (HTTP 429 / Code 88), it cools down and recalculates safe hourly/daily thresholds automatically.
  * Circuit breaker stops sweeps if X flags requests with Code 226 ("automated activity") or session expiry.
* **Zero-Credential Storage & In-App Browser**:
  * Sign in directly on `x.com` through the integrated secure WebView.
  * Passwords and cookies are never stored, logged, or exported.
  * Requests inherit authentic Chromium TLS/HTTP2 fingerprints and valid CSRF tokens.
* **Relay & GraphQL Self-Healing Engine**:
  * Seamless failover across candidate operation IDs (legacy GraphQL URT and modern Relay endpoints).
  * Automatically discovers rotating query IDs and evicts stale endpoints on HTTP 404 without crashing sweeps.
* **Deep Filter Engine**:
  * Age thresholds (e.g., delete items older than 30/90/365 days).
  * Exact date ranges and "keep newest N" protection.
  * Engagement shields: protect posts with more than X likes, reposts, replies, or views.
  * Keyword matching: regex, case-sensitivity, whole words, and keep-word filters.
  * Content attributes: filter by media attachments, links, hashtags, mentions, or self-replies.
  * Keep-lists: preserve specific post IDs or URLs permanently.
* **Official Archive Import**:
  * The live X timeline only exposes your most recent ~3,200 items.
  * Import your official X archive `.zip` (`tweets.js`, `like.js`) to queue and sweep your entire historical catalog.
* **Persistent Background Execution & Glance Widgets**:
  * Runs as a foreground service with exact alarm scheduling, surviving phone reboots and app dismissal.
  * Jetpack Glance home screen widgets (2x2, 4x2, 4x4) to monitor progress and pause/resume sweeps directly.

---

## Architecture & Tech Stack

```
app/src/main/java/app/sweeper/
├── data/           # Room Database (posts, operations log) & Preferences DataStore
├── engine/         # RateGovernor, FilterEngine, DeletionEngine, ArchiveImporter
├── net/            # Headless WebView transport, GraphQL/Relay query resolution & URT parser
├── service/        # Android Foreground Service, WorkManager watchdogs & Notification manager
├── ui/             # Jetpack Compose UI screens (Home, Posts, Filters, Settings, Login)
└── widget/         # Jetpack Glance AppWidgets
```

| Component | Library | Purpose |
|---|---|---|
| **UI** | Jetpack Compose + Material 3 | Modern declarative interface |
| **Widgets** | Jetpack Glance | Interactive home screen widgets |
| **Persistence** | Room (SQLite) + Jetpack DataStore | Local post records, sweep logs, and governor state |
| **Networking** | AndroidX WebKit | Headless Chromium origin bridge for authenticated GraphQL |
| **Concurrency** | Kotlin Coroutines & Flow | Non-blocking reactive pipelines |
| **Serialization** | Kotlinx Serialization | Resilient JSON parsing |

---

## Getting Started

### Sideloading the APK
1. Download the latest `PostSweeper-3.0.apk` from the [Releases](https://github.com/abusulayman710/post-sweeper/releases) page.
2. Transfer and install on your Android device (Android 8.0+ / API 26+).
3. Open PostSweeper and tap **Log In**.
4. Log into your X account in the official web view.
5. Configure your filters and pacing profile, then start the sweep.

---

## Building from Source

### Prerequisites
* JDK 17 (Java Development Kit)
* Android SDK 35 (Android 15) with platform tools

### Build Steps

Clone the repository:
```bash
git clone https://github.com/abusulayman710/post-sweeper.git
cd post-sweeper
```

Run tests and compile the release APK:
```bash
# On Linux / macOS:
./gradlew testDebugUnitTest
./gradlew assembleRelease

# On Windows:
gradlew.bat testDebugUnitTest
gradlew.bat assembleRelease
```

The compiled APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`

---

## Privacy & Security

* **No Analytics / Telemetry**: PostSweeper contains zero tracking, analytics, or crash reporting libraries.
* **Direct Connection**: Every network request travels straight from your device to `x.com`. No intermediate proxy or relay is used.
* **Local Storage**: Your posts, filters, and sweep logs never leave your phone.
* Read our full [Security Policy](SECURITY.md).

---

## Contributing

Contributions, bug reports, and suggestions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before opening a pull request.

---

## Disclaimer

PostSweeper is an independent open-source project and is **not affiliated with or endorsed by X Corp.** Use this tool responsibly. Review [DISCLAIMER.md](DISCLAIMER.md) for details.

---

## License

This project is licensed under the [MIT License](LICENSE).
