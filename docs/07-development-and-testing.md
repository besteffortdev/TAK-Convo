# 07 — Development and testing

## Setup

| Need | Notes |
|---|---|
| JDK 17 or newer | runs Gradle and javac (`JAVA_HOME`); AGP 8.13 / Gradle 8.14.3. The code is Java 17 because TAK.gov's pipeline only has JDK 17 (see "Release builds" below) |
| Android SDK | platform 36, build-tools 35 (`dexdump`, `aapt2` are handy) |
| ATAK SDK 5.6 and 5.8 | the ATAK-CAN 5.6.0.24 and 5.8.0.5 SDKs: `main.jar`, `atak-gradle-takdev.jar`, the developer `atak.apk`, the keystore |
| A device with that version's **developer** `atak.apk` | release ATAK refuses plugins not signed by TAK.gov, see below |

The plugin is built for ATAK **5.6** and **5.8**, one APK per version, each against its own
SDK. `local.properties` (not committed) names them:

```properties
sdk.dir=<Android SDK>
atak.sdk.5.6=C:\\...\\ATAK-CAN-5.6.0.24-SDK
atak.sdk.5.8=C:\\...\\ATAK-CAN-5.8.0.5-SDK
# takrepo.url / takrepo.user / takrepo.password switch to tak.gov's Maven repository instead
```

The ATAK version is `atakVersion` in `gradle.properties` (5.8.0), or `-PatakVersion`. The root
`build.gradle` picks that version's SDK and sets `sdk.path` for the takdev plugin, and the
takdev plugin is that SDK's `atak-gradle-takdev.jar`. With `takrepo.url` (TAK.gov's pipeline)
no local SDK is needed: takdev comes from that Maven repository and downloads the SDK. Don't set `sdk.path` in
`local.properties`: takdev reads it from there before the build's value, and the build stops
with a message if it finds it. `gradle/atak-runtime.gradle` has the versions of the libraries
each ATAK version ships (see [06](06-atak-runtime-and-classloading.md)).

Write Gradle files without a byte-order mark: PowerShell's `Set-Content -Encoding utf8` adds
one, and `settings.gradle` then fails to parse.

## Build

```bash
./gradlew assembleCivDebug --offline                       # ATAK 5.8 (gradle.properties)
./gradlew assembleCivDebug --offline -PatakVersion=5.6.0   # ATAK 5.6
# app/build/outputs/apk/civ/debug/ATAK-Plugin-takconvo-<version>-<git>-<atak version>-civ-debug.apk
java tools/AtakLinkCheck.java <atak.sdk.5.x>/atak.apk app/build/outputs/apk/civ/debug/<apk>
```

In PowerShell, quote the property: `.\gradlew.bat assembleCivDebug --offline
"-PatakVersion=5.6.0"`. Unquoted, PowerShell splits the argument at the first dot and Gradle
looks for a task named `.6.0`. Each build replaces the APK of the other version in the output
directory. The first build for a version, or after a library change, has to run without
`--offline` so that Gradle can download the libraries.

The two APKs differ only in the `plugin-api` they declare (`com.atakmap.app@5.6.0.CIV`,
`...@5.8.0.CIV`) and in the library versions they were compiled against. Both link-check with
no finding against their SDK's `atak.apk`. The developer ATAKs of these SDKs, unlike the Play
Store builds, aren't obfuscated.

`AtakLinkCheck` must report no finding before an APK goes on a device (see
[06](06-atak-runtime-and-classloading.md)).

### Developer ATAK on a test device, and release ATAK

The plugin loads in the SDK's **developer** ATAK (build type `sdk`). The developer `atak.apk`
of the 5.5.1.8, 5.6.0.24 and 5.8.0.5 SDKs are all signed with the same SDK key
(`O=WinTec Arrowmaker`), so one installs over another with `adb install -r`, keeping ATAK's
data and the plugin's (account, OMEMO keys, history). Mind the version codes: 5.6.0.24's
(1789391081) is higher than 5.8.0.5's (1789342433), so going from 5.6 to 5.8 is a downgrade
(`-r -d`). Since 4.10, ATAK also accepts a plugin built for an older API than its own.

A **release** ATAK (Play Store, or an organisation's loadout) won't load the plugin:

- **Signature.** `AtakPluginRegistry.verifySignature` only accepts plugins signed with ATAK's
  own key, a TAK.gov key (`ACCEPTABLE_KEY_LIST`) or an App Transparency signature. It logs
  `signature mismatch[com.atakmap.android.takconvo.plugin]`, and no setting bypasses it.
- **Obfuscation.** Release ATAK is ProGuard-obfuscated. Checked against ATAK-CAN 5.8.0.5
  (`civSmall-release`, `[playstore]`), the 5.8 build refers to 7 ATAK methods that were
  renamed (`AtakBroadcast.getInstance()` is `a()`, and `registerReceiver`, `sendBroadcast`,
  `CotMapComponent.getInstance`...). It also refers to 2 methods of OkHttp's `RequestBody`,
  which ATAK 5.8 bundles, with Okio renamed (`okio.ByteString` is `atak.core.e2`). ATAK's copy
  shadows the plugin's.

A release build for a given ATAK has to apply that release's ProGuard mapping
(`-applymapping`), which the 5.6 and 5.8 SDKs don't include (their `mapping.txt` is empty), and
moves the plugin's classes, OkHttp included, into `atakplugin.takconvo` (`-repackageclasses`).
It then has to be signed by TAK.gov: its Third Party Pipeline does both (next section). Until
then, a test device needs the developer ATAK; installing it over a release ATAK takes an
uninstall first (another signing key), and ATAK's app data goes with it (`/sdcard/atak` stays).

### Release builds and TAK.gov's Third Party Pipeline

TAK.gov's Third Party Pipeline (TPP, tak.gov › Resources › Third Party Pipeline) builds a
plugin from its source and signs it, so that release ATAK loads it. Its requirements, and how
this repository meets them:

| TPP requirement | Here |
|---|---|
| a zip with one root folder, whose name the APKs get | `tools/tpp-package.sh` |
| Gradle, with its scripts and wrapper | `gradlew` (executable, LF), `gradle/wrapper/` |
| an `assembleCivRelease` target | the `civ` flavor's release build |
| the SDK through `atak-gradle-takdev` from TAK.gov's Maven | `takdev 3.+` (the 5.x template's; the page's "2.+" is older), resolved from `takrepo.url` |
| `-repackageclasses` naming the plugin | `atakplugin.takconvo`, written by `app/build.gradle` |
| the `com.atakmap.app.component` activity in the manifest | `app/src/main/AndroidManifest.xml` |
| its build machine: JDK 17 (FAQ), and no internet beyond the Maven repositories | Java 17 source and target everywhere, no Gradle toolchain |

```bash
tools/tpp-package.sh                  # build/tpp/takconvo-atak56.zip, build/tpp/takconvo-atak58.zip
tools/tpp-package.sh 5.8.0            # one version
```

Each zip holds the working tree as it is, from a temporary git index, so nothing is staged.
Commit first: uncommitted changes make the version `<commit>-wip`. Each zip:
- sets `atakVersion` (the TPP builds the default);
- writes `takVersionName` (the commit) and `takStaticVersion` (the packaging time, as version
  code), because the archive has no `.git` for takdev to read them from;
- leaves out `docs/` (except `docs/user_manual/`), `tools/`, `provisioning/`, `README.md` and
  `template.local.properties`: the build doesn't need them, and they name internal hosts. The
  TPP sets `ATAK_CI=1`, which makes `gradle/typst.gradle` compile `docs/user_manual/usermanual.typ`
  into `assets/usermanual.pdf`; the manual uses no Typst package, so nothing is downloaded then;
- keeps files byte for byte (`core.autocrlf=false`): with Windows line endings `gradlew` breaks
  on the TPP's Linux.

Upload each zip on the TPP page. It builds `assembleCivRelease` for that ATAK version and
returns the signed APK, which declares `com.atakmap.app@5.6.0.CIV` or `...@5.8.0.CIV`.

**Checked locally**, on the extracted zips with JDK 17, no `.git`, and a `local.properties`
with only `sdk.dir` (takdev needs the file; the TPP writes its own):
- `assembleCivRelease` succeeds for both versions, with the SDK given as
  `-Patak.sdk.5.x=<dir>`;
- the APK's version is `0.1 (<commit>) - [5.x.0]`, with the static version code;
- with the TPP's flags (`-Ptakrepo.force=true -Ptakrepo.url=https://artifacts.tak.gov/artifactory/maven
  -Ptakrepo.user=... -Ptakrepo.password=...`), the build goes straight to TAK.gov's Maven for
  takdev.

That last step is as far as a local check goes: artifacts.tak.gov is reserved for US
government accounts, so the TPP's own pre-check command can't be run here.

**What the TPP returns.** One folder per submission: `build.log`, the Fortify scan
(`fortify_scan_results.pdf`, `scan_results.fpr`, logs) and an OWASP dependency check
(`dependency-check-report.html`). Fortify scans the source even when the build fails. The
`.fpr` is a zip; its `audit.fvdl` (XML) lists each finding with its trace. The PDF's priorities
(Critical, High, ...) come from each rule's impact and likelihood.

**First submission (2026-10-02), both versions:**
- **The build failed**: Gradle's toolchain asked for a JDK 21, the machine has only 17, and its
  proxy refuses the download (`api.foojay.io`: `ERR_ACCESS_DENIED`). Gradle itself
  (services.gradle.org) and TAK.gov's Maven were reachable. Fixed by building as Java 17: no
  toolchain, and the fork's Java 21 constructs rewritten
  ([05](05-conversations-fork.md#k-java-17-for-takgovs-pipeline)).
- **Fortify: 4 critical**, all "Insecure SSL: Server Identity Verification Disabled", in
  upstream code that does verify the server, with its own verifiers, which Fortify doesn't
  recognize. The fork now makes those checks through `HostnameVerifier.verify`
  ([05](05-conversations-fork.md#l-server-identity-checks-takgovs-scan-recognizes)). One
  path stays unverified by design: DNS over TLS to the network's DNS server when Android's
  Private DNS is "Automatic", as Android itself does it (`de.gultsch.minidns.DNSSocket`).
- Fortify's 38 high findings are all in upstream code: "Privacy Violation" (the password and
  messages reach the XMPP socket or files, which is what a client does), "Hardcoded Password"
  (the database column name `"password"`) and "Empty Password" (a group chat without one). The
  23 low ones include "Password in Comment" in the plugin's code (comments about the password
  setting).
- The dependency check only saw the Gradle wrapper and TAK's own `takdevlint.aar` (a false
  match on Apache SkyWalking's CPE), since the build stopped before resolving the libraries.

**Second submission (9b14a22, 2026-10-02), both versions:**
- The Java compilation passed. **The build failed** at `:app:runTypst` ("Cannot access first()
  element from an empty Iterable"): the zip had no `docs/user_manual/`. Fixed in
  `tools/tpp-package.sh`; the manual is now TAK Convo's own.
- **Fortify: no critical** (the 4 are gone, DNSSocket's included), the same 38 high, 28 low (5
  new "Password in Comment" in the generated `ImmutableBookmark`, scanned now that the build
  got further).
- The dependency check: still only `takdevlint.aar` (MEDIUM, false match).

**What the release build needed:**
- R8 needs every class the plugin refers to, either packaged or as a library: ATAK's classes
  and the libraries it ships come from the SDK's `main.jar`. `androidx.concurrent` and
  `androidx.tracing`, which ATAK has but `main.jar` lacks, are packaged
  ([06](06-atak-runtime-and-classloading.md)).
- `app/proguard-gradle.txt` (user section) has `-dontwarn` rules for `androidx.window`'s device
  extensions and JNDI (`javax.naming`), which exist on no Android device.
- The TPP applies release ATAK's mapping to everything in `main.jar`, OkHttp and Okio included,
  which release ATAK renames (see above).

## Install and run

```powershell
tools\deploy.ps1 -Serial <adb serial> [-AtakVersion 5.8.0|5.6.0] [-NoBuild] [-SkipLinkCheck]
```

builds for that ATAK version (default 5.8.0), link-checks against its SDK's `atak.apk`,
installs, and restarts ATAK with the plugin loaded. It refuses a device whose ATAK is another
version. It exists because of two ATAK behaviours, both handled by editing ATAK's preferences
while ATAK is stopped (`run-as com.atakmap.app.civ`, a debuggable developer build):

- **`adb install -r` of a plugin makes ATAK clear `shouldLoad-<plugin package>`** (Android 16),
  so the reinstalled plugin stays unloaded. The script sets it back to `true`.
- **`pluginSafeMode`**: ATAK sets it when it starts loading plugins and clears it 10 s later or
  on a clean exit. Stopped inside those 10 s, ATAK asks "exited uncleanly... load the plugins
  anyway?" on the next start. The script sets it to `false`. (Revoking a permission with
  `pm revoke` also kills ATAK and causes the prompt.)

The first time a plugin is sideloaded, ATAK's **Plugins** screen needs a refresh: it lists
sideloaded plugins from a cached `/sdcard/atak/support/apks/sideloaded/product.inf`.

## Provisioning a test device

- Tool preferences: Settings › Tool Preferences › TAK Convo, then **Import .pref file**.
- From `adb`: push the file, then import it. ATAK empties `/sdcard/atak/tmp` at start-up, so
  push it **after** ATAK is running:
  ```bash
  adb push provisioning/takconvo-template.pref /sdcard/atak/tmp/
  adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_IMPORT_PREF \
      --es path /sdcard/atak/tmp/takconvo-template.pref
  ```
- `provisioning/` has a template and two test files: `takconvo-local-test.pref` and
  `takconvo-emulator-relay.pref` (emulator through the relay below). They use example host
  names: put in your own server's.
- What a `.pref` set, on a developer ATAK (debuggable, so `run-as` works):
  ```bash
  P="run-as com.atakmap.app.civ cat shared_prefs"
  adb shell $P/com.atakmap.app.civ_preferences.xml | grep takconvo_   # ATAK's: the keys as imported
  adb shell $P/takconvo_com.atakmap.app.civ_preferences.xml           # Conversations' settings
  adb shell $P/takconvo_room_nicknames.xml                            # rooms where the callsign is taken
  ```
  `takconvo_xmpp_password` must be gone from ATAK's file within a second of the import.

## Debug broadcasts

Debug builds register `DebugReceiver`. It is exported, but senders need
`android.permission.DUMP`: adb's shell has it, other apps can't get it. A broadcast from an
app's uid (`adb shell run-as com.atakmap.app.civ am broadcast --user 0 ...`) isn't
delivered.

| Action (`com.atakmap.android.takconvo.` + ...) | Extras | Does |
|---|---|---|
| `DEBUG_SET_PREF` | `key`, `value` | sets an ATAK preference (string); removes it without `value` |
| `DEBUG_IMPORT_PREF` | `path` | imports a `.pref` file with ATAK's importer |
| `DEBUG_PROVISION` | | runs `XmppEngine.provision()`: `TakConvo.Trust` logs the trust sources from the `TakConvo.Provision` thread, `TakConvo.XmppEngine` "provisioning ..." from the main thread |
| `DEBUG_ADD_TAK_SERVER` | `connect` (`host:port:ssl`), `user`, `pass` | adds a TAK server connection the way ATAK's dialog does |
| `DEBUG_DUMP_SELF_SA` | | logs the SA this device sends (check `xmppUsername`) |
| `DEBUG_SEND` | `to`, `body` | sends a plain-text 1:1 message |
| `DEBUG_SHOW_ACCOUNT` | | opens the account pane |
| `DEBUG_OPEN_SETTINGS` | | opens the tool preferences |
| `DEBUG_SHOW_CHAT` | | opens the chat pane |
| `DEBUG_CHAT_BACK` | | presses back in the chat pane |
| `DEBUG_FAKE_CONTACT` | `jid` (default: own), `callsign` | injects the SA of a TAK user advertising `jid`, into this ATAK only |
| `DEBUG_REMOVE_FAKE_CONTACT` | | removes it |
| `DEBUG_OPEN_CONTACT` | | does what tapping its XMPP connector does |
| `DEBUG_DUMP_CONTACT` | | logs its unread counts, XMPP presence and default connector, and whether its address is on the map (Show on map) with the time that took |
| `DEBUG_FAKE_INCOMING` | `from` (default: own), `body` | stores and notifies a message as if received (nothing is sent) |
| `DEBUG_ATAK_BROADCAST` | `action`, `extra.<key>` (strings) | sends an ATAK-internal broadcast with those extras, e.g. `com.atakmap.android.contact.CONTACT_LIST` opens Contacts |
| `DEBUG_SEND_MAP_ITEM` | `uid` (a map item's) | sends to the self chat what a group chat gets from ATAK's send list: a line naming the item, and a data package of it ([10](10-atak-map-integration.md#map-items-to-a-group-chat)) |
| `DEBUG_SEND_TO_CONTACT` | `uid` (a contact's), `extra.<key>` | does what ATAK's send list does for a contact whose IP connector names a broadcast (a group chat's `takconvo.room:<address>`); without extras nothing is sent, `GroupChatSends` only warns |
| `DEBUG_SEND_FILE` | `path` | sends a file to the self chat, as ATAK's Send dialog would |
| `DEBUG_CLEAR_CONTENT` | | does to TAK Convo what ATAK's Clear Content does, without clearing ATAK: stops the plugin and deletes its data, keys and XMPP login ([02](02-embedded-engine.md#ataks-clear-content)). Back up first (see the checklist) |

```bash
adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_CHAT
```

`adb shell` splits extras on spaces, even quoted: use values without spaces.

## Logs

| Tag | From |
|---|---|
| `TakConvo.Plugin`, `.XmppEngine`, `.Nicknames`, `.Trust`, `.Settings`, `.Host`, `.Contacts`, `.PendingIntents`, `.Notifications`, `.Debug` | the plugin (`.Settings`: also rejected `takconvo_conversations_*` values) |
| `tak convo` | Conversations (its `Config.LOGTAG` is the app name) |
| `AndroidRuntime` | crashes; `adb logcat -b crash` keeps them after the main buffer rolls |

```bash
adb logcat -s TakConvo.Plugin TakConvo.XmppEngine TakConvo.Host "tak convo"
```

`TakConvo.Host` logs each embedded activity's creation, resume, pause and stop, and every
permission request. Logs that name users (addresses, callsigns, rooms, the `status:` line
that `deploy.ps1` waits for) go through `SensitiveLog` and appear in debug builds only; message
text is never logged ([09](09-code-guidelines.md#logging)). `ResourcesCompat` "Failed to inflate ColorStateList" warnings are harmless
(ATAK's and the plugin's `R` classes clash on a few AndroidX ids).

## Driving the UI from a PC

```powershell
adb shell screencap -p /sdcard/shot.png ; adb pull /sdcard/shot.png .   # not `> shot.png` in PowerShell: it corrupts the PNG
adb shell uiautomator dump /sdcard/ui.xml ; adb pull /sdcard/ui.xml .  # view bounds to tap
adb shell input tap X Y ; adb shell input text "hello%sworld" ; adb shell input keyevent 4
```

In Git Bash, `MSYS_NO_PATHCONV=1` or PowerShell is needed for commands with `/sdcard/...`
arguments: MSYS rewrites them into Windows paths.

Send test messages to your **own** JID ("note to self"): it exercises OMEMO, upload and
delivery without writing to anyone else.

## An emulator and a server only the phone can reach

When the XMPP server is only reachable from the phone's network, relay through the phone:

```bash
adb -s <phone> shell "nc -L -p 15222 nc -w 10 <xmpp-host> 5222"   # keep running
adb -s <phone> forward tcp:15222 tcp:15222
# emulator: host 10.0.2.2, port 15222 (provisioning/takconvo-emulator-relay.pref)
```

The emulator then connects to `10.0.2.2:15222`, which reaches the phone and the server. The
certificate still names the real server, so trust works unchanged.

## Device test checklist

After an upstream merge, a dependency change or a change to the host:

1. `AtakLinkCheck`: no finding.
2. ATAK starts, the plugin loads, `status: ONLINE` in `TakConvo.Plugin`.
3. Account pane: status, server features; login and logout in XMPP-login mode. Without TAK
   credentials only the notice shows, and "Use an XMPP account" brings the fields. In
   landscape the keyboard leaves the pane visible. Signing in to a server that fails shows a
   spinner, then the error, which stays up during background retries (Reconnect does the same).
   Once a sign-in (or the account the pane was waiting for) is online, the chat list replaces
   the account pane: XMPP off, open TAK Convo (account pane), XMPP on. With the chats open,
   Manage accounts, then the avatar: the profile picture screen shows at once (`DropDownManager`
   logs "retaining the drop down on the stack" for the account pane); tapping its image opens
   the system photo picker. Cancel brings back the account pane, with no "Log in" button, and
   back closes it. The chats are still there next time.
   Changing the ATAK callsign (`DEBUG_SET_PREF --es key locationCallsign --es value X`, then
   back) logs `nickname ... -> callsign X` and, in `tak convo`, `published User Nick` and a
   group chat `setSelf(.../X)`. With a second phone (another account) in a group chat, give
   both phones the same callsign: the second one keeps its nickname in that room
   (`TakConvo.Nicknames`: `X taken in <room>, keeping ...`), and it is still joined after a
   reconnect (`... joining as ...` if the join hit the conflict). Notifications › Vibration off, then `DEBUG_FAKE_INCOMING`: the
   notification is in channel `takconvo_messages_sound` (`dumpsys notification`).
4. Chat pane from the toolbar: chat list; open a chat; send to yourself; the message is
   delivered (double tick) and encrypted (shield). With no chats, the hint's icon and text sit
   between the search bar and Start chat. Not connected: block ATAK's network
   (`adb shell cmd connectivity set-chain3-enabled true`, then
   `set-package-networking-enabled false com.atakmap.app.civ`) and restart ATAK (the open
   connection survives the block). The pane shows the red strip, "connecting" with a spinner,
   then "Server not found"; a tap opens the account pane. Undo both commands (`true`, then
   chain3 `false`): the strip goes away once online. A wrong `takconvo_xmpp_host` doesn't do
   it: Conversations tries its last working address first.
5. Back: chat → list → pane closes. Reopen: same state. With a chat open, log out (or turn
   XMPP off: `DEBUG_SET_PREF --es key takconvo_xmpp_enabled --es value false`, then `true`):
   one back closes the account pane and the old chat doesn't come back.
6. Home, then back to ATAK: `TakConvo.Host` logs pausing/stopping, then resuming.
7. Start chat → Add contact dialog; group chat → channel details → back. Chat list › QR icon:
   Show QR Code shows the address; Scan QR Code shows the camera in the pane (`dumpsys
   media.camera`: ATAK in PREVIEW), scanning another device's code opens that contact, and back
   releases the camera. Start chat →
   Discover channels lists the XMPP server's channels, without the public directory's privacy
   prompt. With `takconvo_xmpp_channel_discovery` = `server` and `takconvo_xmpp_channel_server`
   = the server's domain, it lists the same channels. ATAK's Contacts lists the open group
   chats at the top, with the chat room icon; tapping one opens it.
8. Overflow menus: search messages; Settings opens the plugin's preferences; Manage accounts
   opens the account pane.
9. Long-press a message: context menu, add a reaction.
10. Text field: select text (floating toolbar), paste as quote.
11. Attachment row: File → pick a file → send to yourself → open it with another app.
    Camera opens (cancel it). Voice message (the microphone send button): the recorder floats
    over the chat, the timer runs, Send delivers it, and it plays. Cancel and back discard it
    ("deleted canceled recording" in `tak convo`). A chat has no call button.
12. Contacts and notifications, with `DEBUG_FAKE_CONTACT` and `DEBUG_FAKE_INCOMING` (see
    [08](08-contacts-and-notifications.md#testing)): with message notifications off (the
    default), pane closed → no notification, and the contact row, the Contacts button and the
    TAK Convo tool show the count. Turn them on (`DEBUG_SET_PREF --es key
    takconvo_notification_messages --es value true`): a notification with sound and "app
    switched into background" in `tak convo`; tapping the notification opens the chat; Reply
    and Mark as read work; tapping the contact's XMPP connector opens the chat. Remove the key
    afterwards.
13. `dumpsys alarm` lists ATAK alarms tagged `com.atakmap.android.takconvo.DELIVER`, and
    `TakConvo.PendingIntents` logs "delivering eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"
    (about a minute after the account connects) or "... PING" when they fire.
14. `.pref` provisioning: import a file with a few `takconvo_conversations_*` keys (one
    Boolean as a String, one number as an Integer), an invalid value and an unknown key. The
    valid ones are in Conversations' preferences with the right types (`run-as`, see
    Provisioning). `TakConvo.Settings` warns about the other two. Removing a key
    (`DEBUG_SET_PREF` without `value`) removes it there. A `takconvo_xmpp_password` without
    `takconvo_xmpp_username` is logged as ignored and removed. Only test one with a username
    using the account's real password: it replaces the stored login.
15. ATAK's map ([10](10-atak-map-integration.md#testing)), in the self chat: a fake message
    with an MGRS reference and a lat/lon pair shows two links, each tap a marker; the quick
    message row is there and a tap fills the field; Location › A point on the map, then send:
    **Show location** centers the map; the menu's Show on map centers on your position; Data
    Packages › SEND › TAK Convo › the self chat sends the package, and tapping it offers
    Import into ATAK, which extracts it. A marker's details › SEND lists the group chats;
    `DEBUG_SEND_MAP_ITEM` sends the self chat a line whose MGRS is a link, and a data package.
16. What other apps can reach ([09](09-code-guidelines.md#android)):
    - `DebugReceiver` takes adb's shell broadcasts, not an app's (`run-as`, see Debug
      broadcasts).
    - A `com.atakmap.android.takconvo.DELIVER` broadcast from adb (`-d takconvo://deliver/x
      -p com.atakmap.app.civ`) logs nothing in `TakConvo.PendingIntents`, while alarms are
      still delivered.
    - `DEBUG_ATAK_BROADCAST` with `action` `com.atakmap.android.takconvo.SEND_TO_GROUP_CHAT`
      and `extra.contactUID` `takconvo.room:<room>` gets no reaction. With that room open,
      `DEBUG_SEND_TO_CONTACT --es uid takconvo.room:<room>` makes `TakConvo.Send` warn that
      there's nothing to send.
    - `DEBUG_SEND_FILE` with a file in ATAK's app storage (`run-as` into `files/`, path by
      `/data/data/...` and `/data/user/0/...`) logs "refusing to send a file from ATAK's
      private storage". A file in `/sdcard/atak` is sent.
    - `DEBUG_ATAK_BROADCAST` with `action` `com.atakmap.android.takconvo.OPEN`,
      `extra.takconvo.class` `eu.siacs.conversations.ui.ConversationsActivity` (with or
      without a made-up `extra.takconvo.token`) logs "ignoring an open request that didn't
      come from a notification". A real notification's tap still opens its chat. The same
      with `com.atakmap.android.takconvo.LOCATION_PICKED` and `extra.point` while Location ›
      A point on the map waits: nothing is shared; the tap on the map still is.
17. Approving the server ([03](03-provisioning-and-trust.md#approving-the-server)), with
    `.pref` files imported by `DEBUG_IMPORT_PREF`:
    - after installing over a version without approvals, the engine logs "encrypting the
      stored credentials" and "approved {...}", and `no_backup/takconvo_plugin/approved_server`
      exists; the `accounts` table's `password` starts with `takconvo-gcm1:`; after a
      restart the account comes online without "updating account" (it decrypted);
    - a `.pref` with `<preference name="takconvo_s4_probe">` creates
      `shared_prefs/takconvo_s4_probe.xml` (why approval is a file);
    - `takconvo_xmpp_use_tak_credentials` true and `takconvo_xmpp_domain` `s4-test.invalid`:
      "waiting for approval", the account disabled, `saXmppUsername` cleared, the notification
      "approve the new server"; its tap opens the account pane naming `s4-test.invalid` and
      "your TAK server credentials". Don't tap Connect there. A `.pref` with the old values
      reconnects without a question and the notification goes;
    - `takconvo_xmpp_use_android_ca_store` true: the notice lists "the device's CA store";
      **Connect** approves it and the account comes online. Remove the key and approve again
      to end where you started.
18. Clear Content ([02](02-embedded-engine.md#ataks-clear-content)). It deletes the history and
    the XMPP login, so back up first, with ATAK stopped:
    ```bash
    adb shell am force-stop com.atakmap.app.civ
    adb exec-out run-as com.atakmap.app.civ tar -cf - . > atak-data.tar
    adb exec-out run-as com.atakmap.app.civ tar -cf - \
        -C /storage/emulated/0/Android/data/com.atakmap.app.civ/cache takconvo > ext.tar
    ```
    Start ATAK, post a notification (`DEBUG_FAKE_INCOMING`), then `DEBUG_CLEAR_CONTENT`: the
    plugin logs "Clear Content: deleting TAK Convo's data" then "TAK Convo's data deleted";
    the notification goes; `databases/takconvo_*`, `shared_prefs/takconvo_*`,
    `files|cache|no_backup/takconvo*`, `app_takconvo_*` and the external `cache/takconvo` are
    gone and stay gone; ATAK keeps running. Restarted, the plugin is `NOT_SIGNED_IN` (the
    login went too). To restore: force-stop ATAK, delete what `find . ! -type d` lists that
    the tar doesn't (stale database journals would otherwise meet the restored databases),
    `adb exec-in run-as com.atakmap.app.civ tar -xf - < atak-data.tar` (and the external
    tar with `-C` its directory), start ATAK. The stored password can't be decrypted any more
    (`AEADBadTagException`: the key went with the wipe); provisioning sets it again from the
    restored credential store and the account comes online.
19. The pane's size ([04](04-chat-pane-activity-host.md#when-the-pane-changes-size)), with
    `TakConvo.Host`:
    - opening the pane logs "pane size", then "creating ... for W x H dp" for that size (311 ×
      311 dp on the S23), and no relaunch; closing and reopening it relaunches nothing;
    - dragging the handle toward the map takes it full screen (738 × 311 dp on the S23),
      dragging it back restores it: each time, one "relaunching ..." 400 ms later;
    - ATAK only rotates with its `atakControlForcePortrait` setting: import a `.pref` setting it
      to `true` (as a `java.lang.Boolean`). With a chat open and a draft typed, the chat comes
      back for the new size with the same scroll position and draft. Reopened in portrait, the
      pane is at the bottom and relaunched at once. With the chat list's ⋮ menu open while it
      rotates, nothing is relaunched until the menu closes. With the account pane over the
      chat pane while it rotates, the chat pane is relaunched when it shows again;
    - remove the setting (`DEBUG_SET_PREF --es key atakControlForcePortrait`), restart ATAK (its
      own layout is off after the change) and clear the draft.
20. `adb logcat -b crash` is empty.

## Gotchas

- Git for Windows: use `core.longpaths=true` when cloning upstream Conversations under a deep
  directory. Some of its paths exceed 260 characters, and those files silently don't check out.
- Conversations is built as a library, whose `BuildConfig` has no version or application id:
  the fields its code reads (`APP_NAME`, `VERSION_NAME`, `APPLICATION_ID`...) are declared by
  hand in `conversations/build.gradle`.
- The dev PC may not resolve internal hosts that the phone reaches; test against the real
  server from the phone.
- Credentials: TAK server credentials come from ATAK. An XMPP login in a `.pref` file
  (`takconvo_xmpp_password`) is moved to ATAK's credential store as soon as the plugin reads
  it, but the file holds it in clear text. Keep such files out of the repository.
- ATAK doesn't finish starting while the phone is locked. `deploy.ps1` then reports that the
  account did not come online, and no `TakConvo` log appears. Unlock the phone, or keep it
  awake while charging (Developer options › Stay awake).
