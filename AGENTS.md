# droidhelp

Android client built with Jetpack Compose, Kotlin, OkHttp, and Kotlin Serialization.

## Setup & Build
- **Build**: `./gradlew build`
- **Deploy to device/emulator**: `./gradlew installDebug`
- **SDK & Toolchain**: `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, Java 17, Kotlin `2.1.10`, AGP `8.7.0`.
- **Dependencies**: Managed in `gradle/libs.versions.toml` (test dependencies are declared in `app/build.gradle.kts`).

## Testing
- **Run all unit tests**: `./gradlew test`
- **Run a single unit test**: `./gradlew :app:testDebugUnitTest --tests "com.bighelp.droidhelp.<ClassName>"`
- **Run Android instrumentation tests**: `./gradlew connectedAndroidTest`
- **Test Stack**: Configured with `useJUnitPlatform()` (JUnit 5), alongside JUnit 4, Robolectric, MockWebServer, and `kotlinx-coroutines-test`.
- **Integration Test Boilerplate**: When writing `MockWebServer` / coroutine integration tests, copy imports and setup verbatim from `app/src/test/java/com/bighelp/droidhelp/transport/UnsupportedMethodIntegrationTest.kt` (JUnit 5 `org.junit.jupiter.api.*`, `okhttp3.mockwebserver.*`, `kotlinx.coroutines.test.runTest`, `kotlinx.coroutines.withTimeoutOrNull`).
- **Iteration Workflow**: Pass `--no-daemon --console=plain --warning-mode=none -q` (or `| tail -n 50` without `-q` for test summaries) to `./gradlew`, iterate with `--tests "com.bighelp.droidhelp.<ClassName>"`, and run full `./gradlew test --no-daemon --console=plain --warning-mode=none | tail -n 50` only once at the end.

## Code Style & Conventions
- **Stack**: Android, Jetpack Compose (Material 3), Kotlin Coroutines.
- **Serialization**: Use `kotlinx.serialization` (`kotlinx.serialization.json`) for JSON handling.
- **Conventions**: Follow standard Android and Kotlin coding guidelines; keep existing comments and docstrings intact.

## Architecture & Contracts
- **Fencing Contract**: See [docs/FENCING_CONTRACT.md](docs/FENCING_CONTRACT.md) for the `SignIn` / `Owner` / `SocketOwner` fencing rules (`Owners.kt`, `StaleOwnerException`) governing async transport, workspace, and authentication operations.
- **Networking**: `OkHttp` for HTTP and WebSocket transport (`transport/`).
- **Persistence & Security**: `androidx.security:security-crypto` (`AndroidSecureStorage`), `androidx.datastore:datastore-preferences`, and SQLite (`SqliteVersionedDatabase`).

## Project Structure
- `app/`: Main Android application module (the only Gradle module in `settings.gradle.kts`).
  - `app/src/main/java/com/bighelp/droidhelp/`:
    - `app/`: Application entry point and composition (`DroidHelpApp`, `AppComposition`).
    - `home/` & `home/model/`: Compose screens (`WorkView`, `ChatsInboxView`, `SearchScreen`), `WorkViewModel`, and navigation/UI models.
    - `chat/`: Chat UI models and performance utilities.
    - `designsystem/`: Compose theme and typography.
    - `platform/`: Authentication (`Auth`, `BrowserAuthentication`, `SignInCallbackHandler`), secure storage (`SecureStorage`, `AndroidSecureStorage`), and SQLite database (`VersionedDatabase`, `SqliteVersionedDatabase`).
    - `session/`: Session coordination, prompt submission, streaming, event streams, and submission journaling.
    - `transport/`: WebSocket transport, RPC correlation, heartbeat, reconnect policy, endpoint/capabilities/health clients, and `Owners` fencing.
- `docs/`: Architecture and contract documentation (including `FENCING_CONTRACT.md`).
- **Non-Gradle files**: Root `persistence/` (a standalone Room prototype) and `test_serialization.kt` are not included in `settings.gradle.kts` or `app/build.gradle.kts` and are not compiled by Gradle.
