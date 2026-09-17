# WebDAV / Nextcloud provider setup

This document explains how to link a self-hosted WebDAV endpoint (Nextcloud,
ownCloud, or any RFC 4918 server) to Synckro, and how to run the manual
end-to-end smoke test against a dockerized Nextcloud instance before merging
changes to `app/src/main/java/com/synckro/providers/webdav/`.

---

## What the provider supports

| Operation | WebDAV request |
| --- | --- |
| `list()` / `getMetadata()` | `PROPFIND` with `Depth: 1` / `Depth: 0` |
| `download()` | `GET` |
| `uploadNew()` / `updateContent()` | `PUT` |
| `createFolder()` | `MKCOL` |
| `delete()` | `DELETE` (HTTP 404 is treated as "already gone") |
| `getStorageQuota()` | `PROPFIND` for the RFC 4331 `quota-used-bytes` / `quota-available-bytes` properties |

Item ids are the **server-absolute, percent-decoded resource paths**
(`/remote.php/dav/files/alice/Photos/a.jpg`). WebDAV exposes no rename-stable
resource id, so moving a file on the server looks like a delete of the old path
plus an add of the new one.

`D:getetag` is mapped to `RemoteFile.eTag` only. It is an opaque version tag, so
`RemoteFile.contentHash` stays `null` — WebDAV cannot supply a true content
hash, and the `CloudProvider` contract forbids substituting one for the other.

### Capability gap: no delta API

Microsoft Graph (`/delta`) and Google Drive (`changes.list`) return *changes
since a token*. WebDAV has no equivalent, which is the one real capability gap
of this provider. The fallback is a **full re-enumeration**:

* `WebDavRemoteEnumerator` walks the sync root with recursive `Depth: 1`
  `PROPFIND` requests on every run and reports each item as `MODIFY`; the diff
  stage then classifies entries against the local index, so unchanged files
  produce no operations. This is the same shape as the periodic re-enumeration
  the engine performs for delta providers
  (`SyncEngine.shouldRunPeriodicRemoteReenumeration`, every 7 days), except
  that for WebDAV it happens on *every* sync.
* `WebDavProvider.changesSince(null)` establishes a baseline without replaying
  history (empty change list), matching the Graph/Drive providers. The returned
  token is opaque and only carries the enumeration timestamp for diagnostics.

Two consequences to keep in mind:

1. **Remote deletions are not propagated incrementally.** A full listing can
   only report what exists, so deleted files are picked up when the pair is
   re-seeded (cold start without a delta token), not on the next incremental
   run.
2. **Cost scales with the size of the remote tree**, not with the number of
   changes. Prefer longer sync intervals and narrower sync roots for WebDAV
   pairs than for OneDrive/Google Drive pairs.

---

## Step 1 — Create an app password

Do **not** use your account password. On Nextcloud:

1. Open **Settings → Personal → Security**.
2. Under **Devices & sessions**, enter a name such as `Synckro (Pixel)` and
   press **Create new app password**.
3. Copy the generated password — it is shown only once.

Nextcloud can revoke a single app password without affecting your other
sessions, and it bypasses two-factor prompts, which HTTP Basic cannot satisfy.

## Step 2 — Link the account in Synckro

The account is linked with three values:

| Field | Example | Notes |
| --- | --- | --- |
| Server URL | `https://cloud.example.com` | A bare host is expanded to the Nextcloud/ownCloud files endpoint `remote.php/dav/files/<username>`. Supply the full collection URL instead if your server uses a different layout. |
| Username | `alice` | Login name. |
| Password | the app password from Step 1 | |

Credentials are validated with a `Depth: 0` `PROPFIND` against the resulting
collection URL, then persisted in `EncryptedSharedPreferences` — the same
storage pattern used by `GoogleDriveAuthManager` and `OneDriveAuthManager`. They
never leave `WebDavAuthManager` except as a ready-made HTTP Basic
`Authorization` header.

> **HTTPS is required.** Plain `http://` URLs are rejected for every host except
> `localhost` / `127.0.0.1` / `::1`, because HTTP Basic would otherwise put the
> app password on the wire in clear text. Put a TLS-terminating reverse proxy in
> front of self-hosted instances, or forward a local port over SSH when testing.

---

## Manual end-to-end smoke test

The JVM unit tests (`app/src/test/java/com/synckro/providers/webdav/`) fixture
WebDAV responses with `MockWebServer`. Run the following smoke test against a
real server before merging provider changes.

### 1. Start a Nextcloud instance

```bash
docker run --rm -d --name synckro-nextcloud -p 8080:80 \
  -e SQLITE_DATABASE=nextcloud \
  -e NEXTCLOUD_ADMIN_USER=alice \
  -e NEXTCLOUD_ADMIN_PASSWORD=synckro-dev-password \
  -e NEXTCLOUD_TRUSTED_DOMAINS=localhost \
  nextcloud:stable
```

Wait for `http://localhost:8080` to serve the login page (the first start takes
a minute or two), sign in as `alice`, and create an app password as in Step 1.

### 2. Verify the endpoint with `curl`

```bash
# List the user's root collection
curl -u alice:<app-password> -X PROPFIND -H 'Depth: 1' \
  http://localhost:8080/remote.php/dav/files/alice/

# Upload, re-read and delete a file
curl -u alice:<app-password> -T ./notes.txt \
  http://localhost:8080/remote.php/dav/files/alice/notes.txt
curl -u alice:<app-password> \
  http://localhost:8080/remote.php/dav/files/alice/notes.txt
curl -u alice:<app-password> -X DELETE \
  http://localhost:8080/remote.php/dav/files/alice/notes.txt
```

### 3. Exercise the provider from the app

Forward the container port to the device or emulator so the loopback exemption
applies and the app can reach the server:

```bash
adb reverse tcp:8080 tcp:8080
```

Then, in the app:

1. **Accounts → WebDAV / Nextcloud → Connect**, entering
   `http://localhost:8080`, `alice`, and the app password.
2. Create a sync pair against a folder on the instance and run a manual sync.
3. Verify each leg of the round trip:
   - a new local file appears on the server (`PUT`),
   - a file created on the server downloads to the device (`PROPFIND` + `GET`),
   - a new local folder appears on the server (`MKCOL`),
   - a locally deleted file disappears from the server (`DELETE`),
   - the Accounts screen shows the used/total storage (RFC 4331 quota).
4. Confirm the known gap: delete a file **on the server**, run a sync, and
   observe that the local copy survives until the pair is re-seeded.

### 4. Tear down

```bash
adb reverse --remove tcp:8080
docker rm -f synckro-nextcloud
```

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| "Server rejected the stored credentials (HTTP 401)" | Password/app password revoked, or two-factor authentication is enforced for the account password — use an app password. |
| "The server has no WebDAV collection at …" | The URL points at the instance root of a non-Nextcloud server; supply the full collection URL. |
| `HTTP 403` on listing | Some servers reject `Depth: infinity`; Synckro never sends it, so check the account's share permissions instead. |
| Link fails immediately with a URL error | Plain `http://` to a non-loopback host; use HTTPS or `adb reverse`. |
| Sync feels slow on large trees | Expected: WebDAV re-enumerates fully on every run (see the capability gap above). Narrow the sync root or increase the interval. |
