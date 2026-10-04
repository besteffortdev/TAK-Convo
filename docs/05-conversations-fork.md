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

As of 2026-10-03 (credentials encrypted, OMEMO key size) it is **53 modified files, 2 added
files, 2 removed manifests** (132 hunks), and the patch applies cleanly to 2.20.4. Every code change carries a `TAKCONVO`
comment: `grep -rn TAKCONVO conversations/src`. When codeberg is unreachable,
`UPSTREAM_DIR=<a checkout of the tag> tools/fork-diff.sh` compares with a local clone.

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
  to the ones the target ATAK (5.6 or 5.8) ships, because ATAK's copies win at runtime
  (see [06](06-atak-runtime-and-classloading.md)).
- `org.jetbrains:annotations` excluded (duplicate classes with ATAK's).
- Java 17 instead of 21 (section K).

Dependency versions that differ from upstream 2.20.4:

| Dependency | Upstream | Fork | Why |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.5.0 | 4.11.0 | ATAK ships OkHttp 4.11.0 and its copy is the one loaded |
| `im.conversations.webrtc:webrtc-android` | 149.0.0 | 129.0.0 | 149 requires compileSdk 37 (AGP 9) |
| `androidx.appcompat:appcompat` | 1.8.0 | 1.7.1 | builds with compileSdk 36 against ATAK's AndroidX |
| `androidx.concurrent:concurrent-futures` | 1.3.0 | 1.2.0 | same |
| `androidx.emoji2:*` | 1.6.0 | 1.5.0 | same |
| `androidx.exifinterface` | 1.4.2 | 1.4.1 (5.6), 1.4.2 (5.8) | forced to ATAK's version |
| `androidx.swiperefreshlayout` | 1.2.0 | 1.1.0 | builds with compileSdk 36 against ATAK's AndroidX |
| `androidx.viewpager` | 1.1.0 | 1.0.0 | same; also ATAK-provided |
| `androidx.work:work-runtime` | 2.11.2 | 2.10.5 | same |
| `com.google.android.material` | 1.14.0 | 1.13.0 | same |
| `androidx.emoji2:emoji2-bundled` | `freeImplementation` | `implementation` | the fork only has the free variant |

Only the OkHttp and WebRTC reasons were recorded at the time. When upgrading, try upstream's
versions first and keep an older one only if the build or `AtakLinkCheck` fails with it.

### `libs/annotation-processor/build.gradle`

`project(':libs:annotation')` → `project(':conversations-annotation')`, the name the plugin's
`settings.gradle` gives it. Both `libs/` builds are Java 17 (section K).

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
| `res/layout/fragment_conversation.xml` | The attachment choices are one horizontally scrolling row (`HorizontalScrollView` around the `ConstraintLayout`, `Flow` with `wrapMode="none"`) instead of a wrapping grid. A 350 dp pane only fits two choices per row, and three rows didn't fit in the pane's height. `message_input_box` is laid out above the new `attachment_choices_scroll`. The `attachment_choices_max_element_wrap` integer is now unused. The input row is more compact: less padding (4 dp instead of 6 dp, 8sp and 12 dp), a 20 dp attach icon without the icon button's minimum size and insets, a 24 dp send icon instead of 32 dp. |
| `res/values/dimens.xml` | `bubble_avatar_size` 48 → 36 dp (the avatar next to messages, and a bubble's minimum height), `avatar_on_conversation_overview` 56 → 44 dp. |
| `res/layout/item_conversation.xml` | The chat list avatar is `@dimen/avatar_on_conversation_overview` (the size its image is loaded at) instead of a fixed 56 dp. |
| `res/layout/item_media_choice.xml` | Attachment choices: 76 dp wide instead of 108, 28 dp icons with 12 dp padding instead of 40 and 16. |
| `res/layout/fragment_conversations_overview.xml` | The empty chat list hint: a 64 dp icon instead of the drawable's 192 dp, and 24 dp side margins instead of 48. In the pane the icon ran into the search bar and pushed the text under the Start chat button. |
| `java/eu/siacs/conversations/ui/PublishProfilePictureActivity.java` | When embedded, `pickAvatar()` picks the image with `GetContent("image/*")` instead of the cropper's `CropImageActivity`, whose Crop button is in the window's action bar (an embedded activity's window isn't shown). The preview and the published avatar are the image's center square (`cropCenterSquare`), as before. |

### F. Files handed to other apps

| File | Change |
|---|---|
| `java/eu/siacs/conversations/persistance/FileBackend.java` | When embedded, `getAuthority()` returns ATAK's FileProvider (`<package>.provider`) and `getUriForFile()` goes through `shareableFile()`: files under external storage are served directly, private files from a copy in the external cache `shared/` directory. New `deleteShareableCopies()` (called by the plugin at start). `Cache.takePicture()` writes to the external cache when embedded, so that the camera app can write through ATAK's provider; `isCachedFile()` follows. |

ATAK's provider only serves external storage, and a plugin can't declare a provider of its own.
Without this, opening an attachment fails, and taking a photo or showing an image notification
throws.

### G. Notifications, alarms and change observation as ATAK's

Conversations' notifications are posted as ATAK's, its PendingIntents are created in ATAK's
package, and the plugin needs to follow changes without looking like a UI (see
[02](02-embedded-engine.md#changes-and-threads) and [08](08-contacts-and-notifications.md)).
None of these changes does anything unless the plugin has set the corresponding hook.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | Three hooks the plugin sets: `PendingIntentFactory PENDING_INTENTS` with the helpers `getActivity`/`getService`/`getBroadcast`; `NotificationFilter NOTIFICATIONS` with `filter(notification)`; `Observer OBSERVER` (`onAccountsChanged`, `onConversationsChanged`, `onRosterChanged`, `onUnreadCountChanged`) with `observer()`. Each helper does what upstream does when the hook isn't set or not embedded. |
| `java/eu/siacs/conversations/services/NotificationService.java` | Every `PendingIntent.getActivity/getService(` (16 places) → `TakConvoCompat.getActivity/getService(`. Both `notify(...)` methods post `TakConvoCompat.filter(notification)`. The conversation shortcut is neither set on a message notification nor pushed when embedded. |
| `java/eu/siacs/conversations/services/XmppConnectionService.java` | The three alarms' `PendingIntent.getBroadcast(` → `TakConvoCompat.getBroadcast(`. `updateAccountUi()`, `updateConversationUi()` and `updateRosterUi()` also call the observer. `updateUnreadCountBadge()` reports the count to the observer instead of `ShortcutBadger` when embedded. `foregroundNotificationNeedsUpdatingWhenErrorStateChanges()` is false when embedded, and `toggleForegroundService()` cancels a foreground notification left by an earlier version. `onTaskRemoved()` logs out when embedded instead of keeping the (nonexistent) foreground service. |
| `java/eu/siacs/conversations/services/ShortcutService.java` | `refresh()` does nothing when embedded: the shortcuts would be ATAK's launcher shortcuts. |

Without these, notification taps, actions and all alarms (pings, reconnection timers) were
dropped by the system, notifications showed random ATAK icons (or crash ATAK when the id
doesn't exist there), a "1 of 1 accounts connected" notification appeared, and Conversations
silenced its notifications whenever no chat was open.

### H. No calls inside ATAK

Calls need `RtpSessionActivity`, a full-screen activity with the system's call integration,
which a plugin can't run. Upstream's own switch for "no calls" is Tor mode, and these changes
reuse its paths.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/ui/ConversationFragment.java` | The call and ongoing-call toolbar items are hidden when embedded. Upstream's call button proposes the call (the contact's device rings) before it starts `RtpSessionActivity`, so it wouldn't just fail. |
| `java/eu/siacs/conversations/xmpp/manager/DiscoManager.java` | The Jingle RTP features (`VOIP_NAMESPACES`) aren't advertised when embedded, so contacts' clients don't offer to call this device. |
| `java/eu/siacs/conversations/xmpp/manager/JingleManager.java` | `isUsingClearNet()`, which only gates RTP, is false when embedded: incoming RTP `session-initiate`s get an `unsupported-info` error, and call proposals are ignored, as over Tor. |

### I. Channel discovery on another server

The plugin's settings choose where "Discover channels" looks (see
[03](03-provisioning-and-trust.md)). Upstream's own setting already chooses between the public
directory and the account's server; these changes add another server.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | New hook `CHANNEL_DISCOVERY_SERVER` (a `Jid`, null by default). |
| `java/eu/siacs/conversations/services/ChannelDiscoveryService.java` | `getLocalMucServices()` returns that server, asked through the first enabled account, instead of the account's group chat services. New `discoverRoomsOf()` looks for rooms there: a group chat service's items are its rooms, and a server's items are its services, whose items are the rooms (rooms have a local part, services don't). Used for the account's services too, where it finds the same rooms. The cache key includes the server. |

### J. Diagnostics

| File | Change |
|---|---|
| `java/eu/siacs/conversations/xmpp/XmppConnection.java` | A stream error without a known condition logs the condition's class name and text instead of the element's `toString()`, which said nothing. Harmless to drop. |

### K. Java 17 for TAK.gov's pipeline

Upstream is Java 21. TAK.gov's Third Party Pipeline builds with JDK 17 and can't download
another one (see [07](07-development-and-testing.md#release-builds-and-takgovs-third-party-pipeline)),
so the fork compiles as Java 17. Each Java 21 construct became its Java 17 equivalent, with the
same result (a `null` selector now takes the default branch instead of throwing):

| Construct | Java 17 form | Files |
|---|---|---|
| pattern `switch` (`case Foo f ->`, guards, `case null`) | `if (x instanceof Foo f) ... else if ...` | `ui/adapter/UserAdapter`, `xml/XmlReader`, `xmpp/XmppConnection` (`errorResponse`), `xmpp/jingle/AbstractJingleConnection`, `xmpp/jingle/JingleRtpConnection`, `xmpp/jingle/transports/InbandBytestreamsTransport`, `xmpp/manager/RegistrationManager`, `im/.../commands/Actions`, `im/.../jingle/Reason` |
| `case null, default` in a `String` switch | `switch (Strings.nullToEmpty(x))` and `default` | `ui/ConversationFragment` (permission result) |
| record pattern (`x instanceof Rec(Type c)`) | `x instanceof Rec r`, then `r.c()` | `entities/ListItem`, `ui/ConversationsOverviewFragment`, `ui/StartConversationActivity`, `ui/adapter/SearchSuggestionAdapter`, `xmpp/manager/EntityTimeManager` |
| `instanceof` of the expression's own type | a null check | `ui/adapter/MediaAdapter` |

The build files changed with it: `sourceCompatibility`/`targetCompatibility` 17 in
`conversations/build.gradle` and both `libs/` builds, and the annotation processor
(`XmlElementProcessor`) declares `SourceVersion.latestSupported()` instead of
`@SupportedSourceVersion(RELEASE_21)`, which JDK 17 doesn't have.

### L. Server identity checks TAK.gov's scan recognizes

TAK.gov's pipeline runs Fortify, which reported "Insecure SSL: Server Identity Verification
Disabled" (critical) wherever a TLS socket is used without a call to
`HostnameVerifier.verify(host, session)`. Upstream does check the server, with its own
verifiers. These changes make the same checks through a `HostnameVerifier`:

| File | Change |
|---|---|
| `java/eu/siacs/conversations/xmpp/XmppConnection.java` | `upgradeSocketToTls()`: `XmppDomainVerifier.verify(domain, verifiedHostname, session)` is called from a `HostnameVerifier` lambda. An `SSLPeerUnverifiedException` inside it now means "domain not verified" (`TLS_ERROR_DOMAIN`) instead of `TLS_ERROR`. |
| `java/de/gultsch/minidns/DNSSocket.java` | DNS over TLS to a named server: Conscrypt's `OkHostnameVerifier.strictInstance().verify(host, certificate)` is called from a `HostnameVerifier` lambda. |

DNS over TLS to the network's DNS server by address (Android's Private DNS set to "Automatic")
still verifies nothing, as Android itself does in that mode; Fortify may report that path.

### M. ATAK's map and imports

What ties the chats to ATAK's map (see [10](10-atak-map-integration.md)). Each change calls
`TakConvoCompat.atak()`, null outside ATAK, so the fork behaves as upstream there.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | Hook `ATAK` (`TakConvoCompat.Atak`: `quickMessages`, `isOnMap`, `showOnMap`, `findCoordinates`, `openFile`), record `Coordinates`, helper `linkCoordinates(Spannable)` (a `geo:` `URLSpan` over each position found, where no link is). |
| `java/eu/siacs/conversations/ui/adapter/MessageAdapter.java` | `linkCoordinates(body)` between `Linkify.addLinks` and `FixedURLSpan.fix`. |
| `java/eu/siacs/conversations/utils/GeoHelper.java` | `showLocationIntent()` adds the extra `label` (the sender, or "Me"), which names the marker. |
| `java/eu/siacs/conversations/ui/util/ViewUtil.java` | `view(...)` asks `openFile` first; upstream's body is now `openWith(...)`, which `openFile` runs for "Open with another app". |
| `java/eu/siacs/conversations/ui/ConversationFragment.java` | `onResume()` fills the quick message row (`showQuickMessages`, `appendQuickMessage`). The menu's `action_show_on_map`: visible in a one-to-one chat whose address `isOnMap`, calls `showOnMap`. |
| `res/layout/fragment_conversation.xml` | `quick_messages_scroll` (a `HorizontalScrollView`, gone by default) with the `ChipGroup` `quick_messages`, above `message_input_box`; `snackbar` is above it instead of above the input box. |
| `res/menu/fragment_conversation.xml` | Item `action_show_on_map` (location pin icon, hidden by default). |
| `res/values/takconvo_strings.xml` | **New.** `takconvo_show_on_map`. |

### N. Credentials stored encrypted

The `accounts` table holds the password (with TAK credentials, the TAK server's) and the
SASL2 FAST token. Inside ATAK both go through `TakConvoCompat.CREDENTIALS`, which the plugin
sets to an Android Keystore cipher ([02](02-embedded-engine.md#credentials-in-the-database)).
Unset outside ATAK: stored as upstream stores them.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | Interface `CredentialCipher` (`encrypt`, `decrypt`), hook `CREDENTIALS`, helpers `encryptCredential`, `decryptCredential`. |
| `java/eu/siacs/conversations/entities/Account.java` | `fromCursor`: `decryptCredential` around the `password` and `fast_token` columns. `getContentValues`: `encryptCredential` around both. |

Backups (`ExportBackupWorker`) read the table directly, but they run from Conversations'
settings screen, which isn't available inside ATAK.

### O. OMEMO keys parsed by protobuf 2.5.0

libsignal 2.6.2 parses each received OMEMO key with protobuf-java 2.5.0, as upstream does.
A scan of the packaged libraries (OSV, `civReleaseRuntimeClasspath`) found advisories against
no other library. Tested on the PC against the two jars:

- **CVE-2024-7254** (stack overflow from nested groups): not reachable. libsignal's messages
  use protobuf's full runtime, where unknown groups go through `CodedInputStream.readGroup`
  and its limit of 64 levels; 100 000 levels end in `InvalidMessageException`, which
  `processReceiving` already handles.
- **CVE-2021-22569** (alternating unknown fields): parse time grows with the square of the
  size: 64 KiB took 0.3 s, 256 KiB 3.9 s on a desktop, longer on a phone, on the thread that
  processes the account's stanzas. Any sender can put such a key in a message.

| File | Change |
|---|---|
| `java/eu/siacs/conversations/utils/TakConvoCompat.java` | `MAX_OMEMO_KEY_BYTES = 2048`. Real keys are about 200 bytes; a crafted 2 KiB one parses in 0.3 to 0.7 ms on the desktop. |
| `java/eu/siacs/conversations/crypto/axolotl/XmppAxolotlSession.java` | `processReceiving`: a larger key is skipped like an undecryptable one (the next key is tried, the last one fails with `CryptoFailedException`). Applies outside ATAK too. |

Upgrading protobuf would mean regenerating libsignal's 2.5-generated classes.

## What the plugin relies on

An upstream update can also break the plugin without touching a fork change. These are the
Conversations APIs the plugin (`app/`) uses directly:

| Plugin class | Conversations API |
|---|---|
| `xmpp/EmbeddedConversations` | extends `Conversations`; `attachBaseContext`, `onCreateEmbedded()` |
| `xmpp/EmbeddedXmppService` | extends `XmppConnectionService`; `attachBaseContext` |
| `xmpp/XmppEngine` | `XmppConnectionService`: `onCreate`, `onStartCommand(null, 0, 0)`, `onBind`, `onTaskRemoved`, `onDestroy`, `getAccounts`, `findAccountByJid`, `createAccount`, `updateAccount`, `reconnectAccountInBackground`, `findOrCreateConversation`, `sendMessage`, `getConversations`. `TakConvoCompat` hooks (sections G and I). `AppSettings.CHANNEL_DISCOVERY_METHOD`, `ChannelDiscoveryService.Method`. The default preferences file name (`<package>_preferences`). `Account` (constructor, `setResource`, `setPassword`, `setHostname`, `setPort`, `setOption`/`isOptionSet(OPTION_DISABLED)`, `isOnlineAndConnected`). `AppSettings.SHOW_CONNECTION_OPTIONS`, `BuildConfig.APP_NAME`, `CryptoHelper.random`, `Jid.ofUserInput`, `FileBackend.deleteShareableCopies` |
| `xmpp/CallsignNicknames` | `Account.getDisplayName`/`setDisplayName`, `databaseBackend.updateAccount`, `publishDisplayName`, `checkMucRequiresRename()`, `getConversations`. `Conversation.getBookmark`/`getMucOptions`/`getStatus` (`STATUS_ARCHIVED`). `MucOptions`: `online`, `getActualNick`, `getProposedNickPure`, `getError` (`NICK_IN_USE`, `NONE`), `getUsers` and `User.getFullJid`/`getRealJid`. `MultiUserChatManager.checkMucRequiresRename(conversation)` and `changeUsername` (which rejoins under the new nickname when not joined). `BookmarkManager.create`, `ImmutableBookmark.builder().from(..).nick(..)`. `JidHelper.localPartOrFallback`. That a failed join (conflict) sets `NICK_IN_USE` without notifying observers |
| `config/ConversationsSettings` | the keys, types and values of Conversations' settings (`res/xml/preferences_*.xml`): switches stored as Booleans, lists and numbers as Strings (`AppSettings.getLongPreference` parses them). Check the list against an upstream update's preference screens |
| `xmpp/EmbeddedPendingIntents` | `TakConvoCompat.PendingIntentFactory`; the `eu.siacs.conversations.` package prefix of the components it redirects; `SystemEventReceiver` being a `BroadcastReceiver` with a no-argument constructor, `XmppConnectionService` a `Service` |
| `xmpp/EmbeddedNotifications` | `TakConvoCompat.NotificationFilter`; style `Theme.Conversations3` (the icons' tints); the channel id `messages` and the channel group `chats` |
| `contacts/XmppContacts` | `Conversation`: `getAccount`, `getMode`/`MODE_SINGLE`/`MODE_MULTI`, `getAddress`, `getName`, `unreadCount`, `getMucOptions().online()`. `Account.getRoster().getContacts()`, `Contact.getOption(Contact.Options.TO)`, `getShownStatus()`, `Presence.Availability` |
| `map/MapLocations` | the class names `ui.ShowLocationActivity` and `ui.ShareLocationActivity`; the show intent's extras `latitude`, `longitude`, `label` or its `geo:` data; the share result's extras `latitude`, `longitude`, `accuracy` (what `ConversationFragment` reads for `ATTACHMENT_CHOICE_LOCATION`). `MiniUri.getOrNull`, `MiniUri.Geo` (`getLatitude`, `getLongitude`, `getLabel`) |
| `map/ChatSender` | `XmppConnectionService.attachFileToConversation(conversation, uri, type)` (a Guava `ListenableFuture`), `encryptIfNeededAndSend(message)`, `Message(conversation, body, encryption)`, `Conversation.getNextEncryption`/`getName`/`getStatus`/`getLatestMessage`/`getMode`/`getAddress` |
| `contacts/GroupChatSends` | `Conversation.getMode` (`MODE_MULTI`), `getStatus` (`STATUS_ARCHIVED`), `getAddress`, `getAccount` |
| `map/AtakIntegration` | `TakConvoCompat.Atak` and `Coordinates` (section M) |
| `debug/DebugReceiver` | `Message(conversation, body, ENCRYPTION_NONE, STATUS_RECEIVED)`, `markUnread`, `Conversation.add`, `XmppConnectionService.createMessageAsync`, `getNotificationService().push`, `updateConversationUi` |
| `ui/AccountView` | layout `activity_edit_account` and its view ids (`toolbar`, `editor`, `avater`, `account_jid(_layout)`, `account_password(_layout)` and that they share a parent, `save_button`, `cancel_button`, `stats`, `account_main_layout`, and the ids it hides), string `account_status_connecting`, `Account.State` and `getReadableId()`, style `Theme.Conversations3.Dark`, `AxolotlService`, `UIHelper`, `XmppConnection` and its managers (`Blocking`, `Carbons`, `ClientStateIndication`, `ExternalServiceDiscovery`, `HttpUpload`, `MessageArchive`, `Pep`, `Roster`) |
| `ui/host/EmbeddedActivityHost` | activity class names (the `SUPPORTED` and `FLOATING` lists, `ui.activity.SettingsActivity`, `EditAccountActivity`, `ManageAccountActivity`), `ConversationsActivity.ACTION_VIEW_CONVERSATION` / `EXTRA_CONVERSATION`, styles `Theme.Conversations3` and `Theme.Conversations3.Dialog`, `BaseActivity.embeddedContent` |

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
   the build stays on compileSdk 36. **New Java 21 code** (pattern `switch`, record patterns,
   `case null`, `instanceof` of an expression's own type) gets the Java 17 form of section K:
   the build fails on each, one error per file at a time, so build until it passes. Check that
   new TLS sockets verify the server through a `HostnameVerifier` (section L).
6. **Build and link-check.**
   ```bash
   ./gradlew assembleCivDebug                       # for ATAK 5.8, then each other version:
   ./gradlew assembleCivDebug -PatakVersion=5.6.0   # online the first time: new libraries
   java tools/AtakLinkCheck.java <that ATAK SDK>/atak.apk app/build/outputs/apk/civ/debug/<apk>
   ```
   Fix every `AbstractMethodError` finding: implement all methods, and replace lambdas by
   anonymous classes for interfaces ATAK provides. Fix `NoSuch...Error` findings by using another
   overload, or accept them in `tools/atak-link-ignore.txt` with a reason.
7. **Check for new direct `Activity.requestPermissions` calls**
   (`grep -rn "[^.a-zA-Z]requestPermissions(" conversations/src`) and route them through
   `ActivityCompat`.
8. **Check new activities.** Any activity that should open inside the pane goes into
   `EmbeddedActivityHost.SUPPORTED` once tested, and into `FLOATING` too if its theme is a
   dialog; others show "Not available inside ATAK". A new entry point to calls must be hidden
   like the toolbar's (section H).
   **Check new PendingIntents and notification paths**
   (`grep -rn "PendingIntent.get\|\.notify(\|pushDynamicShortcut" conversations/src/main`):
   PendingIntents aimed at Conversations' components go through `TakConvoCompat`, and
   notifications through `NotificationService.notify`. Check that the service still calls its
   UI listeners from `updateAccountUi`/`updateConversationUi`/`updateRosterUi`, where the
   observer hooks are.
9. **Check the location and file paths** (section M): that locations still open through
   `ShowLocationActivity`/`GeoHelper` and are shared through `ShareLocationActivity` with the
   same extras, that received files still open through `ViewUtil.view`, and that message text
   is still linkified in `MessageAdapter` before `FixedURLSpan.fix`.
   **Check the settings `ConversationsSettings` passes on**: compare its list with the
   switches and lists in `res/xml/preferences_*.xml`. Add new settings that work inside ATAK,
   drop removed ones, and keep the allowed values of lists in step. Update the template's list.
10. **Test on the device**, see [07](07-development-and-testing.md#device-test-checklist).
11. **Update this document**: the base tag at the top, the file tables and the dependency table.
