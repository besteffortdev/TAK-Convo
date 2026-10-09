# 11 — Findings

What we learned embedding a full Android app in ATAK, roughly in the order it bit us.

## ATAK as a platform

1. **Release ATAK only loads TAK.gov-signed plugins.** Play Store ATAK-CAN 5.8 logs
   `signature mismatch` and refuses a self-signed plugin whatever its `plugin-api`. Its API is
   also ProGuard-obfuscated (`AtakBroadcast.getInstance()` is `a()`): release plugins are built
   with the SDK's mapping of that exact version. Use the SDK's developer `atak.apk` until the
   plugin goes through TAK.gov signing.
2. **`artifacts.tak.gov` is behind an Appgate SDP gateway.** The build uses the ATAK SDKs
   offline (`atak.sdk.5.6`, `atak.sdk.5.8`). Each ATAK version ships its own AndroidX and
   coroutines versions, which the plugin must compile against: one APK per ATAK version.
3. **A plugin's components never run.** Activities, services, receivers and providers in the
   plugin's manifest are dead: the code runs in ATAK's process, under ATAK's identity. Everything
   Conversations gets from Android had to be supplied by hand ([02](02-embedded-engine.md),
   [04](04-chat-pane-activity-host.md)).
4. **Plugin classes load parent-first.** For any class ATAK also has (AndroidX, OkHttp,
   Kotlin...), ATAK's copy is used. Compile against ATAK's exact versions and don't package
   them (`gradle/atak-runtime.gradle`). This is why OkHttp stays at 4.11 and several AndroidX
   libraries are held back ([06](06-atak-runtime-and-classloading.md)).
5. **ATAK's AndroidX interfaces have no default methods at runtime.** ATAK's R8 build desugared
   them into abstract methods and dropped some companions. Any plugin class implementing them
   must implement every method: a lambda for `OnBackStackChangedListener` crashed ATAK with
   `AbstractMethodError` the moment a chat opened. `tools/AtakLinkCheck.java` finds all such
   cases from the two APKs.
6. **The plugin's `java.time` is `j$.time`** (core library desugaring), so calls into ATAK's
   OkHttp with a `Duration` don't link. Use the `TimeUnit` overloads.
7. **AGP 8 only.** ATAK's `takdev` Gradle plugin needs AGP 8, so compileSdk 36. Upstream
   Conversations 2.20.4 targets SDK 37; its SDK 37 constants moved to `TakConvoCompat`, and
   WebRTC stays at 129.
8. **ATAK drop-downs with `ignoreBackButton`** are kept on ATAK's stack under other drop-downs,
   but back never closes them: `onBackButtonPressed()` is called and the close is refused. The
   pane closes itself when Conversations has nothing left to go back to. A hidden drop-down
   comes back on top with `showDropDown` (`unhideDropDown` unhides the top one only). Other
   drop-downs it opens over are closed unless retained: the account pane has `Pane.RETAIN`.
   ATAK restores a drop-down's view state when it shows again, and an `EditText`'s restored
   text fires its text watchers.
9. **`adb install -r` makes ATAK forget to load the plugin** (`shouldLoad-<package>` is reset,
   Android 16). **Killing ATAK within 10 s of start** leaves `pluginSafeMode` set and ATAK asks
   whether to load plugins. `tools/deploy.ps1` handles both.
10. **ATAK's FileProvider (`<package>.provider`) serves external storage only**, and a plugin
    can't declare its own. Private attachments are served from copies in ATAK's external cache.
11. **ATAK requires camera and microphone at start-up**, so Conversations' permission prompts
    rarely fire; when they do they must go through ATAK's activity.

## Running an app's activities inside another app's window

12. **`Instrumentation.newActivity` + a stand-in parent works.** Activities attach with
    their own (never shown) window. The parent receives `startActivity`/`finish`, and its window
    gives dialogs ATAK's token. The views are moved into the pane
    ([04](04-chat-pane-activity-host.md)).
13. **Whatever a view asks of its window reaches ATAK's**: context menus came up with
    ATAK's resources (crash on the plugin's string ids) and selections went to ATAK's activity.
    The pane root sends context menus to the embedded activity's own decor view, and gives
    action-mode callbacks a menu inflater with the plugin's resources.
14. **`Activity.requestPermissions` throws in an embedded activity** (no `ActivityThread`).
    Requests go through `ActivityCompat` and a `PermissionCompatDelegate` that asks ATAK's
    `ActivityResultRegistry`. Activity results travel the same way.
15. **Embedded activities must follow ATAK's lifecycle**, not only the pane's visibility:
    a chat left "resumed" behind other apps is marked read and its notifications are held back.
16. **Autofill and content capture must be hidden** from embedded activities: the system server
    rejects sessions for activities it didn't create.
17. **Size resources for the pane, not the screen.** The pane (40 % of the map) is about 325 dp
    wide on a phone. Conversations' attachment grid didn't fit and became a scrolling row.
18. **No `recreate()`**: AppCompat's night-mode switch would recreate the activity; the plugin
    fixes dark mode instead, as ATAK is always dark.
19. **Plugin views outside an AppCompat activity lose Material styling**:
    `MaterialComponentsViewInflater` has to be installed as the inflater factory by hand.
20. **Conversations must not change the process**: its `Application.onCreate` installs Conscrypt
    as the first security provider and a global exception handler. The fork has an embedded
    variant without them.
21. **A plugin's resource id means nothing to an ATAK context.** `Toast.makeText(atak,
    R.string.x, ...)` looks the id up in ATAK's resources and throws
    `Resources$NotFoundException`. The "Not available inside ATAK" toast did that, so every
    unavailable button (the voice message button among them) crashed ATAK. Resolve with the
    plugin's context and pass the text.
22. **Dialog-themed activities can float in the pane.** The voice recorder is shown centred over
    a scrim, and the chat under it stays visible and paused. The `FLAG_KEEP_SCREEN_ON` it sets
    on its unseen window is moved to its views in ATAK's window.
23. **Switch a feature off rather than refuse its screen.** Conversations' call button sends the
    call proposal, and the contact's phone rings, before it opens the call screen. Calls are
    hidden, not advertised and not accepted, reusing upstream's "no calls over Tor" paths.
24. **A phone app's UI is oversized in a side pane.** A lower `densityDpi` in the embedded
    screens' configuration (0.9 × ATAK's) scales all of Conversations' dp and sp at once; only
    the few sizes still out of proportion (avatars, attachment buttons, the input row) were
    changed in the fork.

## Server and provisioning

25. **Openfire holds a killed client's session detached and doesn't answer a bind for the same
    resource** until it drops it (the new stream idles out after 10 s first). A fresh resource
    on every ATAK start makes login immediate.
26. **TAK server credentials arrive after the plugin starts.** Provisioning re-runs on TAK
    server connection changes and never falls back to another identity in the meantime. With
    several TAK servers, which one connects first changes between starts: the one on the XMPP
    domain is used first (or the one `takconvo_xmpp_tak_server` names), and another one's
    password goes to the XMPP server only after the user's **Connect**.
27. **The organisation's CA is already in ATAK's TAK server truststore**, so reusing it as a
    trust source needs no extra provisioning. `CertificateManager.getLocalTrustManager(String)`
    rebuilds from ATAK's database on each call, so newly imported truststores count.
28. **`.pref` files may carry Booleans as strings**, which breaks preference check boxes; they are
    normalised on load.

## Notifications, alarms and contacts

29. **Registering as one of Conversations' UI listeners makes it believe it is on screen.** The
    engine did, to follow changes, so Conversations never told the server it was inactive and
    silenced every notification while no chat was open. The fork calls an observer from the same
    places instead ([02](02-embedded-engine.md#changes-and-threads)).
30. **Every PendingIntent aimed at the embedded app's components is silently dropped**:
    notification taps and actions, and all `AlarmManager` alarms, i.e. Conversations' pings and
    reconnection timers. They go through ATAK's activity (its `internalIntent` extra, like ATAK's
    own `NotificationUtil`) and a receiver registered in ATAK's process instead.
31. **An embedded app's notifications are ATAK's.** Resource icons resolve in ATAK's package: a
    random ATAK drawable, or, if the id doesn't exist there, "Bad notification posted", which
    kills ATAK. They are posted with bitmap icons. The system shows them as ATAK's (name, app
    icon on Samsung), and Conversations' notification channels are listed under ATAK.
32. **Shortcuts an embedded app publishes are ATAK's launcher shortcuts**, opening activities ATAK
    doesn't have. Conversations publishes them for frequent contacts; that is off.
33. **ATAK's contacts take plugin handlers per connector type, ahead of their own.** One for
    `connector.xmpp` replaces ATAK's external-app handler; its `NotificationCount` and `Presence`
    features feed the contact rows, and `Contacts.updateTotalUnreadCount()` sets ATAK's Contacts
    and Chat button badges. A plugin's toolbar button is a `NavButtonModel` found by the
    `ToolbarItem`'s identifier, which carries a badge count
    ([08](08-contacts-and-notifications.md)).
34. **A plugin can add its own contacts.** An `IndividualContact` with no map item opens its
    default connector when tapped, takes its unread count from the connector's handler, and
    shows ATAK's chat-room icon with the `fakeGroup` extra. It sits in the root group unless
    `getParentUID()` is overridden, as ATAK's own TADIL-J contacts do. The XMPP group chats are
    listed that way, at the top of the list with the users.
35. **ATAK paints tool icons in one color, from their alpha.** A colored icon shows as a plain
    square in the tool menu. The plugin's app icon (Plugin Manager) is Conversations' launcher
    icon; its tool icon is Conversations' speech bubble (`drawable/ic_takconvo`, from the
    monochrome launcher icon), drawn to a bitmap for the `ToolbarItem`.

## Maintenance

36. **Every change to Conversations is marked and documented**
    ([05](05-conversations-fork.md)), and `tools/fork-diff.sh` regenerates the exact
    diff against upstream; the doc has the procedure to move to a newer release.
37. **Upstream Conversations has paths longer than 260 characters**: clone it with
    `core.longpaths=true` on Windows or files silently go missing.

## Security

38. **A `.pref` file can write any preferences file in ATAK**, a plugin's included:
    `PreferenceControl` opens whatever `<preference name>` says. And ATAK applies the `.pref`
    in an imported data package without asking. What decides where credentials go, or proves a
    request is genuine, lives in a file in ATAK's `no_backup/` instead
    ([03](03-provisioning-and-trust.md#approving-the-server)).
39. **Any app can send ATAK's internal broadcasts**: ATAK's launcher activity rebroadcasts the
    `internalIntent` of any intent. Plugin receivers that act use an action with a random part,
    or check a token ([09](09-code-guidelines.md#android)).
40. **Plugins take part in Clear Content through `ClearContentRegistry`**: ATAK calls each
    listener on its clear task's thread, before it clears its own credentials and preferences,
    then quits ([02](02-embedded-engine.md#ataks-clear-content)).
41. **Conversations' OMEMO goes through protobuf-java 2.5.0** (libsignal 2.6.2). Its
    stack-overflow advisory isn't reachable (64 nesting levels at most), but crafted keys parse
    in quadratic time; keys over 2 KiB are skipped
    ([05](05-conversations-fork.md#o-omemo-keys-parsed-by-protobuf-250)).
42. **Only a package reads its own managed configuration** (app config), and a plugin's code
    runs as ATAK: `RestrictionsManager` in ATAK's process returns ATAK's. A provider in the
    plugin's package, in a process of its own, reads it and answers ATAK only. Android tells
    only a running process of the package about changes, so the plugin reads it again each time
    ATAK comes to the front
    ([03](03-provisioning-and-trust.md#managed-configuration-mdm)).
