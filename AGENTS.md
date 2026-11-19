# droidhelp

## Setup & Build
- Install dependencies and build the project: `./gradlew build`
- Run the development server (Android install): `./gradlew installDebug`

## Testing
- Run unit tests: `./gradlew test`
- Run Android instrumentation tests: `./gradlew connectedAndroidTest`

## Code Style
- Language: Kotlin
- Target JVM: Java 17
- Use Kotlin Serialization for JSON handling.
- Follow standard Android/Kotlin coding conventions.

## Project Structure
- `app/`: Main application module.
- `app/src/main/java/com/bighelp/droidhelp/`: Core logic including transport protocols and capabilities.
