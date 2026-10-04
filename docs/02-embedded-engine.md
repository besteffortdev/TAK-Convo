# 02 — Running Conversations' engine inside ATAK

Conversations' XMPP engine is `XmppConnectionService`, an Android `Service` that the system
creates, and that everything else reaches through `startService` / `bindService`. Inside ATAK
nothing of the plugin's package is ever started by the system, so the plugin creates these
objects itself and makes them believe they were.

Classes: `plugin/xmpp/XmppEngine`, `EmbeddedContext`, `EmbeddedConversations`,
`EmbeddedXmppService`.

## The context

Every Android object reaches the platform through its `Context`. `EmbeddedContext` is a
`ContextWrapper` whose base is **ATAK's application context**, with some calls answered
differently:

| Calls | Answered by | Why |
|---|---|---|
| `getResources`, `getAssets`, `getClassLoader`, `getTheme` | the plugin's context | Conversations' code and resources are in the plugin APK |
| `getPackageName`, system services, permission checks, `getContentResolver` | ATAK (base) | the process belongs to ATAK; asking for the plugin package would fail |
| `getSharedPreferences(n)`, `getDatabasePath(n)`, `openOrCreateDatabase(n)`, `getDir(n)` | ATAK, name prefixed `takconvo_` | nothing collides with ATAK's own files |
| `getFilesDir`, `getCacheDir`, `getNoBackupFilesDir`, `getExternalCacheDir`, `getExternalFilesDir` | ATAK's directory + `/takconvo` | same |
| `startService`, `startForegroundService`, `stopService`, `bindService`, `unbindService` aimed at `XmppConnectionService` | the in-process service (`ServiceRouter`) | there is no Android service to start |
| `getApplicationContext` | the `EmbeddedConversations` instance | upstream code casts it to `Conversations` |

The engine runs on the **root** context. Conversations' activities get **UI contexts** derived
from it (`forUi`), which share the application, storage and service routing, but carry a
configuration of their own (night mode, the pane's size) and an empty theme to start from.
They also return `null` for the autofill and content capture services: those register the
activity with the system server, which rejects an activity it didn't create.

```text
class EmbeddedContext extends ContextWrapper(base = atak.applicationContext):
    getResources()           -> plugin (or plugin.createConfigurationContext(uiOverride))
    getClassLoader()         -> plugin.classLoader
    getSharedPreferences(n)  -> base.getSharedPreferences("takconvo_" + n)
    getDatabasePath(n)       -> base.getDatabasePath("takconvo_" + n)
    getFilesDir()            -> base.getFilesDir() / "takconvo"          # mkdirs
    getApplicationContext()  -> root.application ?: root

    startService(intent):
        if router.handles(intent):                 # component == XmppConnectionService
            post(main) { router.startCommand(intent) }   # -> service.onStartCommand(intent)
            return intent.component
        return base.startService(intent)

    bindService(intent, conn, flags):
        if router.handles(intent):
            remember conn
            binder = router.bind(intent)           # -> service.onBind(intent)
            post(main) { conn.onServiceConnected(intent.component, binder) }
            return true
        return base.bindService(intent, conn, flags)

    forUi(display, override):                      # a context per embedded activity
        return new EmbeddedContext(root, base.createDisplayContext(display), override)
```

## Application and service

`Conversations` (the `Application`) and `XmppConnectionService` are subclassed only to call the
protected `attachBaseContext`:

```java
final class EmbeddedConversations extends Conversations {
    EmbeddedConversations(EmbeddedContext base) { attachBaseContext(base); }
    void start() { onCreateEmbedded(); }          // fork addition, see 05
}
final class EmbeddedXmppService extends XmppConnectionService {
    void attach(Context base) { attachBaseContext(base); }
}
```

`onCreateEmbedded()` is the part of `Conversations.onCreate()` that is safe in ATAK's process.
The rest would change ATAK: installing Conscrypt as the first security provider for the whole
process, a global uncaught-exception handler, and AppCompat's default night mode.

The service is never attached by the system, so `Service`'s final methods that go through the
system (`stopSelf`, `startForeground`) would throw. The fork skips them when
`TakConvoCompat.EMBEDDED` is set; ATAK's own foreground service keeps the process alive. Other
calls that assume Conversations' own package (enabling UnifiedPush or profile-picture
components, registering a telecom `ConnectionService`) are skipped the same way.

## Start and stop

```text
XmppEngine.start(atakContext, pluginContext):          # once per process
    TakConvoCompat.EMBEDDED = true                     # before any Conversations code runs
    context     = new EmbeddedContext(atakContext, pluginContext)
    application = new EmbeddedConversations(context); context.setApplication(application)
    application.start()
    FileBackend.deleteShareableCopies(context)         # see 04, files
    service = new EmbeddedXmppService(); service.attach(context)
    context.setServiceRouter(-> service)
    TakConvoCompat.PENDING_INTENTS = EmbeddedPendingIntents   # see "PendingIntents" below
    TakConvoCompat.NOTIFICATIONS   = EmbeddedNotifications    # see 08
    TakConvoCompat.OBSERVER        = observer                 # see "Changes and threads"
    TakConvoCompat.CREDENTIALS     = KeystoreCredentials      # see "Credentials in the database"
    nicknames = new CallsignNicknames(...)             # see 03, the callsign as nickname
    ConversationsSettings.apply(ATAK's prefs, Conversations' prefs)   # .pref-set, see 03
    service.onCreate()                                 # opens the DB, loads accounts
    if a credential was read unencrypted: save every account again    # encrypts it
    approvedServer = PrivateFiles "approved_server"    # see 03, approving the server
    initial = load()                                   # on the main thread, this once
    applyTrust(initial if its server is approved)      # before anything connects, see 03
    for account in service.accounts:
        account.resource = "TAK Convo." + random(3)    # see "resource" below
    service.onStartCommand(null)                       # connects enabled accounts
    apply(initial)                                     # the account, see 03
    watch ATAK's preferences:
        takconvo_xmpp_*           -> provision() (debounced 750 ms)
        takconvo_conversations_*  -> ConversationsSettings.apply() (debounced 750 ms)
        locationCallsign          -> nicknames.sync()
    watch TAK server connections and the device trust store -> provision()

XmppEngine.shutdown():
    stop watching; nicknames.stop()                    # its pending join re-checks
    advertise(null)                                    # clear saXmppUsername
    service.onTaskRemoved(null)                        # logs out and saves, as on swipe-away
    service.onDestroy()
    TakConvoCompat.OBSERVER = NOTIFICATIONS = PENDING_INTENTS = null
    # CREDENTIALS stays: the logouts run on other threads and may still save their account
    stop receiving delivered PendingIntents
```

Upstream ignores `onTaskRemoved` while its foreground service is on, which it always is on
Android 8 and later. The fork makes it log out when embedded: the plugin stopping is the end
of the engine, and a clean `</stream>` doesn't leave a session detached on the server. (Quitting
ATAK from its menu now logs "sending stream close" and "received stream close".)

### A fresh resource on every start

When ATAK is killed, the server keeps the XMPP session detached, waiting for a stream
management resumption. Conversations can't resume it after a process restart. Openfire then
doesn't answer a bind for the **same** resource until it drops the old session. It closes the
new stream as idle (10 s) first, so every login after a restart hung and retried. A new random
resource per ATAK start avoids the conflict, and login takes about 0.3 s. The old session
expires on its own.

### Advertising the address

ATAK reads the preference `saXmppUsername` and sends it in this device's SA as
`<contact xmppUsername="...">`, like the TAK Chat plugin does. `provision()` sets it to the
account's bare JID, and clears it whenever no account is provisioned or the engine stops.

## Changes and threads

The plugin needs to know when accounts, conversations, the roster or the unread count change:
the account pane, the unread badges and the contact list follow them. The obvious way,
registering as one of the service's UI listeners (`setOnConversationListChangedListener`...),
is wrong. The service counts those listeners to decide whether Conversations is on screen
(`checkListeners()`). With the engine always registered, it believed it always was:

- it never sent the server `csi/inactive` or went idle, and
- it silenced every notification whenever no chat was open ("chat overview is in
  foreground"), so new messages never made a sound.

Instead the fork calls a `TakConvoCompat.Observer` from the same places it calls its UI
listeners, and the embedded activities alone register as UI listeners, as they do upstream
from `onStart`/`onStop`. Conversations is then "in the foreground" exactly while its pane is
visible and ATAK is started (see 04), and switches to the background when the pane closes.

```text
fork, XmppConnectionService:
    updateAccountUi():        ...UI listeners...; OBSERVER?.onAccountsChanged()
    updateConversationUi():   ...UI listeners...; OBSERVER?.onConversationsChanged()
    updateRosterUi():         ...UI listeners...; OBSERVER?.onRosterChanged()
    updateUnreadCountBadge(): if the count changed: OBSERVER?.onUnreadCountChanged(count)
                              # upstream sets the launcher badge; ATAK's launcher icon isn't ours

XmppEngine.observer (worker threads):
    on any of them: dispatchChanged()
    onUnreadCountChanged(n): unreadCount = n; dispatchChanged()

dispatchChanged():                    # changes come in bursts, e.g. catching up after a login
    if no dispatch is pending: post(main) {
        nicknames.sync()                  # e.g. the account came online: publish the callsign
        for l in listeners: l.onXmppStateChanged()
    }
```

Every method of `XmppEngine` must be called on the main thread, as upstream's service expects.

## PendingIntents

Conversations hands the system `PendingIntent`s aimed at its own components: notification taps
open `ConversationsActivity`, notification actions start `XmppConnectionService`, and
`AlarmManager` alarms (idle pings, the ping after a connectivity change, reconnection timers)
go to `SystemEventReceiver`. None of those components exist in ATAK's package, so the system
dropped them all. Until this was found, **no alarm ever reached the engine**: pings and
scheduled reconnects only happened when something else woke it.

The fork creates every such `PendingIntent` through `TakConvoCompat.getActivity/getService/
getBroadcast`, and the plugin's `EmbeddedPendingIntents` redirects the ones aimed at
Conversations' classes:

```text
getActivity(ctx, code, intent, flags):              # a notification tap
    if intent isn't aimed at eu.siacs.conversations.*: return PendingIntent.getActivity(...)
    open  = Intent(ACTION_OPEN) + intent's extras + {class, action, token}
    front = Intent(component = ATAK's main activity, CLEAR_TOP | SINGLE_TOP,
                   action = "OPEN:" + class + ":" + action)   # keeps PendingIntents apart
    front.internalIntent = open          # ATAKActivity.onNewIntent rebroadcasts it in-process
    return PendingIntent.getActivity(atak, code, front, flags)

getService / getBroadcast(ctx, code, intent, flags):  # notification actions, alarms
    if intent isn't aimed at eu.siacs.conversations.*: unchanged
    wrapped = copy(intent), component = null, package = ATAK,
              action = ACTION_DELIVER, data = "takconvo://deliver/<class>/<action>"
    return PendingIntent.getBroadcast(atak, code, wrapped, flags)

receiver (registered on ATAK's context while the engine runs, not exported: through
          ContextCompat, which before Android 13 requires ATAK's signature permission
          DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; a plain registerReceiver would be open
          to any app there):
    on ACTION_DELIVER with scheme takconvo:
        intent = copy(received)           # keeps what the system added: the reply text
        intent.action = original action; component = (ATAK, class)
        if class is a Service:           engineContext.startService(intent)   # routed, see above
        if class is a BroadcastReceiver: new class().onReceive(engineContext, intent)
```

The data URI does two jobs: one filter (`ACTION_DELIVER` + scheme `takconvo`) matches every
action, and PendingIntents stay as distinct as their original targets were (a PendingIntent's
identity includes its data). ATAK's own notifications use the same `internalIntent`
mechanism (`NotificationUtil`), so a tap behaves like a GeoChat one: ATAK comes to the front
and the plugin shows the chat. See [08](08-contacts-and-notifications.md).

ATAK's launcher activity rebroadcasts the `internalIntent` of any app's intent, so any app
could send `ACTION_OPEN` and have the chat pane start a Conversations screen of its choosing,
with its extras. The `token` extra keeps that out: 16 random bytes made once per installation,
kept in `no_backup/takconvo_plugin/open_token` (a `.pref` file can't write there, see
[03](03-provisioning-and-trust.md#approving-the-server)). `unwrapActivity` drops an
`ACTION_OPEN` without it. Kept across restarts, so the notifications left in the shade still
open their chat.

## Credentials in the database

Conversations stores each account's password, and its SASL2 FAST token, in its `accounts`
table. With TAK credentials that is the TAK server password, in ATAK's data directory, where
any copy of that directory shows it (a backup, `run-as` on a debuggable ATAK, a rooted phone). The fork hands both columns to
`TakConvoCompat.CREDENTIALS` (see [05](05-conversations-fork.md#n-credentials-stored-encrypted)):

```text
KeystoreCredentials (plugin):
    key: AES-256-GCM "takconvo_credentials" in the Android Keystore, made on first use; it
         never leaves the Keystore (or the secure hardware behind it)
    encrypt(value): "takconvo-gcm1:" + base64(iv + ciphertext)
                    if that fails: "" (never in clear; provisioning has the original)
    decrypt(stored): without the prefix: stored before encryption, returned as it is and
                     noted, so the engine saves every account again at start
                     if that fails (the key is gone): "", and provisioning sets it again
```

The originals stay in ATAK's own encrypted credential store (TAK server logins and the
`takconvo.xmpp` login), so losing the stored copy only means waiting for provisioning. The
copy is needed at all because, at ATAK start-up, the TAK credentials often aren't available
yet: the stored account connects without them.

## ATAK's Clear Content

ATAK's **Clear Content** deletes ATAK's data, then quits. Plugins take part through `ClearContentRegistry`: ATAK's `ClearContentTask` calls each
listener on its background thread, before it clears ATAK's credential store and preferences.
`TakConvoPlugin` registers one at start, even without an engine:

```text
wipe():                                    # ClearContentTask's thread
    on the main thread, waiting up to 15 s: stopAll()      # as onStop: panes, contacts,
                                                           #   map integration, engine logout
    XmppEngine.wipe():
        cancel Conversations' notifications                # they show message text
        EmbeddedContext.deleteAll():                       # ATAK's data dir, takconvo prefix
            databases takconvo_*                           # messages, contacts, OMEMO keys
            shared_prefs takconvo_*.xml                    # Conversations' and ours
            files/, cache/, no_backup/ takconvo, app_takconvo_*
            Android/data/<package>/files|cache/takconvo    # received files, avatars
        delete the Keystore key                            # earlier copies stay unreadable
        delete no_backup/takconvo_plugin                   # approved server, open token
        delete the takconvo.xmpp login
```

After it, ATAK clears its preferences (the `takconvo_*` settings among them) and its
credential store, then quits. The account and its messages on the server are untouched: the
server's archive (MAM) brings history back if the device signs in again. Files saved to shared
storage (Conversations' `use_shared_storage`, off and not a supported setting) aren't tracked
and stay. `DEBUG_CLEAR_CONTENT` runs the same listener without clearing ATAK
([07](07-development-and-testing.md#debug-broadcasts)).
