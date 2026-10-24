# Contributing to PostSweeper

Thank you for your interest in contributing to PostSweeper. We welcome bug reports, feature requests, documentation improvements, and pull requests.

## Development Setup

### Prerequisites
- **JDK**: Java Development Kit 17 (Temurin, Zulu, or Oracle).
- **Android SDK**: Android API 35 (compileSdk 35, minSdk 26).
- **Android Studio**: Koala / Ladybug or command-line tools.

### Building the Project

Clone the repository and verify the test suite:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK (debug-signed for direct sideloading)
./gradlew assembleRelease
```

On Windows command line:
```cmd
gradlew.bat testDebugUnitTest
gradlew.bat assembleRelease
```

## Architecture Overview

PostSweeper follows a modular, reactive architecture:

- `app.sweeper.net`: X web client emulation layer.
  - `XTransport.kt`: Headless Chromium WebView bridge providing authentic TLS/HTTP2 fingerprints, headers, and CSRF handling.
  - `XApi.kt`: GraphQL query execution, candidate fallback resolution, and query ID discovery.
  - `XParser.kt`: Unified JSON parser supporting both legacy GraphQL URT and modern Relay URT payloads.
- `app.sweeper.engine`: Core sweep execution and safety systems.
  - `RateGovernor.kt`: Token bucket rate limiting, jitter pacing, header inspection, and automatic backoff ladder.
  - `FilterEngine.kt`: Multi-criteria filtering (date ranges, keep counts, engagement thresholds, keywords, media types).
  - `DeletionEngine.kt`: Scan, filter, delete, and independent read verification state machine.
  - `ArchiveImporter.kt`: Parser for official X archive exports (`tweets.js`, `like.js`).
- `app.sweeper.service`: Android background execution.
  - `DeletionService.kt`: Foreground service managing partial wake-locks and WorkManager watchdogs.
- `app.sweeper.ui`: Modern Jetpack Compose UI with Material 3.
- `app.sweeper.widget`: Jetpack Glance home screen widgets.

## Submitting Pull Requests

1. **Fork & Branch**: Create a descriptive feature branch from `main` (`feature/relay-fallback`, `fix/cursor-parsing`).
2. **Code Quality**: Keep changes focused and well-scoped. Maintain existing comments and architecture boundaries.
3. **Verify Locally**: Ensure `./gradlew testDebugUnitTest` and `./gradlew assembleRelease` pass with zero errors.
4. **Pull Request**: Open a PR with a concise description of what changed, why it is needed, and any test output.
