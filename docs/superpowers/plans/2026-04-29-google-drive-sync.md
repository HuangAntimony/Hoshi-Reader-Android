# Google Drive Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build real Google Drive reading progress sync for Android using the iOS/TTU-compatible `ttu-reader-data` layout.

**Architecture:** Add a focused sync package with pure models/mapping helpers, a Drive REST client, an auth repository backed by Google Sign-In, and a `SyncManager` that coordinates `BookStorage`. Compose UI exposes sign-in/settings/manual sync, while reader hooks perform import-on-open and debounced export-on-bookmark-save.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, Google Play services auth (`play-services-auth`), `HttpURLConnection`, JUnit, Android emulator validation.

---

### Task 1: Pure Sync Models And Mapping Helpers

**Files:**
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/DriveSyncModels.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/DriveSyncModelsTest.kt`
- Modify: `app/src/main/java/moe/antimony/hoshi/epub/BookStorage.kt`

- [ ] Write tests for TTU filename sanitization, progress timestamp parsing, Apple-reference seconds to Unix milliseconds conversion, and character-position resolution.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests moe.antimony.hoshi.features.sync.DriveSyncModelsTest --console=plain` and verify the tests fail because the new sync helpers do not exist.
- [ ] Implement `TtuProgress`, `DriveFile`, `DriveSyncFiles`, `SyncDirection`, `SyncResult`, `sanitizeTtuFilename`, `parseProgressTimestampMillis`, `appleReferenceSecondsToUnixMillis`, `unixMillisToAppleReferenceSeconds`, and `BookInfo.resolveCharacterPosition`.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive sync models`.

### Task 2: Sync Settings Store

**Files:**
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/DriveSyncSettings.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/DriveSyncSettingsTest.kt`

- [ ] Write tests that defaults are disabled, toggles serialize with kotlinx serialization, and missing/corrupt JSON returns defaults.
- [ ] Run the targeted test and verify it fails.
- [ ] Implement `DriveSyncSettings` and a file-backed serializer helper used by Android UI.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive sync settings`.

### Task 3: Drive REST Client With Fake Transport

**Files:**
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/GoogleDriveClient.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/GoogleDriveClientTest.kt`

- [ ] Write tests with a fake transport for root folder creation, book folder reuse, progress upload create/update, multipart metadata, and one retry after stale 404 cache.
- [ ] Run the targeted test and verify it fails.
- [ ] Implement `DriveHttpTransport`, `GoogleDriveClient`, request encoding, response decoding, multipart upload, folder-id cache abstraction, and Drive error mapping.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive REST client`.

### Task 4: Auth And Build Configuration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/GoogleDriveAuthRepository.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/GoogleDriveAuthRepositoryTest.kt`

- [ ] Write tests for missing client id, configured client id validation, and signed-out state.
- [ ] Run the targeted test and verify it fails.
- [ ] Add `play-services-auth`, generate `google_client_id` from `HOSHI_GOOGLE_CLIENT_ID` or `local.properties`, and implement `GoogleDriveAuthRepository` around Google Sign-In access token requests for the Drive scope.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive auth configuration`.

### Task 5: Sync Manager

**Files:**
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/DriveSyncManager.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/DriveSyncManagerTest.kt`

- [ ] Write tests for local-only export, remote-only import, newer-local export, newer-remote import, equal timestamp no-op, missing `BookInfo` skip, and per-book failure isolation in sync-all.
- [ ] Run the targeted test and verify it fails.
- [ ] Implement `DriveSyncManager` with injected auth/client/storage, local-to-remote mapping, remote-to-local mapping, stale cache retry, and sync-all result aggregation.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive sync manager`.

### Task 6: Settings UI

**Files:**
- Modify: `app/src/main/java/moe/antimony/hoshi/features/bookshelf/MainShellUi.kt`
- Modify: `app/src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt`
- Create: `app/src/main/java/moe/antimony/hoshi/features/sync/GoogleDriveSyncView.kt`
- Modify: `app/src/test/java/moe/antimony/hoshi/features/bookshelf/MainShellUiTest.kt`

- [ ] Write tests that the Settings groups include `Google Drive Sync` and that destination titles are stable.
- [ ] Run the targeted test and verify it fails.
- [ ] Add a Settings entry and Compose screen with Sign in, Sign out, enable toggle, auto-sync toggles, manual sync-all action, account/status text, and missing-client-id error.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Add Google Drive sync settings UI`.

### Task 7: Reader And Bookshelf Integration

**Files:**
- Modify: `app/src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt`
- Modify: `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt`
- Create: `app/src/test/java/moe/antimony/hoshi/features/sync/DriveSyncIntegrationTest.kt`

- [ ] Write tests around the non-Compose integration helpers for auto-sync-on-open and debounced export-on-bookmark-save decisions.
- [ ] Run the targeted test and verify it fails.
- [ ] Wire `DriveSyncManager` into bookshelf state, sync on reader open when enabled, sync after bookmark save when enabled, and expose manual sync-all from the settings screen.
- [ ] Re-run the targeted test and verify it passes.
- [ ] Commit as `Wire Google Drive sync into reader`.

### Task 8: Verification, Docs, And PR

**Files:**
- Modify: `docs/TODO.md`
- Modify: `docs/superpowers/specs/2026-04-29-google-drive-sync-design.md` if behavior changed during implementation

- [ ] Run `./gradlew.bat check --console=plain --stacktrace`.
- [ ] Run `./gradlew.bat assembleDebug --console=plain --stacktrace`.
- [ ] Install on emulator and verify Settings -> Google Drive Sync renders, missing client id is reported cleanly, and the app does not crash.
- [ ] Commit docs updates as `Document Google Drive sync setup`.
- [ ] Push branch to fork and open a draft PR to the original repository.

## Self-Review

This plan covers the design sections: compatibility layout, auth/config, Drive API, sync manager, local storage mapping, UI, error handling, tests, and rollout. It avoids committing OAuth secrets, keeps CI credential-free, and requires tests before production code for each behavior-bearing task.
