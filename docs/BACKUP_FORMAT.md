# Local backup subsystem

Charon backups are created and opened only after the user chooses a document through Android's Storage Access Framework. The app does not schedule backups, request `INTERNET`, or opt into Android automatic backup.

## Version 1 archive

A backup is a ZIP containing:

- `manifest.json`: format name, version, creation time, and SHA-256/size metadata.
- `data.json`: logical records identified by `(collection, stableId)` with JSON object payloads.
- `receipts/NNNNN.bin`: original receipt bytes, with stable ID and MIME type in the manifest.

Restore validates the entire archive before opening a restore transaction. Validation rejects unsupported versions, missing or unexpected entries, duplicate record/receipt identities, checksum or size mismatches, unsafe paths, and configured decompressed-size/count limits.

## Persistence integration contract

The persistence owner supplies a `BackupSnapshot` for export and implements `TransactionalRestoreTarget`. Its `RestoreSession` must stage both database changes and receipt files so that:

1. `apply` honors `KEEP_EXISTING` or `REPLACE_EXISTING` by stable identity.
2. `commit` makes the staged database and receipt set visible together.
3. `rollback` restores the pre-restore database and receipt set after any failed `apply` or `commit`.

`BackupRestoreCoordinator` parses and validates first, then invokes that transaction contract. `BackupDocumentPanel` is a minimal reusable Compose surface for export, duplicate-policy selection, and restore.
