# Pre-Commit Hook Validation

This document records manual validation of the repository's Git pre-commit hook against failing and compliant commits.

## Hook Configuration

- **Git config**: `core.hooksPath = .githooks`
- **Hook script**: [`.githooks/pre-commit`](../.githooks/pre-commit) (mirrored in `.git/hooks/pre-commit`)
- **Checks executed on every commit**:
  1. **Step 1/2 — Android Lint**: `./gradlew lintDebug` (`abortOnError = true` in `app/build.gradle.kts`, with `NewApi` and `MissingPermission` set to `error` in `app/lint.xml`).
  2. **Step 2/2 — Unit Tests**: `./gradlew test` (JUnit 5 + JUnit 4 vintage engine).
- **Baseline `HEAD` prior to testing**: `0d12b53` (`chore: run lintDebug and unit tests in pre-commit hook`)

---

## Test 1: Failing Changes Blocked by Hook

Both gates in the pre-commit hook were tested independently with temporary changes that were reverted after verifying the hook aborted each commit.

### Test 1a: Android Lint Failure (`./gradlew lintDebug`)

- **Temporary change**: Added `app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt` accessing `Build.VERSION.RELEASE_OR_CODENAME` (API 30) without an `SDK_INT` guard while `minSdk = 26`.
- **Command**:
  ```sh
  git add app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt
  git commit -m "test: verify pre-commit hook blocks lint failure"
  ```
- **Observed outcome**: **Blocked (PASSED validation)**
  - Exit code: `1`
  - Lint error reported:
    ```text
    /opt/data/projects/droidhelp/app/src/main/java/com/bighelp/droidhelp/app/LintFailureProbe.kt:8: Error: Field requires API level 30 (current min is 26): android.os.Build.VERSION#RELEASE_OR_CODENAME [NewApi]
    1 errors, 41 warnings
    ```
  - Hook error message:
    ```text
    Pre-commit hook failed: './gradlew lintDebug' exited with status 1. Commit aborted.
    ```
  - Step 2/2 (`./gradlew test`) was not executed due to fail-fast abort.
  - Confirmed `HEAD` remained unchanged at `0d12b53`.
- **Cleanup**: Unstaged and removed `LintFailureProbe.kt`.

### Test 1b: Unit Test Failure (`./gradlew test`)

- **Temporary change**: Added `app/src/test/java/com/bighelp/droidhelp/transport/HookFailureProbeTest.kt` containing a failing assertion (`assertEquals(2, 1 + 2)`).
- **Command**:
  ```sh
  git add app/src/test/java/com/bighelp/droidhelp/transport/HookFailureProbeTest.kt
  git commit -m "test: verify pre-commit hook blocks unit test failure"
  ```
- **Observed outcome**: **Blocked (PASSED validation)**
  - Exit code: `1`
  - Step 1/2 (`./gradlew lintDebug`): Passed (`0 errors, 41 warnings`, `BUILD SUCCESSFUL`).
  - Step 2/2 (`./gradlew test`): Failed:
    ```text
    HookFailureProbeTest > deliberateFailureToVerifyPreCommitHook() FAILED
        org.opentest4j.AssertionFailedError at HookFailureProbeTest.kt:9

    165 tests completed, 1 failed
    ```
  - Hook error message:
    ```text
    Pre-commit hook failed: './gradlew test' exited with status 1. Commit aborted.
    ```
  - Confirmed `HEAD` remained unchanged at `0d12b53`.
- **Cleanup**: Unstaged and removed `HookFailureProbeTest.kt`.

---

## Test 2: Compliant Change Allowed by Hook

- **Compliant change**: Added this validation report (`docs/PRE_COMMIT_VALIDATION.md`) with a clean codebase and no lint or test violations.
- **Command**:
  ```sh
  git add docs/PRE_COMMIT_VALIDATION.md
  git commit -m "docs: add pre-commit hook validation report"
  ```
- **Observed outcome**: **Allowed (PASSED validation)**
  - Exit code: `0`
  - Step 1/2 (`./gradlew lintDebug`): Passed (`0 errors, 41 warnings`, `BUILD SUCCESSFUL`).
  - Step 2/2 (`./gradlew test`): Passed (`BUILD SUCCESSFUL`).
  - Hook message:
    ```text
    All pre-commit checks passed!
    ```
  - Commit created on `main`: `9bdc972` (`docs: add pre-commit hook validation report`).
