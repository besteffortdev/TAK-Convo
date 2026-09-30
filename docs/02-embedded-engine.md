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
    service.onCreate()                                 # opens the DB, loads accounts
    listen to account and conversation changes
    settings = XmppSettings.load(); applyTrust()       # before anything connects, see 03
    for account in service.accounts:
        account.resource = "TAK Convo." + random(3)    # see "resource" below
    service.onStartCommand(null)                       # connects enabled accounts
    provision()                                        # see 03
    watch takconvo_* preferences, TAK server connections, device trust store -> provision()

XmppEngine.shutdown():
    stop watching
    advertise(null)                                    # clear saXmppUsername
    service.onTaskRemoved(null)                        # logs out and saves, as on swipe-away
    service.onDestroy()
```

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

## Listeners and threads

Upstream calls `OnAccountUpdate` and `OnConversationUpdate` from its worker threads.
`XmppEngine` re-posts them on the main thread to its own `Listener`s (the plugin, the account
pane). Every method of `XmppEngine` must be called on the main thread, as upstream's service
expects.
