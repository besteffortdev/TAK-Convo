# 05 — The Conversations fork

`:conversations` is a fork of **Conversations 2.20.4**
(<https://codeberg.org/iNPUTmice/Conversations>, tag `2.20.4`, commit `bf3269cd`), GPLv3.
This document lists every difference from upstream, why each exists, and how to move the fork
to a newer upstream release.

The exact, current diff can always be regenerated:

```bash
tools/fork-diff.sh --stat          # changed files
tools/fork-diff.sh > fork.patch    # unified diff, paths upstream/... and fork/...
```

As of 2026-09-30 it is **22 modified files, 1 added file, 2 removed manifests** (48 hunks). Every
code change carries a `TAKCONVO` comment: `grep -rn TAKCONVO conversations/src`.

## What was imported

| Upstream | Fork | Notes |
|---|---|---|
| `src/main` | `conversations/src/main` | modified, see below |
| `src/conversations` | `conversations/src/conversations` | Java and resources as-is; `AndroidManifest.xml`, `fastlane/`, `new_launcher-web.png` not imported |
| `src/free` | `conversations/src/free` | as-is; `AndroidManifest.xml` not imported |
| `src/conversationsFree` | `conversations/src/conversationsFree` | as-is |
| `libs/annotation`, `libs/annotation-processor` | `conversations/libs/...` | project path renamed, see build changes |
| `build.gradle` (app) | `conversations/build.gradle` (library) | rewritten, see build changes |
| `AndroidManifest.xml` of main, conversations, free | `conversations/upstream/AndroidManifest.*.xml` | kept for reference only, not built |
| `proguard-rules.pro` | `conversations/upstream/proguard-rules.pro` | used as `consumerProguardFiles` |

Not imported: the `playstore`, `quicksy*` and `test` source sets, `art/`, `docs/`, `fastlane/`.

The fork is the upstream **`conversations` + `free`** variant: the library keeps upstream's two
flavor dimensions (`mode`, `distribution`) with one flavor each, so the source-set overlays
work exactly as in the app. The plugin selects it with `missingDimensionStrategy`.

## Build changes

### `conversations/build.gradle`

Upstream's app `build.gradle` became a `com.android.library` build:

- no `applicationId`, `versionCode`, `versionName`, signing or app-only settings. `BuildConfig`
  fields the code reads are declared by hand: `APP_NAME` = `TAK Convo`,
  `VERSION_NAME` = `2.20.4+takconvo`, `VERSION_CODE`, `APPLICATION_ID`, `PRIVACY_POLICY`. The
  `app_name` and `applicationId` string resources are set the same way.
- `compileSdk 36` instead of 37. AGP 8.13 is the newest AGP the ATAK `takdev` Gradle plugin
  works with, and AGP 8 can't compile against SDK 37. SDK 37 constants moved to
  `TakConvoCompat` (below).
- `apply from: gradle/atak-runtime.gradle`: AndroidX, OkHttp and Kotlin versions are forced
  to the ones ATAK 5.5.1.8 ships, because ATAK's copies win at runtime
  (see [06](06-atak-runtime-and-classloading.md)).
- `org.jetbrains:annotations` excluded (duplicate classes with ATAK's).

Dependency versions that differ from upstream 2.20.4:

| Dependency | Upstream | Fork | Why |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.5.0 | 4.11.0 | ATAK ships OkHttp 4.11.0 and its copy is the one loaded |
| `im.conversations.webrtc:webrtc-android` | 149.0.0 | 129.0.0 | 149 requires compileSdk 37 (AGP 9) |
| `androidx.appcompat:appcompat` | 1.8.0 | 1.7.1 | builds with compileSdk 36 against ATAK's AndroidX |
| `androidx.concurrent:concurrent-futures` | 1.3.0 | 1.2.0 | same |
| `androidx.emoji2:*` | 1.6.0 | 1.5.0 | same |
| `androidx.exifinterface` | 1.4.2 | 1.4.1 | forced to ATAK's version |
| `androidx.swiperefreshlayout` | 1.2.0 | 1.1.0 | builds with compileSdk 36 against ATAK's AndroidX |
| `androidx.viewpager` | 1.1.0 | 1.0.0 | same; also ATAK-provided |
| `androidx.work:work-runtime` | 2.11.2 | 2.10.5 | same |
| `com.google.android.material` | 1.14.0 | 1.13.0 | same |
| `androidx.emoji2:emoji2-bundled` | `freeImplementation` | `implementation` | the fork only has the free variant |

Only the OkHttp and WebRTC reasons were recorded at the time. When upgrading, try upstream's
versions first and keep an older one only if the build or `AtakLinkCheck` fails with it.

### `libs/annotation-processor/build.gradle`

`project(':libs:annotation')` → `project(':conversations-annotation')`, the name the plugin's
`settings.gradle` gives it.

### Manifests

`src/main/AndroidManifest.xml` is reduced to an empty manifest with
`<uses-sdk tools:overrideLibrary="androidx.heifwriter" />`. A plugin's components can't run in
ATAK's process, so no activity, service, receiver or provider is declared, and permissions are
ATAK's. The flavor manifests of `src/conversations` and `src/free` were not imported. The
upstream originals are in `conversations/upstream/` for reference.

## Source changes

Grouped by why they exist. File paths are relative to `conversations/src/main/`.

### A. Running without an Android Service, Application or own package

Conversations runs as plain objects in ATAK's process (see [02](02-embedded-engine.md)). The
flag `TakConvoCompat.EMBEDDED` is set by the plugin before anything else starts.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | **New.** `EMBEDDED` flag, `EXTRA_TRUST_MANAGER` hook, SDK 37 constants `CINNAMON_BUN` and `ACCESS_LOCAL_NETWORK`. |
| `java/eu/siacs/conversations/Conversations.java` | New `onCreateEmbedded()`: the part of `onCreate()` that is safe in ATAK (sets `CONTEXT`, initialises emoji). It skips installing Conscrypt as the process-wide security provider, the global uncaught-exception handler and the night-mode setting, which would all change ATAK itself. |
| `java/eu/siacs/conversations/services/XmppConnectionService.java` | `stopSelf()` (2 places) and `toggleForegroundService()` skipped when embedded: `Service` final methods NPE on a service that was never attached by the system; ATAK's own foreground service keeps the process alive. `toggleSetProfilePictureActivity()` skipped: that component doesn't exist in ATAK's package. |
| `java/eu/siacs/conversations/services/UnifiedPushBroker.java` | `setUnifiedPushDistributorEnabled()` skipped: those components don't exist in ATAK's package. |
| `java/eu/siacs/conversations/services/CallIntegration.java` | `hasSystemFeature()` returns false: no `ConnectionService` can be registered from a plugin. |
| `AndroidManifest.xml` | Emptied, see above. |

### B. Trust: CAs provisioned by the plugin

| File | Change |
|---|---|
| `java/eu/siacs/conversations/services/MemorizingTrustManager.java` | When the default trust manager rejects a chain, `TakConvoCompat.EXTRA_TRUST_MANAGER` (the plugin's TAK truststore / Android CA store / CA file composite) is tried before upstream's own handling. Hostname verification is unchanged. See [03](03-provisioning-and-trust.md). |

### C. Compiling against SDK 36

| File | Change |
|---|---|
| `java/eu/siacs/conversations/ui/EditAccountActivity.java` | `Build.VERSION_CODES.CINNAMON_BUN` → `TakConvoCompat.CINNAMON_BUN`, `Manifest.permission.ACCESS_LOCAL_NETWORK` → `TakConvoCompat.ACCESS_LOCAL_NETWORK`. |
| `java/eu/siacs/conversations/utils/PermissionUtils.java` | Same, 2 places. |
| `java/eu/siacs/conversations/xmpp/XmppConnection.java` | Same, 1 place. |

Revert these when the build moves to compileSdk 37.

### D. ATAK's copies of libraries win at runtime

Plugin classes load parent-first, so ATAK's AndroidX and OkHttp are the ones used. ATAK's build
turned the default methods of AndroidX interfaces into abstract ones, and the plugin's
`java.time` is desugared to `j$.time`. `tools/AtakLinkCheck.java` finds every such case (see
[06](06-atak-runtime-and-classloading.md)).

| File | Change |
|---|---|
| `java/eu/siacs/conversations/ui/ConversationsActivity.java` | The back stack listener lambda `this::showDialogsIfMainIsOverview` became an anonymous class implementing all five `FragmentManager.OnBackStackChangedListener` methods (+ import `BackEventCompat`). |
| `java/eu/siacs/conversations/ui/ConversationFragment.java` | `backStackListener`: the four default methods of `OnBackStackChangedListener` implemented. `menuProvider`: `onPrepareMenu` and `onMenuClosed` implemented. |
| `java/eu/siacs/conversations/ui/ConversationsOverviewFragment.java` | `globalMenuProvider` and `menuProvider`: `onPrepareMenu` and `onMenuClosed` implemented. |
| `java/eu/siacs/conversations/http/HttpConnectionManager.java` | `callTimeout(Duration.ofSeconds(5))` → `callTimeout(5, TimeUnit.SECONDS)`. |
| `java/eu/siacs/conversations/utils/ScanResultProcessor.java` | `callTimeout(Duration.ofSeconds(3))` → `callTimeout(3, TimeUnit.SECONDS)`. |

Without these, opening a chat throws `AbstractMethodError` and a link check
`NoSuchMethodError`, both of which kill ATAK.

### E. Activities shown in an ATAK pane

Conversations' activities run hosted by the plugin, their views moved into an ATAK drop-down
(see [04](04-chat-pane-activity-host.md)).

| File | Change |
|---|---|
| `java/eu/siacs/conversations/ui/BaseActivity.java` | New public field `embeddedContent` (the root of the activity's views, set by the host). `findViewById()` and `getCurrentFocus()` look there first, because the views are no longer below the window's decor view. `onStart()` doesn't apply the night mode setting when embedded: that calls `recreate()`, which an embedded activity can't do. |
| `java/eu/siacs/conversations/ui/Activities.java` | `setStatusAndNavigationBarColors()` does nothing when embedded: the system bars belong to ATAK's window. |
| `java/eu/siacs/conversations/ui/ConversationsActivity.java` | The start-up checks in `onBackendConnected()` (crash report prompt, battery optimisation, permission prompts) are skipped when embedded. |
| `java/eu/siacs/conversations/ui/XmppActivity.java`, `ContactDetailsActivity.java`, `ConversationsActivity.java`, `StartConversationActivity.java` (4 places) | `requestPermissions(...)` → `ActivityCompat.requestPermissions(this, ...)`. `Activity.requestPermissions` NPEs in an embedded activity (it has no `ActivityThread`); `ActivityCompat` lets the plugin's `PermissionCompatDelegate` make the request through ATAK's activity. Same behaviour outside ATAK. |
| `res/layout/fragment_conversation.xml` | The attachment choices are one horizontally scrolling row (`HorizontalScrollView` around the `ConstraintLayout`, `Flow` with `wrapMode="none"`) instead of a wrapping grid. A 350 dp pane only fits two choices per row, and three rows didn't fit in the pane's height. `message_input_box` is laid out above the new `attachment_choices_scroll`. The `attachment_choices_max_element_wrap` integer is now unused. |

### F. Files handed to other apps

| File | Change |
|---|---|
| `java/eu/siacs/conversations/persistance/FileBackend.java` | When embedded, `getAuthority()` returns ATAK's FileProvider (`<package>.provider`) and `getUriForFile()` goes through `shareableFile()`: files under external storage are served directly, private files from a copy in the external cache `shared/` directory. New `deleteShareableCopies()` (called by the plugin at start). `Cache.takePicture()` writes to the external cache when embedded, so that the camera app can write through ATAK's provider; `isCachedFile()` follows. |

ATAK's provider only serves external storage, and a plugin can't declare a provider of its own.
Without this, opening an attachment fails, and taking a photo or showing an image notification
throws.

### G. Diagnostics

| File | Change |
|---|---|
| `java/eu/siacs/conversations/xmpp/XmppConnection.java` | A stream error without a known condition logs the condition's class name and text instead of the element's `toString()`, which said nothing. Harmless to drop. |

## What the plugin relies on

An upstream update can also break the plugin without touching a fork change. These are the
Conversations APIs the plugin (`app/`) uses directly:

| Plugin class | Conversations API |
|---|---|
| `xmpp/EmbeddedConversations` | extends `Conversations`; `attachBaseContext`, `onCreateEmbedded()` |
| `xmpp/EmbeddedXmppService` | extends `XmppConnectionService`; `attachBaseContext` |
| `xmpp/XmppEngine` | `XmppConnectionService`: `onCreate`, `onStartCommand(null, 0, 0)`, `onBind`, `onTaskRemoved`, `onDestroy`, `getAccounts`, `findAccountByJid`, `createAccount`, `updateAccount`, `reconnectAccountInBackground`, `findOrCreateConversation`, `sendMessage`, `getConversations`, `set/removeOnAccountListChangedListener`, `set/removeOnConversationListChangedListener`. `Account` (constructor, `setResource`, `setPassword`, `setHostname`, `setPort`, `setOption`/`isOptionSet(OPTION_DISABLED)`, `isOnlineAndConnected`). `AppSettings.SHOW_CONNECTION_OPTIONS`, `BuildConfig.APP_NAME`, `CryptoHelper.random`, `Jid.ofUserInput`, `FileBackend.deleteShareableCopies` |
| `ui/AccountView` | layout `activity_edit_account` and its view ids (`toolbar`, `avater`, `account_jid(_layout)`, `account_password(_layout)`, `save_button`, `cancel_button`, `stats`, `account_main_layout`, and the ids it hides), style `Theme.Conversations3.Dark`, `AxolotlService`, `UIHelper`, `XmppConnection` and its managers (`Blocking`, `Carbons`, `ClientStateIndication`, `ExternalServiceDiscovery`, `HttpUpload`, `MessageArchive`, `Pep`, `Roster`) |
| `ui/host/EmbeddedActivityHost` | activity class names (the `SUPPORTED` list, `ui.activity.SettingsActivity`, `EditAccountActivity`, `ManageAccountActivity`), `ConversationsActivity.ACTION_VIEW_CONVERSATION` / `EXTRA_CONVERSATION`, style `Theme.Conversations3`, `BaseActivity.embeddedContent` |

## Moving to a newer upstream release

1. **Read upstream's changelog** between 2.20.4 and the target tag, looking for changes to the
   files in the tables above, to `XmppConnectionService`'s lifecycle, and to dependencies.
2. **Rebase the fork.** In a scratch directory:
   ```bash
   tools/fork-diff.sh 2.20.4 > takconvo.patch      # the fork's changes, against its base
   git -c core.longpaths=true clone --depth 1 --branch <new-tag> \
       https://codeberg.org/iNPUTmice/Conversations.git new
   cd new && git apply -p1 --reject --whitespace=nowarn ../takconvo.patch
   ```
   The patch uses `upstream/` and `fork/` prefixes; `-p1` strips them. Hunks that don't apply
   are left in `*.rej` files next to their file (the patch is a plain diff, so `--3way` can't
   merge). They are usually the `requestPermissions` and `MenuProvider` ones, which move with
   upstream refactoring: re-apply them by hand, keeping the `TAKCONVO` comments.
3. **Copy** the patched `src/main`, `src/conversations`, `src/free`, `src/conversationsFree`
   and `libs/` over `conversations/`. Skip `fastlane/`, the launcher artwork and the flavor
   manifests. Update `conversations/upstream/` with the new manifests and proguard rules.
4. **Merge the build.** Diff upstream's new `build.gradle` against the previous one and carry
   the changes into `conversations/build.gradle`: new dependencies, new `BuildConfig` fields,
   new flavor source sets. Keep the ATAK-provided versions from `gradle/atak-runtime.gradle`.
5. **New SDK constants.** Anything new from SDK 37 or later goes into `TakConvoCompat` while
   the build stays on compileSdk 36.
6. **Build and link-check.**
   ```bash
   ./gradlew assembleCivDebug --offline
   java tools/AtakLinkCheck.java <sdk>/atak.apk app/build/outputs/apk/civ/debug/<apk>
   ```
   Fix every `AbstractMethodError` finding: implement all methods, and replace lambdas by
   anonymous classes for interfaces ATAK provides. Fix `NoSuch...Error` findings by using another
   overload, or accept them in `tools/atak-link-ignore.txt` with a reason.
7. **Check for new direct `Activity.requestPermissions` calls**
   (`grep -rn "[^.a-zA-Z]requestPermissions(" conversations/src`) and route them through
   `ActivityCompat`.
8. **Check new activities.** Any activity that should open inside the pane goes into
   `EmbeddedActivityHost.SUPPORTED` once tested; others show "Not available inside ATAK".
9. **Test on the device**, see [07](07-development-and-testing.md#device-test-checklist).
10. **Update this document**: the base tag at the top, the file tables and the dependency table.
