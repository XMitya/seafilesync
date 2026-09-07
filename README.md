# Seafile Sync

An Android client that synchronises Seafile libraries the way the desktop client does: chosen
libraries are mirrored into a folder on the device, changes travel in both directions, and syncing
continues in the background with the app closed.

The official mobile app does not do this. It is a file browser with one-way backup features, and
its folder backup compares files by name and uploads each one exactly once, so a file whose
contents change is never re-uploaded and deletions and renames are never propagated.

## What it does

- Connects one Seafile account and mirrors selected libraries into a folder you choose
- Two-way sync over the real block protocol, so only changed blocks are transferred
- Conflicts keep both versions, using the same naming Seafile itself uses
- Runs in a foreground service with a notification showing what is transferring
- Survives being killed, Doze, and reboots
- A bounded log on disk, shareable from Settings, because the failures worth reading happen in a
  background service hours before anyone looks and logcat has long since overwritten them
- Encrypted libraries, decrypted on the device. The password is verified locally against the
  magic the server publishes and never sent anywhere, so the server stores only ciphertext. The
  official app posts the password to the server and receives plaintext, which means the server
  can read the library.

- Content-defined chunking, so an edit only re-uploads the blocks around it rather than
  everything after it. Boundaries match the desktop client's, which means blocks it already
  uploaded are reused rather than duplicated.

## What it does not do yet

- More than one account.

## Building

```
./gradlew :app:assembleDebug
```

Requires an Android SDK with platform 37. The Gradle daemon runs on Java 25 and the vendor is
pinned to Adoptium: AGP's JdkImageTransform runs `jlink` against the Android platform's stripped
`java.base`, and GraalVM's `jlink` fails there.

## Testing

```
./gradlew :app:testDebugUnitTest          # engine, protocol, parsers
./gradlew :app:connectedDebugAndroidTest  # service, keystore, UI
```

The unit tests run against responses captured from a real Seafile 11 server, in
`app/src/test/resources/fixtures/`. The object-id tests in particular check that ids computed here
match the ones that server assigned, because an id computed differently produces a library nobody
else can read and reports no error while doing it.

Note that `connectedDebugAndroidTest` uninstalls the app afterwards, taking its data and its
Keystore key with it. Run it before manual testing, not between steps.

## When something goes wrong

Settings has the log: how big it is, a button to share it, and one to clear it. It is capped at
two files of 512 KB, so it cannot grow into the storage it is meant to be syncing into.

Worth knowing about one entry in particular. Seafile keeps a single token per (user, platform,
device id), so signing in again from anywhere with the same device id invalidates this device's
token. The app then returns to the sign-in screen, which is indistinguishable from a deliberate
sign-out unless the log is read -- so that case is logged explicitly.

## Talking to a server by hand

`scripts/dev-server.sh` wraps curl for poking a server during development. It bypasses any
configured HTTP proxy, because a proxy that cannot reach the Seafile host fails as a plain timeout
that is easy to misread, and it handles both token kinds: the account token for `/api2` and the
per-library sync token for `/seafhttp`.

```
scripts/dev-server.sh repos
scripts/dev-server.sh api "/api2/repos/<id>/dir/?p=/"
scripts/dev-server.sh seafhttp <id> "/repo/<id>/commit/HEAD"
```

Credentials go in a git-ignored `scripts/.env.local`:

```
SEAFILE_URL=https://seafile.example.com
SEAFILE_USER=you@example.com
SEAFILE_PASSWORD='...'
```

One caution: logging in there with the same `device_id` the app uses invalidates the app's token,
because Seafile keeps one token per (user, platform, device id). The script logs in without device
fields, which avoids the collision; passing them by hand does not.

## Things worth knowing about the protocol

There is no official specification for Seafile's sync protocol, so these were established from the
server's source and confirmed against a running server.

- An fs object's id is the SHA-1 of its JSON serialised with sorted keys and `", "` / `": "`
  separators. Anything else produces ids the server disagrees with.
- `modifier` and `size` appear on a directory entry only for regular files. Adding them to a
  subdirectory entry changes the parent directory's id.
- Directory entries are stored in descending name order compared bytewise over UTF-8, which is not
  Kotlin's natural string order above the BMP.
- `permission-check`, `fs-id-list` and `quota-check` return 404 without a trailing slash once a
  query string follows, although the routes declare the slash as optional.
- The server merges concurrent commits itself, so publishing a head does not fail on a race; the
  resulting head may differ from the one that was published and has to be read back.
- Commit objects require `creator` to be exactly 40 characters, including the all-zero id a client
  writes, so a serialiser that omits default values silently produces rejected commits.
- `download-info` returns `"encrypted": ""` for a plain library and `"encrypted": 1` for an
  encrypted one: an empty string in one case, an integer in the other, from the same field.
- A newly created library has an all-zero root id, meaning "empty directory" rather than a stored
  object. Asking `pack-fs` for it answers 500, so an empty library fails to sync at all unless
  that id is special-cased -- and an empty library is what a user has just after creating one.
- Encrypted libraries hash the ciphertext, not the file: a block id is the SHA-1 of what is
  stored. So encryption happens before hashing on the way out and verification before decryption
  on the way in. Chunk boundaries still come from the plaintext, so encrypted libraries
  deduplicate as well as plain ones do.
- The chunking fingerprint is carried as a 32-bit value while its table arithmetic is 64-bit, so
  rolling the window and computing it outright give different answers. That is load-bearing: the
  chunker seeds each block with the direct computation and rolls from there, and "fixing" the
  discrepancy moves every boundary.
