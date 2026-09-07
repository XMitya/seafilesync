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

## What it does not do yet

- **Encrypted libraries.** They are listed but cannot be synced. Doing this properly means
  decrypting on the device; doing it the way the official app does would mean sending the library
  password to the server, which defeats the point of encrypting it.
- Content-defined chunking. Blocks are fixed-size, which is correct on the wire but does not
  deduplicate against blocks the desktop client cut differently.
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
