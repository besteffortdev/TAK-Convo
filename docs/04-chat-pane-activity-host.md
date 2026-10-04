# 04 — The chat pane: Conversations' activities in an ATAK drop-down

The chat list, the chats, contact and channel details, search and so on are **Conversations'
own activities**, unchanged apart from the few fork changes listed at the end. The plugin
creates them itself, drives their lifecycle, and shows their views in an ATAK side pane.

Classes: `plugin/ui/host/EmbeddedActivityHost`, `HostParent`, `PaneFrame`, `ChatDropDown`;
`plugin/ui/ConnectionBanner`.

## Why activities can't just be started

`startActivity` asks the system to start a component declared in an installed package's
manifest, in that package's process. The plugin's activities would start in the plugin's own
process: no ATAK, no engine, no map. ATAK's manifest doesn't declare them. So the plugin builds
activity objects the way `ActivityUnitTestCase` and `LocalActivityManager` did:
`Instrumentation.newActivity()` creates and attaches an activity with a window of its own,
`Instrumentation.callActivityOn*()` drives its lifecycle. The activity's window is **never
shown**: its views are moved into the pane.

```mermaid
flowchart LR
    subgraph ATAKWindow["ATAK's window"]
        DD["ATAK drop-down (right side)"]
        PF["PaneFrame"]
        DD --> PF
        PF --> V1["content of activity 1 (GONE)"]
        PF --> V2["content of activity 2 (VISIBLE, top)"]
    end
    subgraph Hidden["never attached"]
        W1["activity 1 window / DecorView"]
        W2["activity 2 window / DecorView"]
    end
    A1["activity 1"] --- W1
    A2["activity 2"] --- W2
    A1 -. views moved .-> V1
    A2 -. views moved .-> V2
    A1 & A2 -->|mParent| HP["HostParent"]
    HP -->|startActivity, finish| H["EmbeddedActivityHost<br/>(back stack)"]
```

## Creating an activity

```text
EmbeddedActivityHost.create(className, intent, caller, requestCode):
    attachToAtak()                               # lifecycle observer + permission delegate
    cls  = pluginClassLoader.loadClass(className)
    base = engine.newUiContext(display, paneConfiguration())    # see "configuration"
    floating = className in FLOATING                     # see "Floating activities"
    info = ActivityInfo(package = ATAK, name = className,
                        theme = floating ? Theme.Conversations3.Dialog : Theme.Conversations3,
                        flags = HARDWARE_ACCELERATED)
    intent.component = (ATAK package, className)
    activity = instrumentation.newActivity(cls, base, token = null, engine.application,
                                           intent, info, title = "", parent = hostParent,
                                           id = className, lastNonConfigurationInstance = null)
    activity.setTheme(info.theme)
    stack.push(Record(activity, caller, requestCode, floating))  # before onCreate: it may
    instrumentation.callActivityOnCreate(activity, null)          # start or finish
    instrumentation.callActivityOnPostCreate(activity, null)
    record.content = floating ? floatOver(takeContent(activity)) : takeContent(activity)
    if activity.window has FLAG_KEEP_SCREEN_ON:          # its own window is never shown
        record.content.keepScreenOn = true               # keeps ATAK's on while visible
    paneFrame.addView(record.content, MATCH_PARENT)

takeContent(activity):
    view = activity.window.decorView.findViewById(android.R.id.content)
    climb while the parent is AppCompat's FitWindowsLinearLayout / FitWindowsFrameLayout /
          ActionBarOverlayLayout                   # keeps the action-mode bar containers
    detach view from its parent
    if view has no background: view.background = activity's windowBackground
    activity.embeddedContent = view                # fork: BaseActivity.findViewById looks here
    return view
```

The window itself stays unattached. Attaching an activity's decor view to ATAK's window would
reconfigure ATAK's view root.

## The parent activity

Android still routes what a child activity can't do itself through its **parent**, as it did
for `ActivityGroup`. `HostParent` is an `Activity` that is never attached or shown. It only
answers those calls:

| Call on the child | Goes to the parent as | `HostParent` does |
|---|---|---|
| `startActivity`, `startActivityForResult` | `startActivityFromChild` | `host.startFromChild(child, intent, requestCode)` |
| `finish()` | `finishFromChild` | `host.finishFromChild(child)` |
| window creation | `getWindow()` (container) | ATAK's window, so the child's dialogs and popups get ATAK's window token |
| theme creation | `getTheme()` | an empty theme, so only the activity's own style applies |
| `setRequestedOrientation` | same | ignored: the orientation is ATAK's |
| `startIntentSender...` | same | not supported |

## The back stack

```text
startFromChild(child, intent, requestCode):
    post { start(record of child, intent, requestCode) }   # after the caller's callback:
                                                            # it often finishes right after
start(caller, intent, requestCode):
    name = intent.component.className
    if name not in Conversations' package:  startExternal(...); return
    if name is EditAccountActivity / ManageAccountActivity:  listener.onShowAccount(); return
    if name is ui.activity.SettingsActivity:                  listener.onShowSettings(); return
    if name not in SUPPORTED:
        toast "Not available inside ATAK"; deliver RESULT_CANCELED to caller; return
    existing = record of that class on the stack
    if existing and (name in SINGLE_INSTANCE or intent has FLAG_ACTIVITY_CLEAR_TOP):
        destroy every record above existing
        pause(existing); onNewIntent(existing, intent); settle(existing)
        return
    pause(top)
    record = create(name, intent, caller, requestCode)    # on failure: toast, cancel, settle(top)
    settle(record)
    if not record.floating:
        for each record below it that is shown:            # the previous one, and what showed
            content.visibility = GONE; stop(it)            # through a floating one

finishFromChild(child):       # marks it finishing, then posts:
    finish(record):
        stack.remove(record); destroy(record)
        deliverResult(record.caller, record.requestCode,
                      activity.mResultCode, activity.mResultData)   # read by reflection
        if record was on top:
            if the stack is empty: listener.onHostEmpty()  # -> close the pane
            else: settleShown()

settleShown():                # the top, and below a floating record what it floats over
    for r from the top down:
        r.content.visibility = VISIBLE; settle(r)
        if not r.floating: break
```

The toast's text comes from the plugin's resources. `Toast.makeText(atak, resId, ...)` would
look the id up in ATAK's resources and throw `Resources$NotFoundException`. Until 2026-09-30
that crashed ATAK whenever an unavailable activity was asked for, e.g. the voice message
button.

`SUPPORTED` lists the activities allowed to open in the pane:

| Tested on a device | Allowed, not yet tested |
|---|---|
| `ConversationsActivity`, `StartConversationActivity`, `ConferenceDetailsActivity`, `SearchActivity`, `RecordingActivity` (floating), `ScanQrCodeActivity`, `PublishProfilePictureActivity` (picking without the cropper, see [05](05-conversations-fork.md#e-activities-shown-in-an-atak-pane)) | `ContactDetailsActivity`, `TrustKeysActivity`, `AddReactionActivity` (the full emoji picker; quick reactions are a dialog and work), `MucUsersActivity`, `ChooseContactActivity`, `ChannelDiscoveryActivity`, `EditHistoryActivity`, `MediaBrowserActivity`, `BlocklistActivity` |

A failure in `onCreate` is caught (toast, `RESULT_CANCELED`); a failure later, e.g. in a click
handler, would still crash ATAK, so test an activity before relying on it.
`ConversationsActivity` and `StartConversationActivity` are `SINGLE_INSTANCE`, like upstream's
`singleTask`/`singleTop`.

The plugin starts activities from outside the same way, with no caller: `showMain()` (the chat
list, if nothing is shown), `showConversation(uuid)` (a TAK user's XMPP connector), and
`startActivity(intent)` for whatever a tapped notification aimed at (see
[08](08-contacts-and-notifications.md)). The account screen of the error notification is
routed to the account pane like any other start, and the plugin closes the chat pane again if
nothing ended up in it (`isEmpty()`).

The chats shown belong to one account. When the engine's account changes or goes away (logging
out, XMPP turned off, another account provisioned), `TakConvoPlugin.onXmppStateChanged` closes
the chat pane and destroys its activities. Logging out happens in the account pane, opened over
the chat pane, which ATAK keeps on its drop-down stack (`DropDownManager.closeDropDown` also
removes a hidden one). Without this, closing the account pane brought the old chats back, and
back went through them one by one.

### QR codes

"Show QR Code" (the chat list's QR menu, contact and channel details) is a dialog, which works
as any other. "Scan QR Code" starts `ScanQrCodeActivity`, which runs in the pane as is: it
opens the back camera with the old `Camera` API (ATAK holds `CAMERA`), shows the preview on a
`TextureView` (drawn in ATAK's window like any view), decodes frames with ZXing, vibrates
(ATAK holds `VIBRATE`) and returns the text. The chat list's `onQrCodeScanned` then starts
what it starts upstream: `StartConversationActivity` for a contact or channel address, the
account pane for the own account. Back, closing the pane or leaving ATAK pauses it and
releases the camera.

### Floating activities

A dialog-themed activity (`windowIsFloating`) would float over the activity that started it,
which stays visible and paused. `FLOATING` lists those the host shows that way; today that is
`RecordingActivity`, the voice message recorder:

```text
floatOver(content):
    content.background = rounded rectangle (28 dp, ?colorSurfaceContainerHigh)
    scrim = FrameLayout(background = 60 % black, clickable)   # touches outside do nothing,
    scrim.addView(content, MATCH_PARENT × WRAP_CONTENT,       # as setFinishOnTouchOutside(false)
                  gravity CENTER, margins 24 dp)
    return scrim                                              # the record's content
```

The activity below keeps its content visible and is paused, not stopped. `settle` resumes only
the top record, and `settleShown` settles both, so closing the pane or leaving ATAK stops both.
The recorder's result (the file's URI) comes back through `deliverResult` like any other.
`RecordingActivity` records with `MediaRecorder` under ATAK's `RECORD_AUDIO` permission. It
sets `FLAG_KEEP_SCREEN_ON` on its own window, which the host carries over to the content in
ATAK's window. Upstream discards the recording when the recorder stops, and so does the host:
closing the pane, leaving ATAK or pressing back while recording cancels it.

### Other apps and results

Intents for other apps (file picker, camera, "open with") are started by **ATAK's** activity,
the only one the system can return a result to:

```text
startExternal(caller, intent, requestCode):
    if no caller or requestCode < 0:  atak.startActivity(intent); return
    launcher = atak.activityResultRegistry.register("takconvo#" + n, StartActivityForResult) {
        result -> unregister; deliverResult(caller, requestCode, result.code, result.data)
    }
    launcher.launch(intent)
    # ActivityNotFoundException / SecurityException -> toast, deliver RESULT_CANCELED

deliverResult(to, requestCode, code, data):
    if to is still on the stack and not finishing:
        data.extrasClassLoader = plugin class loader
        to.activity.onActivityResult(requestCode, code, data)       # protected: reflection
```

`ComponentActivity.onActivityResult` dispatches the result to its own `ActivityResultRegistry`,
so fragments that launched with `registerForActivityResult` get it as usual.

## Lifecycle

An embedded activity is resumed only while it is on top, the pane is visible, **and ATAK is
resumed**. Conversations marks what a resumed chat shows as read and holds back its
notifications, so a chat must not stay resumed behind other apps.

```text
settle(record):                      # called on every change of any of the three
    atakState = atak.lifecycle.currentState       # ATAK's activity is a LifecycleOwner
    if paneVisible and record is on top and atakState >= RESUMED:  resume(record)
    elif paneVisible and atakState >= STARTED:  pause(record); start(record)   # or below a
    else:                                        stop(record)                  # floating one

resume(r): start(r); callActivityOnResume; onPostResume (reflection, resumes fragments);
           lifecycle ON_RESUME
pause(r):  lifecycle ON_PAUSE; callActivityOnPause
start(r):  [callActivityOnRestart if stopped before]; callActivityOnStart; lifecycle ON_START
stop(r):   pause(r); lifecycle ON_STOP; callActivityOnStop
destroy(r): stop(r); lifecycle ON_DESTROY; callActivityOnDestroy; remove r.content
```

Instrumentation doesn't make the calls around `onStart`/`onResume`/`onStop` that update an
activity's own `LifecycleRegistry`, so the host dispatches those events itself. Dispatching
one that already happened does nothing.

Inputs: `ChatDropDown.onDropDownVisible/onDropDownClose` → `setVisible()`; a
`LifecycleEventObserver` on ATAK's activity → `settleShown()`. Closing the pane only stops the
activities; they are destroyed when the plugin stops.

## Back

```text
ChatDropDown.onBackButtonPressed():  goBack(); return true
goBack():
    if not host.onBackPressed(): closeDropDown()

host.onBackPressed():
    if top.activity.onBackPressedDispatcher.hasEnabledCallbacks():
        dispatcher.onBackPressed(); return true     # fragments, search bar, attachments...
    if stack.size > 1: finishFromChild(top); return true
    return false                                     # nothing left: close the pane
```

The drop-down is shown with `ignoreBackButton = true`. ATAK then keeps it on its stack when
another drop-down opens over it (e.g. the account pane), and brings it back afterwards. The
catch: with that flag ATAK calls `onBackButtonPressed()` but **never closes** the drop-down on
its own. So `goBack()` closes it explicitly: `closeDropDown()` passes the flag and does close.

## Showing the pane from elsewhere

```text
ChatDropDown.show():
    if open and visible: return
    host.setEstimatedPaneSize(ATAK's area unchanged since the last layout ? last size
                                                                      : estimatePaneSize())
    showDropDown(pane, 0.4, FULL_HEIGHT, FULL_WIDTH, 0.4, ignoreBackButton = true, this)
                                       # pane: the connection banner above the host's view
```

Its size until the next layout is only estimated, see "When the pane changes size".

`show()` is also how the plugin reaches a pane that is open but **hidden under another
drop-down**: the account pane opened from a chat's menu, whose avatar then opens the profile
picture screen in the chat pane. `showDropDown` handles that case: ATAK takes the hidden
drop-down off its stack ("removing a drop down that was not visible, but on the stack") and
shows it on top. On the way, the host's activities are only stopped. `unhideDropDown()` doesn't
work there: it unhides whatever is on top of ATAK's stack, the account pane, not this
drop-down. The pane stayed hidden until back closed the account pane, and only then did the
profile picture screen show.

The drop-down shown over another one keeps it on the stack only if it is **retained**
(`ignoreBackButton` or retain). Otherwise ATAK closes it ("not retaining the drop down on the
stack, closing"). So the account pane is built with `Pane.RETAIN = true`. Opened from it, the
profile picture screen returns to it:

```text
TakConvoPlugin.editProfilePicture():          # the account pane's avatar, when online
    host = openChatPane()                     # over the account pane, which stays under it
    host.startActivity(PublishProfilePictureActivity, EXTRA_ACCOUNT = own bare JID)
    profilePictureFromAccount = true

onTopFinished(activity):                      # EmbeddedActivityHost.Listener, after finish()
    if profilePictureFromAccount and activity is PublishProfilePictureActivity:
        profilePictureFromAccount = false
        chatDropDown.closeDropDown()          # the account pane shows again; the chats only
                                              # stop and are there next time
```

When the account pane shows again, ATAK's drop-down fragment restores the views' saved
state. The `EditText`s re-set their text, which fired the text watchers: the pane counted
that as an edit and showed a disabled "Log in". `AccountView` turns state saving off for
the address and password fields. `refresh()` fills them anyway, and a password has no place
in saved state.

## Not connected: `ConnectionBanner`

Conversations shows nothing while its account connects. The chat pane opens as soon as an
account exists, so a first sign-in looked like an empty chat list. `ChatDropDown` puts a strip
above the activities' screens, which the plugin builds and keeps up to date as an engine
listener:

```text
account null or online      -> hidden (no account: the account pane shows instead)
CONNECTING, OFFLINE         -> spinner, "Not connected yet: connecting to the XMPP server…"
any other state             -> warning icon, "Not connected: <state>. Tap for details."
                               (e.g. Server not found, Unauthorized, No internet)
tap                         -> the account pane
```

It shows the current state, so a retry after an error shows "connecting" again for its
duration. It uses the screens' theme and `UiScale`, inflated like the account pane.

## Views that talk to their window: `PaneFrame`

The pane's root, `PaneFrame`, is in ATAK's window. Some requests a view makes of its window
travel up the view tree and would reach **ATAK's** decor view, which answers them with ATAK's
resources and ATAK's activity as callback:

```text
PaneFrame.dispatchApplyWindowInsets(insets): return insets       # already clear of the bars

PaneFrame.showContextMenuForChild(view[, x, y]):
    # e.g. long-press on a message. ATAK's decor view would build the menu with ATAK's
    # resources (Resources$NotFoundException on the plugin's string ids, ATAK crashes) and
    # deliver the selection to ATAK's activity.
    return top.activity.window.decorView.showContextMenuForChild(view[, x, y])
    # that decor view is unattached, but its menu callback is the embedded activity and its
    # context has the plugin's resources; the popup/dialog anchors to `view`, which is attached

PaneFrame.startActionModeForChild(view, callback[, type]):
    # e.g. text selection, "Paste as quote". The floating toolbar must stay with ATAK's
    # window, the one on screen, but callbacks inflate their menus with mode.getMenuInflater()
    return super.startActionModeForChild(view, InflatingCallback(view.context, callback)[, type])

InflatingCallback(context, callback) extends ActionMode.Callback2:
    every callback method passes InflatingActionMode(mode, context) instead of mode
InflatingActionMode(mode, context) extends ActionMode:
    getMenuInflater()      -> new MenuInflater(context)          # plugin resources
    setTitle/Subtitle(res) -> mode.setTitle(context.getText(res))
    everything else        -> mode
```

## Runtime permissions

`Activity.requestPermissions()` starts the system's permission activity through the
activity's `ActivityThread`, which an embedded activity doesn't have: it throws a
`NullPointerException`. The fork routes Conversations' requests through
`ActivityCompat.requestPermissions`, which asks a global `PermissionCompatDelegate` first. The
host installs one while it has activities:

```text
attachToAtak():
    previous = ActivityCompat.getPermissionCompatDelegate()
    ActivityCompat.setPermissionCompatDelegate(delegate)

delegate.requestPermissions(activity, permissions, requestCode):
    if activity is not on the stack: return previous?.requestPermissions(...) ?: false
    launcher = atak.activityResultRegistry.register(key, RequestMultiplePermissions) {
        granted -> unregister
                   results[i] = granted[permissions[i]] ? GRANTED : DENIED
                   activity.onRequestPermissionsResult(requestCode, permissions, results)
    }
    launcher.launch(permissions)
    return true

detachFromAtak(): if the delegate is still ours, put `previous` back
```

Fragments and `registerForActivityResult(RequestPermission...)` end up in
`ActivityCompat.requestPermissions` too, and `onRequestPermissionsResult` dispatches back to
them. In practice ATAK already holds the permissions Conversations asks for (ATAK requires
camera and microphone at start-up), so this path is a safety net. It was checked with a
request for `READ_CONTACTS` (undeclared by ATAK → denied) together with `CAMERA` (granted).

## Configuration and theme

```text
paneConfiguration():
    uiMode = NIGHT_YES                                   # ATAK is always dark
    densityDpi = ATAK's densityDpi × UiScale.FACTOR (0.9)
    if pane size is known:                               # the whole pane, banner included
        screenWidthDp, screenHeightDp = pane size / scaled density
        smallestScreenWidthDp = min(...); orientation from the pane's shape
```

Conversations is designed for a whole phone screen, and next to ATAK's denser UI its screens
looked oversized in a side pane. A lower density scales every dp and sp of its resources
alike: text, icons, touch targets. 0.8 turned out a bit small; 0.9 it is.

The pane takes 40 % of the map (`ChatDropDown.PANE_FRACTION`: its width in landscape, its
height in portrait), and so does the account pane. Resources are chosen for the **pane's**
size, not the screen's, so a side pane on a tablet gets Conversations' phone layouts. On the
480 dpi S23 in landscape the pane is 839 × 840 px, 311 × 311 dp at the scaled density. The
size is the whole drop-down, like an activity's window: the connection banner above the
activities shows and hides without changing it.

The host also sets `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)`,
because AppCompat would otherwise follow the device and an embedded activity can't
`recreate()`. That setting is process-wide: it would also affect another AppCompat plugin in
ATAK.

### When the pane changes size

The pane changes size when it is rotated with ATAK, when its handle takes it full screen and
back, and when ATAK places it elsewhere: opened in portrait, it is at the bottom, 400 × 309 dp
on the S23. Android relaunches an activity whose configuration changes: it saves its state,
destroys it and creates it again with that state. The host does the same.

```text
ChatDropDown: after each layout of the pane     -> host.setPaneSize(w, h)
              ATAK's handle (onStateRequested)  -> resize(full screen - handle) or back to 0.4

setPaneSize(w, h):
    start what waited for the layout
    relaunch the shown activities: at once if the pane was just shown, else 400 ms after the
        last change (the size is still changing)

relaunchStale():                    # also when the pane shows again, and when the top finishes
    not while: hidden, the size only estimated, ATAK's window without focus (a dialog or menu)
    for the shown activities (the top one, and those a floating one shows), bottom up:
        if orientation or width or height differs by more than 8 dp: relaunch(record)

relaunch(record):
    keep the focused view's id; stop
    callActivityOnSaveInstanceState(state); content.saveHierarchyState(views)
    destroy; create a new activity in the same record, with the old one's intent:
        onCreate(state), onStart, onRestoreInstanceState(state), onPostCreate(state)
        content.restoreHierarchyState(views); focus the view kept
    put its content where the old one was; settle
```

The record stays the same, so a result for the old activity (a permission, a picked file, a
point picked on the map) reaches the new one. Hidden activities are relaunched only when they
show again, as on Android. `RecordingActivity` isn't relaunched: upstream declares
`configChanges` for it, so a recording continues.

- **Saved state.** All of Conversations' activities save theirs, as they must on a phone that
  rotates: the chat comes back with its scroll position, draft and attachments. The window of
  an embedded activity no longer holds its views, so the host saves and restores their state
  itself, as the window would.
- **Retained objects aren't handed over.** Android passes the old activity's ViewModels and
  retained fragments to the new one, through `retainNonConfigurationInstances()` and
  `mChangingConfigurations`. Both are hidden API that ATAK's target SDK 35 denies
  (max-target-o), so the new activity starts from its saved state only. Because the old one
  doesn't know it is being relaunched, the chat hides the keyboard and stops a playing voice
  message.
- **Not under a dialog or menu.** Dialogs are ATAK windows (see "The parent activity"): a
  dialog left open would act on the activity replaced. Two of Conversations' dialogs (adding a
  contact, creating a group chat) also keep their listener only when retained. The host waits
  until ATAK's window has the focus again; `PaneFrame` reports it.
- **The first activity waits for the layout.** Before its first layout, and when ATAK's area
  changed while it was closed, the pane's size is only estimated: 40 % (or all) of ATAK's
  content area (`android.R.id.content`). On the S23 that gave 347 × 400 dp for a 311 × 311 dp
  pane: ATAK's panes leave out its toolbar and the screen's cutout. The first activity is
  created one frame later, after the layout, or with the estimate after 500 ms. The display's
  size is worse: it counts the system bars, and it once put a 350 dp pane in Conversations'
  `w384dp` bucket, where a voice message's player was wider than its bubble. The account pane
  still uses the estimate: it is the plugin's own view, sized once.

ATAK on a phone stays in landscape unless its `atakControlForcePortrait` setting is on. Changed
while ATAK runs, ATAK keeps an open pane where it was (a side pane 160 dp wide in portrait)
until it is opened again, and its own layout is off until ATAK restarts.

## Files handed to other apps

"Open with", "share" and the camera need a `content://` URI that another app can read or
write. A plugin can't declare a `FileProvider`; ATAK's (`com.atakmap.app.civ.provider`) serves
external storage only. The fork's `FileBackend` handles it (see
[05](05-conversations-fork.md#f-files-handed-to-other-apps)):

```text
getUriForFile(context, file):                   # embedded
    return FileProvider.getUriForFile(context, "<ATAK package>.provider", shareableFile(file))

shareableFile(file):
    if file is under external storage or /storage: return file
    copy = externalCache/shared/<path relative to filesDir>   # same file -> same URI
    if copy is missing or older or of another size: copy file -> copy
    return copy

Cache.takePicture():   the camera writes to externalCache/Camera when embedded
XmppEngine start:      FileBackend.deleteShareableCopies()   # decrypted copies don't linger
```

The copies are in ATAK's app-specific external storage, which other apps can only read
through the provider's grant.

## What the fork changes for this

| Fork change | Why |
|---|---|
| `BaseActivity.embeddedContent`, `findViewById`, `getCurrentFocus` | the views are no longer below the window's decor view |
| `BaseActivity.onStart` skips the night-mode check | it calls `recreate()` |
| `Activities.setStatusAndNavigationBarColors` does nothing | the system bars are ATAK's |
| `ConversationsActivity.onBackendConnected` skips start-up prompts | crash reports, battery optimisation and permissions are ATAK's business |
| `requestPermissions` → `ActivityCompat.requestPermissions` | see "Runtime permissions" |
| attachment choices in one scrolling row | the pane is too narrow and low for the grid |
| smaller avatars (chat list 44 dp, messages 36 dp) and attachment choices, a compact message field | in proportion with the pane, on top of the 0.9 scale |
| the empty chat list hint's icon 64 dp instead of 192 dp, narrower margins | it ran into the search bar and pushed the text under Start chat |
| `FileBackend` provider and camera | see "Files handed to other apps" |
| call buttons hidden, calls neither advertised nor accepted | `RtpSessionActivity` can't run embedded, see [05](05-conversations-fork.md#h-no-calls-inside-atak) |

## Not available yet

Share and show location don't start Conversations' activities: the host's `Redirect`
(`MapLocations`) takes them to ATAK's map, see [10](10-atak-map-integration.md). The host asks
its `Redirect` about every start, before routing it.

Started from Conversations' UI, these show "Not available inside ATAK": Conversations' own
settings (replaced by the plugin's; a `.pref` file sets them, see
[03](03-provisioning-and-trust.md#conversations-own-settings)), backup import. Library activities
aren't hosted either: the image cropper's `CropImageActivity` is avoided by the fork instead. Calls (`RtpSessionActivity`) are switched off rather than refused: the call
button would already have made the contact's device ring.
