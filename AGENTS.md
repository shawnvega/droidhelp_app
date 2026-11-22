# droidhelp

Android client (Kotlin 2.1.10, Java 17, AGP 8.7.0, `minSdk = 26`, `targetSdk = 35`).
Stack: Jetpack Compose (Material 3), Coroutines, OkHttp, `kotlinx.serialization.json`, DataStore, `AndroidSecureStorage`, SQLite (`SqliteVersionedDatabase`).
Dependencies: `gradle/libs.versions.toml` (test deps in `app/build.gradle.kts`).

## Commands & Testing (JUnit 4, Robolectric, MockWebServer, `kotlinx-coroutines-test`)
- **Single test (use while iterating)**:
  `./gradlew :app:testDebugUnitTest --tests "com.bighelp.droidhelp.<Package.ClassName>" --no-daemon 2>&1 | tail -n 80`
- **All unit tests (run once at the end)**:
  `./gradlew test --no-daemon 2>&1 | tail -n 80`
- **Static analysis / full build**: `./gradlew detekt` / `./gradlew build` (`detekt` and `lint` fail on errors).
- **Detekt rules (`maxIssues: 0`)**: No wildcard imports (`import foo.*`), no magic numbers in local vars/expressions (extract constants), max line length 120, max 60 lines/method, max 15 functions/class, no `GlobalScope` or `Thread.sleep`.
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
