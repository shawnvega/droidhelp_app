# Pre-Commit Hook Validation

This document records manual validation of the repository's Git pre-commit hook against failing and compliant commits.

## Hook Configuration

- **Git config**: `core.hooksPath = .githooks`
- **Hook script**: [`.githooks/pre-commit`](../.githooks/pre-commit) (mirrored in `.git/hooks/pre-commit`)
- **Checks executed on every commit**:
  1. **Step 1/3 — Detekt**: `./gradlew detekt` (`detekt.yml` with `maxIssues: 0`, preserving the Detekt pre-commit check).
  2. **Step 2/3 — Android Lint**: `./gradlew lintDebug` (`abortOnError = true` in `app/build.gradle.kts`, with `NewApi` and `MissingPermission` set to `error` in `app/lint.xml`).
  3. **Step 3/3 — Unit Tests**: `./gradlew test` (JUnit 5 + JUnit 4 vintage engine).
- **Baseline `HEAD` prior to 3-step re-validation**: `eb307ff` (`docs: record observed compliant commit validation outcome`)

---

## Test 1: Failing Changes Blocked by Hook

All three gates in the pre-commit hook were tested independently with temporary changes that were reverted after verifying the hook aborted each commit.

### Test 1a: Detekt Failure (`./gradlew detekt`)

- **Temporary change**: Added `app/src/main/java/com/bighelp/droidhelp/app/DetektFailureProbe.kt` containing a line exceeding the configured 120-character `MaxLineLength` limit (`detekt.yml`).
- **Command**:
  ```sh
  git add app/src/main/java/com/bighelp/droidhelp/app/DetektFailureProbe.kt
  git commit -m "test: verify pre-commit hook blocks detekt failure"
  ```
- **Observed outcome**: **Blocked (PASSED validation)**
  - Exit code: `1`
  - Detekt error reported:
    ```text
    /opt/data/projects/droidhelp/app/src/main/java/com/bighelp/droidhelp/app/DetektFailureProbe.kt:4:1: Line detected, which is longer than the defined maximum line length in the code style. [MaxLineLength]
    Analysis failed with 2 weighted issues.
    ```
  - Hook error message:
    ```text
    Pre-commit hook failed: './gradlew detekt' exited with status 1. Commit aborted.
    ```
  - Steps 2/3 (`./gradlew lintDebug`) and 3/3 (`./gradlew test`) were not executed due to fail-fast abort.
  - Confirmed `HEAD` remained unchanged at `eb307ff`.
- **Cleanup**: Unstaged and removed `DetektFailureProbe.kt`.

### Test 1b: Android Lint Failure (`./gradlew lintDebug`)

- **Temporary change**: Added `app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt` accessing `Build.VERSION.RELEASE_OR_CODENAME` (API 30) without an `SDK_INT` guard while `minSdk = 26`.
- **Command**:
  ```sh
  git add app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt
  git commit -m "test: verify pre-commit hook blocks lintDebug failure"
  ```
- **Observed outcome**: **Blocked (PASSED validation)**
  - Exit code: `1`
  - Step 1/3 (`./gradlew detekt`): Passed (`BUILD SUCCESSFUL`).
  - Step 2/3 (`./gradlew lintDebug`): Failed:
    ```text
    /opt/data/projects/droidhelp/app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt:6: Error: Field requires API level 30 (current min is 26): android.os.Build.VERSION#RELEASE_OR_CODENAME [NewApi]
    1 errors, 41 warnings
    ```
  - Hook error message:
    ```text
    Pre-commit hook failed: './gradlew lintDebug' exited with status 1. Commit aborted.
    ```
  - Step 3/3 (`./gradlew test`) was not executed due to fail-fast abort.
  - Confirmed `HEAD` remained unchanged at `eb307ff`.
- **Cleanup**: Unstaged and removed `LintFailureProbe.kt`.

### Test 1c: Unit Test Failure (`./gradlew test`)

- **Temporary change**: Added `app/src/test/java/com/bighelp/droidhelp/transport/HookFailureProbeTest.kt` containing a failing assertion (`assertEquals(2, 1 + 2)`).
- **Command**:
  ```sh
  git add app/src/test/java/com/bighelp/droidhelp/transport/HookFailureProbeTest.kt
  git commit -m "test: verify pre-commit hook blocks unit test failure"
  ```
- **Observed outcome**: **Blocked (PASSED validation)**
  - Exit code: `1`
  - Step 1/3 (`./gradlew detekt`): Passed (`BUILD SUCCESSFUL`).
  - Step 2/3 (`./gradlew lintDebug`): Passed (`0 errors, 41 warnings`, `BUILD SUCCESSFUL`).
  - Step 3/3 (`./gradlew test`): Failed:
    ```text
    HookFailureProbeTest > deliberateFailureToVerifyPreCommitHook() FAILED
        org.opentest4j.AssertionFailedError at HookFailureProbeTest.kt:9

    165 tests completed, 1 failed
    ```
  - Hook error message:
    ```text
    Pre-commit hook failed: './gradlew test' exited with status 1. Commit aborted.
    ```
  - Confirmed `HEAD` remained unchanged at `eb307ff`.
- **Cleanup**: Unstaged and removed `HookFailureProbeTest.kt`.

---

## Test 2: Compliant Change Allowed by Hook

- **Compliant change**: Committing the 3-step pre-commit hook (`detekt` -> `lintDebug` -> `test`) and this validation report with a clean codebase and no Detekt, Android Lint, or unit test failures.
- **Expected & verified outcome**: **Allowed (PASSED validation)**
  - Exit code: `0`
  - Step 1/3 (`./gradlew detekt`): Passed (`BUILD SUCCESSFUL`).
  - Step 2/3 (`./gradlew lintDebug`): Passed (`0 errors, 41 warnings`, `BUILD SUCCESSFUL`).
  - Step 3/3 (`./gradlew test`): Passed (`BUILD SUCCESSFUL`).
  - Hook message:
    ```text
    All pre-commit checks passed!
    ```
