# Google Drive Sync Design

## Goal

Implement real Google Drive sync on Android, matching the iOS/TTU-compatible `ttu-reader-data` model instead of using the Android file picker as a shortcut. The first complete Android feature should let a user sign in with Google, sync reading progress for every imported book, import newer remote progress, export newer local progress, and keep the user informed when sync is skipped or fails.

This design intentionally avoids uploading EPUB files, dictionaries, fonts, and private large assets. The iOS reference syncs reading state and related sidecar data, not the book contents themselves.

## Compatibility Target

The Android implementation will use the same Google Drive layout used by the iOS reference:

- Root folder: `ttu-reader-data`
- One folder per book under the root folder
- Book folder names sanitized with the TTU filename rules
- Progress files named `progress_1_6_<lastModifiedMillis>_<progress>.json`
- Progress JSON compatible with iOS:

```json
{
  "dataId": 0,
  "exploredCharCount": 12345,
  "progress": 0.42,
  "lastBookmarkModified": 1760000000000
}
```

Android will create the root folder if it does not exist. The iOS code currently reports a missing root as an error, but creating it is better for Android first-run behavior and remains compatible with existing folders.

## Authentication

Use Google Sign-In / Credential Manager to obtain an OAuth access token with the Drive `drive.file` scope:

`https://www.googleapis.com/auth/drive.file`

The app must not commit secrets. The Android OAuth client id will be read from a Gradle `resValue` backed by `local.properties`, environment variables, or CI secrets. A placeholder string resource may exist, but runtime sign-in must show a clear configuration error if the client id is missing.

Tokens and Google account state will be wrapped behind a `GoogleDriveAuthRepository`. Access tokens are refreshed through the Google identity stack when possible; locally cached sensitive values must use Android encrypted storage rather than plain SharedPreferences.

## Drive API Layer

Add `features/sync/drive` classes:

- `GoogleDriveClient`
- `DriveFile`
- `DriveFileList`
- `DriveSyncFiles`
- `TtuProgress`
- `GoogleDriveError`

`GoogleDriveClient` will use OkHttp or `HttpURLConnection` plus kotlinx serialization. It needs methods equivalent to the iOS handler:

- `findOrCreateRootFolder()`
- `ensureBookFolder(bookTitle, rootFolderId, coverFile?)`
- `listSyncFiles(bookFolderId)`
- `downloadProgress(fileId)`
- `uploadProgress(folderId, fileId, progress)`
- `clearCache()`

The client will cache root folder id and title-to-folder id mappings in SharedPreferences. On Drive 404 responses it clears stale cache and retries once.

## Sync Manager

Add an Android `SyncManager` that coordinates local `BookStorage`, parsed `BookInfo`, and Google Drive files.

Direction rules:

- No local bookmark and no remote progress: synced/no-op
- Local bookmark only: export to Drive
- Remote progress only: import from Drive
- Both exist: compare `Bookmark.lastModified` with the remote timestamp parsed from `progress_*.json`
- Newer local: export
- Newer remote: import
- Same timestamp: synced/no-op

Importing remote progress converts `exploredCharCount` into `chapterIndex` and chapter progress using `BookInfo`. If `BookInfo` is missing, the sync is skipped with a user-visible reason.

Exporting local progress writes the exact current local character count and normalizes `Bookmark.lastModified` to millisecond precision after upload, matching the iOS behavior.

## Local Storage Changes

`BookStorage` already stores:

- `metadata.json`
- `bookmark.json`
- `bookinfo.json`

Add helpers needed by sync:

- Resolve character count to chapter/progress from `BookInfo`
- Read cover file for optional upload
- Load/save sync settings

`Bookmark.lastModified` currently uses Apple reference seconds. For Google Drive file names and TTU JSON, convert to Unix milliseconds at the sync boundary. Do not change the on-disk bookmark format unless unavoidable.

## UI

Add a real `Google Drive Sync` settings page under Settings.

The page includes:

- Sign in button
- Signed-in account display
- Sign out button
- Sync enabled toggle
- Auto-sync on open toggle
- Auto-sync after bookmark save toggle
- Manual sync all books action
- Last sync status list or compact status row

Reader behavior:

- On open, if enabled, sync the current book before showing or soon after showing the reader.
- On bookmark save, if enabled, debounce export so page turns do not spam Drive.
- Manual sync remains available even if auto-sync is off.

Bookshelf behavior:

- Show sync errors through the existing error message surface or a lightweight dialog/snackbar.
- Do not block local reading if Drive auth or network fails.

## Error Handling

User-facing errors should be short and actionable:

- Missing Google client id
- Sign-in cancelled
- Not signed in
- Network unavailable
- Google Drive permission denied
- Remote folder/file not found after retry
- Local book info missing
- Invalid remote progress JSON

Sync must fail per book, not globally, during "sync all". A failed book should not prevent later books from syncing.

## Testing

Unit tests:

- TTU filename sanitization
- Progress file timestamp parsing
- Sync direction selection
- Bookmark to TTU progress export mapping
- TTU progress to local bookmark import mapping
- Stale Drive cache retry behavior using fake client
- Missing BookInfo skip behavior

Integration-ish JVM tests:

- Fake Drive client with in-memory folders/files
- Fake BookStorage root with sample `metadata.json`, `bookmark.json`, and `bookinfo.json`
- Manual sync all with mixed import/export/no-op/failure results

Emulator tests:

- Settings page renders Google Drive Sync page
- Missing client id shows configuration error without crashing
- Manual sync button is disabled or reports not signed in when auth is absent

Real Google Drive E2E cannot run in public CI without secrets. Keep it documented as a manual verification path using a debug OAuth client id.

## Build And Configuration

Add Gradle configuration for the OAuth client id:

- `HOSHI_GOOGLE_CLIENT_ID` environment variable
- `googleClientId` in `local.properties`
- generated string resource `google_client_id`

CI should run without a real client id. Tests must not require network or Google credentials.

## Rollout

Implementation should be split into reviewable PRs:

1. Sync models, local mapping helpers, and unit tests
2. Google Drive auth/config plumbing and settings UI
3. Drive REST client with fake-client tests
4. Sync manager and reader/bookshelf integration
5. Emulator validation and documentation

Each PR should compile and keep baseline checks passing. The final feature is not considered complete until manual emulator validation confirms the settings page, missing-client-id path, local fake sync tests, and reader save/open sync hooks.
