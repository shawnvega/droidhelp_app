# droidhelp

Android client (Kotlin 2.1.10, Java 17, AGP 8.7.0, `minSdk = 26`, `targetSdk = 35`).
Stack: Jetpack Compose (Material 3), Coroutines, OkHttp, `kotlinx.serialization.json`, DataStore, `AndroidSecureStorage`, SQLite (`SqliteVersionedDatabase`).
Dependencies: `gradle/libs.versions.toml` (test deps in `app/build.gradle.kts`).

## Commands & Testing (JUnit 4, Robolectric, MockWebServer, `kotlinx-coroutines-test`)
- **Single test (use while iterating)**:
  `./gradlew :app:testDebugUnitTest --tests "com.bighelp.droidhelp.<Package.ClassName>" --no-daemon --console=plain --warning-mode=none 2>&1 | tail -n 80`
- **All unit tests (run once at the end)**:
  `./gradlew test --no-daemon --console=plain --warning-mode=none 2>&1 | tail -n 80`
- **Static analysis / full build**: `./gradlew detekt` / `./gradlew lintDebug` / `./gradlew build`. Pre-commit hook runs `detekt`, `lintDebug`, and `test`.
- **Detekt rules (`maxIssues: 0`)**: No wildcard imports (`import foo.*`), no magic numbers in local vars/expressions (extract constants), max line length 120, max 60 lines/method, max 14 functions/class (`15` fails), max 6 params, no `TooGenericExceptionCaught`/`SwallowedException`, `MatchingDeclarationName`, no `GlobalScope`/`Thread.sleep`.
- **Test boundaries**: Put JUnit tests in `app/src/test/` (never `app/src/main/`). `androidx.compose.ui.test` (`createComposeRule`) is `androidTestImplementation`-only (`app/src/androidTest/`).
- **WebSocket `MockWebServer` gotcha**: Consume the initial `GET` upgrade with `server.takeRequest()` before taking HTTP requests, and close in `onClosing { ws, code, reason -> ws.close(code, reason) }` so `server.shutdown()` won't hang.
- **Test templates** (in `app/src/test/java/com/bighelp/droidhelp/`):
  - HTTP `MockWebServer` + `runTest`: `transport/HealthClientTest.kt`
  - WebSocket `MockWebServer`: `transport/WebSocketTransportTest.kt`
  - In-memory `FakeTransport`: `transport/UnsupportedMethodIntegrationTest.kt`

## Architecture & Source Map (`app/src/main/java/com/bighelp/droidhelp/`)
- **Fencing Contract**: Read [docs/FENCING_CONTRACT.md](docs/FENCING_CONTRACT.md) before touching async transport, workspace, or auth fencing (`SignIn`, `Owner`, `SocketOwner`, `StaleOwnerException` in `transport/Owners.kt`).
- `app/`: Entry point & DI (`DroidHelpApp`, `AppComposition`).
- `home/` & `home/model/`: Screens (`WorkView`, `ChatsInboxView`, `SearchScreen`), `WorkViewModel`, UI models.
- `chat/` & `designsystem/`: Chat UI models/perf utilities; Compose theme & typography.
- `platform/`: Auth (`Auth`, `BrowserAuthentication`, `SignInCallbackHandler`), `SecureStorage`, `SqliteVersionedDatabase`.
- `session/`: Session coordination, prompt submission, streaming, event streams, submission journal.
- `transport/`: OkHttp WebSocket transport, RPC correlation, heartbeat, reconnect policy, endpoint/capabilities/health clients, `Owners` fencing.
