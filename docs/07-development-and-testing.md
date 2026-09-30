# 07 — Development and testing

## Setup

| Need | Notes |
|---|---|
| JDK 21 | Temurin 21 for Gradle (`JAVA_HOME`); AGP 8.13 / Gradle 8.14.3 |
| Android SDK | platform 36, build-tools 35 (`dexdump`, `aapt2` are handy) |
| ATAK-CIV SDK 5.5.1.8 | from the TAK-Product-Center GitHub release: `main.jar`, `atak-gradle-takdev.jar`, the developer `atak.apk`, the keystore |
| A device or emulator with the SDK's **developer** `atak.apk` | release ATAK refuses plugins not signed by TAK.gov |

`local.properties` (not committed):

```properties
sdk.dir=<Android SDK>
takdev.plugin=C:\\dev\\atak-sdk\\ATAK-CIV-5.5.1.8-SDK\\atak-gradle-takdev.jar
sdk.path=C:\\dev\\atak-sdk\\ATAK-CIV-5.5.1.8-SDK
# takrepo.url / takrepo.user / takrepo.password switch to tak.gov's Maven repository instead
```

Write Gradle files without a byte-order mark: PowerShell's `Set-Content -Encoding utf8` adds
one, and `settings.gradle` then fails to parse.

## Build

```bash
./gradlew assembleCivDebug --offline       # app/build/outputs/apk/civ/debug/ATAK-Plugin-takconvo-*.apk
./gradlew assembleCivDebug --offline -PatakVersion=5.8.0   # declare another plugin-api
java tools/AtakLinkCheck.java <sdk.path>/atak.apk app/build/outputs/apk/civ/debug/<apk>
```

In PowerShell, quote the property: `.\gradlew.bat assembleCivDebug --offline
"-PatakVersion=5.8.0"`. Unquoted, PowerShell splits the argument at the first dot and Gradle
looks for a task named `.8.0`. Each build replaces the APK of the other version in the output
directory.

`AtakLinkCheck` must report no finding before an APK goes on a device (see
[06](06-atak-runtime-and-classloading.md)).

### Other ATAK versions and release ATAK

The plugin loads in the SDK's **developer** ATAK (build type `sdk`), whatever its
`plugin-api`. Since 4.10 ATAK also accepts a plugin built for an older API than its own. A
**release** ATAK (Play Store, or an organisation's loadout) won't load it, and building with
`-PatakVersion` doesn't change that:

- **Signature.** `AtakPluginRegistry.verifySignature` only accepts plugins signed with ATAK's
  own key, a TAK.gov key (`ACCEPTABLE_KEY_LIST`) or an App Transparency signature. It logs
  `signature mismatch[com.atakmap.android.takconvo.plugin]`, and no setting bypasses it.
- **Obfuscation.** Release ATAK is ProGuard-obfuscated. Checked against ATAK-CAN 5.8.0.5
  (`civSmall-release`, `[playstore]`), the 5.8 build refers to 7 ATAK methods that were
  renamed (`AtakBroadcast.getInstance()` is `a()`, and `registerReceiver`, `sendBroadcast`,
  `CotMapComponent.getInstance`...). It also refers to 2 methods of OkHttp's `RequestBody`,
  which ATAK 5.8 bundles, with Okio renamed (`okio.ByteString` is `atak.core.e2`). ATAK's copy
  shadows the plugin's.

A release build for a given ATAK has to be made with that version's SDK. The template's
`release` build type applies the SDK's ProGuard mapping (`-applymapping`) and moves the
plugin's classes, OkHttp included, into `atakplugin.takconvo` (`-repackageclasses`). It then
has to be signed by TAK.gov, for example through its third-party plugin pipeline. Until then,
a test device needs the developer ATAK. Only 5.5.1.8 is public (GitHub
`TAK-Product-Center/atak-civ`), and installing it replaces a release ATAK, whose app data goes
with it (`/sdcard/atak` stays).

## Install and run

```powershell
tools\deploy.ps1 -Serial <adb serial> [-NoBuild] [-SkipLinkCheck]
```

builds, link-checks, installs, and restarts ATAK with the plugin loaded. It exists because of two
ATAK behaviours, both handled by editing ATAK's preferences while ATAK is stopped
(`run-as com.atakmap.app.civ`, a debuggable developer build):

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

## Debug broadcasts

Debug builds register `DebugReceiver` (exported, debug only):

| Action (`com.atakmap.android.takconvo.` + ...) | Extras | Does |
|---|---|---|
| `DEBUG_SET_PREF` | `key`, `value` | sets an ATAK preference (string) |
| `DEBUG_IMPORT_PREF` | `path` | imports a `.pref` file with ATAK's importer |
| `DEBUG_PROVISION` | | runs `XmppEngine.provision()` |
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
| `DEBUG_DUMP_CONTACT` | | logs its unread counts, XMPP presence and default connector |
| `DEBUG_FAKE_INCOMING` | `from` (default: own), `body` | stores and notifies a message as if received (nothing is sent) |
| `DEBUG_ATAK_BROADCAST` | `action` | sends an ATAK-internal broadcast, e.g. `com.atakmap.android.contact.CONTACT_LIST` opens Contacts |

```bash
adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_CHAT
```

`adb shell` splits extras on spaces, even quoted: use values without spaces.

## Logs

| Tag | From |
|---|---|
| `TakConvo.Plugin`, `.XmppEngine`, `.Trust`, `.Settings`, `.Host`, `.Contacts`, `.PendingIntents`, `.Notifications`, `.Debug` | the plugin |
| `tak convo` | Conversations (its `Config.LOGTAG` is the app name) |
| `AndroidRuntime` | crashes; `adb logcat -b crash` keeps them after the main buffer rolls |

```bash
adb logcat -s TakConvo.Plugin TakConvo.XmppEngine TakConvo.Host "tak convo"
```

`TakConvo.Host` logs each embedded activity's creation, resume, pause and stop, and every
permission request. `ResourcesCompat` "Failed to inflate ColorStateList" warnings are harmless
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
4. Chat pane from the toolbar: chat list; open a chat; send to yourself; the message is
   delivered (double tick) and encrypted (shield).
5. Back: chat → list → pane closes. Reopen: same state.
6. Home, then back to ATAK: `TakConvo.Host` logs pausing/stopping, then resuming.
7. Start chat → Add contact dialog; group chat → channel details → back.
8. Overflow menus: search messages; Settings opens the plugin's preferences; Manage accounts
   opens the account pane.
9. Long-press a message: context menu, add a reaction.
10. Text field: select text (floating toolbar), paste as quote.
11. Attachment row: File → pick a file → send to yourself → open it with another app.
    Camera opens (cancel it). Voice message (the microphone send button): the recorder floats
    over the chat, the timer runs, Send delivers it, and it plays. Cancel and back discard it
    ("deleted canceled recording" in `tak convo`). A chat has no call button.
12. Contacts and notifications, with `DEBUG_FAKE_CONTACT` and `DEBUG_FAKE_INCOMING` (see
    [08](08-contacts-and-notifications.md#testing)): pane closed → a notification with sound
    and "app switched into background" in `tak convo`; the contact row, the Contacts button
    and the TAK Convo tool show the count; tapping the notification opens the chat; Reply and
    Mark as read work; tapping the contact's XMPP connector opens the chat.
13. `dumpsys alarm` lists ATAK alarms tagged `com.atakmap.android.takconvo.DELIVER`, and
    `TakConvo.PendingIntents` logs "delivering eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"
    (about a minute after the account connects) or "... PING" when they fire.
14. `adb logcat -b crash` is empty.

## Gotchas

- Git for Windows: use `core.longpaths=true` when cloning upstream Conversations under a deep
  directory. Some of its paths exceed 260 characters, and those files silently don't check out.
- Conversations is built as a library, whose `BuildConfig` has no version or application id:
  the fields its code reads (`APP_NAME`, `VERSION_NAME`, `APPLICATION_ID`...) are declared by
  hand in `conversations/build.gradle`.
- The dev PC may not resolve internal hosts that the phone reaches; test against the real
  server from the phone.
- Never put credentials in `.pref` files or preferences: TAK server credentials come from ATAK,
  and the XMPP login goes to ATAK's credential store.
