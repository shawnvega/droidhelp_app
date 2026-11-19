# droidhelp

## Setup & Build
- Build the project: `./gradlew build`
- Deploy to device/emulator: `./gradlew installDebug`

## Testing
- Run unit tests: `./gradlew test`
- Run Android instrumentation tests: `./gradlew connectedAndroidTest`

## Code Style
- **Stack**: Android, Jetpack Compose, Kotlin
- **Target JVM**: Java 17
- **Serialization**: Use Kotlin Serialization for JSON handling.
- **Conventions**: Follow standard Android/Kotlin coding guidelines.

## Project Structure
- `app/`: Main application module containing the Android/Compose logic.
- `app/src/main/java/com/bighelp/droidhelp/`: Core logic including transport protocols and capabilities.
