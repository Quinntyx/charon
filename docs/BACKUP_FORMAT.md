# Local backup subsystem

Charon backups are created and opened only after the user chooses a document through Android's Storage Access Framework. The app does not schedule backups, request `INTERNET`, or opt into Android automatic backup.

## Version 1 archive

A backup is a ZIP containing:

- `manifest.json`: format name, version, creation time, and SHA-256/size metadata.
- `data.json`: logical records identified by `(collection, stableId)` with JSON object payloads.
- `receipts/NNNNN.bin`: original receipt bytes, with stable ID and MIME type in the manifest.

Entry order is not significant. Export writes `data.json` and each receipt directly to the ZIP, then writes the completed manifest. Receipt sources are opened once and copied in bounded chunks; callers should construct `BackupReceipt` with a declared size and an `InputStream` opener rather than loading files into byte arrays.

Restore uses two document passes. The first pass validates paths, counts, size limits, checksums, the manifest, and logical records while retaining only bounded manifest/data metadata. Receipt bodies are hashed and discarded rather than accumulated. After complete validation, the coordinator opens a restore transaction and reopens the selected document. The second pass streams each receipt to staging storage and verifies every entry against the first pass, so a changed document causes rollback. The manifest and data entries have a separate 8 MiB default memory bound; receipt and total archive limits remain independently configurable.

Validation rejects unsupported versions, missing or unexpected entries, duplicate record/receipt identities, checksum or size mismatches, unsafe paths, a document changed between passes, and configured decompressed-size/count limits.

## Persistence integration contract

The persistence owner supplies a `BackupSnapshot` for export and implements `TransactionalRestoreTarget`. Its `RestoreSession` must stage both database changes and receipt files so that:

1. `applyData` honors `KEEP_EXISTING` or `REPLACE_EXISTING` by stable identity.
2. `applyReceipt` copies its stream to staging storage before returning; the stream is not valid afterward.
3. `commit` makes the staged database and receipt set visible together.
4. `rollback` restores the pre-restore database and receipt set after any failed apply, second-pass validation, or commit.

The document opener passed to `BackupRestoreCoordinator` must return a fresh stream for each call. Android's `BackupDocumentService` does this by reopening the user-selected content URI. `BackupDocumentPanel` remains a minimal reusable Compose surface for export, duplicate-policy selection, and restore.
