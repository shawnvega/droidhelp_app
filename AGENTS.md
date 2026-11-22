# droidhelp

Android client (Kotlin 2.1.10, Java 17, AGP 8.7.0, `minSdk = 26`, `targetSdk = 35`).
Stack: Jetpack Compose (Material 3), Coroutines, OkHttp, `kotlinx.serialization.json`, DataStore, `AndroidSecureStorage`, SQLite (`SqliteVersionedDatabase`).
Dependencies: `gradle/libs.versions.toml` (test deps in `app/build.gradle.kts`).

## Commands & Testing (JUnit 4, Robolectric, MockWebServer, `kotlinx-coroutines-test`)
- **Single test (use while iterating)**:
  `./gradlew :app:testDebugUnitTest --tests "com.bighelp.droidhelp.<Package.ClassName>" --no-daemon --console=plain --warning-mode=none 2>&1 | tail -n 50`
- **All unit tests (run once at the end)**:
  `./gradlew test --no-daemon --console=plain --warning-mode=none 2>&1 | tail -n 50`
- **Static analysis / full build**: `./gradlew detekt` / `./gradlew build` (note: `detekt` and `lint` fail on errors).
- **Integration test template**: Copy imports and `MockWebServer` + `runTest` setup verbatim from `app/src/test/java/com/bighelp/droidhelp/transport/UnsupportedMethodIntegrationTest.kt`.

## Architecture & Source Map (`app/src/main/java/com/bighelp/droidhelp/`)
- **Fencing Contract**: Read [docs/FENCING_CONTRACT.md](docs/FENCING_CONTRACT.md) before touching async transport, workspace, or auth fencing (`SignIn`, `Owner`, `SocketOwner`, `StaleOwnerException` in `transport/Owners.kt`).
- `app/`: Entry point & DI (`DroidHelpApp`, `AppComposition`).
- `home/` & `home/model/`: Screens (`WorkView`, `ChatsInboxView`, `SearchScreen`), `WorkViewModel`, UI models.
- `chat/` & `designsystem/`: Chat UI models/perf utilities; Compose theme & typography.
- `platform/`: Auth (`Auth`, `BrowserAuthentication`, `SignInCallbackHandler`), `SecureStorage`, `SqliteVersionedDatabase`.
- `session/`: Session coordination, prompt submission, streaming, event streams, submission journal.
- `transport/`: OkHttp WebSocket transport, RPC correlation, heartbeat, reconnect policy, endpoint/capabilities/health clients, `Owners` fencing.
- **Ignore non-Gradle files**: Root `persistence/` and `test_serialization.kt` are not in `settings.gradle.kts` and are not compiled.
