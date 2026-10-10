# TAK Convo

An ATAK plugin that embeds the [Conversations](https://codeberg.org/iNPUTmice/Conversations)
XMPP client in ATAK, the way Taktrix embeds Element: Conversations' own engine and screens,
running in ATAK's process, in ATAK panes, connected to the organisation's XMPP server with the
user's TAK identity.

- **XMPP in ATAK**: 1:1 chats and group chats (MUC), OMEMO encryption, file transfer
  (HTTP upload), reactions, search, history (MAM), deleting a sent message for everyone
  (XEP-0424 retraction). The engine and UI are Conversations 2.20.4 as forked in
  [besteffortdev/conversation](https://github.com/besteffortdev/conversation), which adds
  retraction, a long-press menu with quick reactions and short names for users on other
  servers.
- **Zero typing**: the XMPP domain comes from a `.pref` file, a mission package or the MDM that
  installs the plugin (Android app config, e.g. SOTI MobiControl); the login reuses the TAK
  server's username and password. An XMPP login screen exists for other setups.
- **Trust like ATAK**: the server certificate is checked against the CAs of ATAK's TAK server
  truststores (optionally the device CA store, or a CA file), besides the public CAs.
- **TAK identity**: the device's XMPP address goes out in its SA (`<contact xmppUsername>`), as
  TAK Chat does.
- **In ATAK's contacts, like GeoChat**: a TAK user who advertises an XMPP address has an XMPP
  connector in ATAK's contact list that opens the chat in TAK Convo. Unread messages show on the
  contact, on ATAK's Contacts button and on the TAK Convo button. Message notifications are off
  by default, as the Conversations app on the device, signed in to the same account, notifies;
  turned on, they (reply, mark as read) bring ATAK up on the chat when tapped.
- **On ATAK's map**: locations in chats become markers on the map; sharing a location sends
  ATAK's position or a point tapped on the map; MGRS and lat/lon written in messages are links
  to the map; a chat's **Show on map** finds the TAK user. GeoChat-style quick messages can sit
  above the message field (a setting). ATAK's **Send** dialog offers TAK Convo for data packages and files,
  a marker's or shape's Send can go to a group chat, and a received map file can be imported
  into ATAK
  ([docs/10](docs/10-atak-map-integration.md)).

## Status

2026-09-30. Built for ATAK 5.6 and 5.8. Working on a Samsung Galaxy S23 (Android 16) with the
ATAK 5.8.0.5 developer build, against an Openfire server (until the move to 5.6/5.8, also on a
Galaxy S22+ with 5.5.1.8):

| Works | Not yet |
|---|---|
| engine in-process, provisioning from `.pref` or an MDM's app config, TAK credentials or XMPP login | calls (audio/video): switched off, not advertised |
| trust from TAK truststores / Android CA store / CA file | |
| ATAK's map: locations, positions in messages, quick messages, Show on map, Send dialog, import into ATAK | |
| account pane, tool preferences, `.pref` import; QR codes: show and scan; profile picture (from the account pane) | backups |
| chat pane: chat list, chats, group chats, start chat, channel details, search; channel discovery on your XMPP server (setting) | TAK callsigns as the names of other users' XMPP contacts |
| your ATAK callsign is your XMPP nickname, for contacts and in group chats, and follows it (setting); a group chat where someone else has it keeps your nickname there | |
| sending/receiving with OMEMO, reactions, context menus, text selection | |
| the pane follows ATAK's rotation and goes full screen from its handle; the screens are rebuilt for the new size, keeping what they show | |
| attachments: pick, upload, open with another app, camera; voice messages | some Conversations screens are allowed but untested (see docs/04) |
| ATAK contacts: XMPP connector opens the chat, unread counts on contacts and buttons; group chats listed with the users | XMPP presence dots on contacts: implemented, untested with a real second user |
| notifications (a setting, off by default): sound when the pane is closed, tap opens the chat, reply, mark as read; sound and vibration settings | |

Release ATAK (e.g. Play Store ATAK-CAN 5.8) loads only plugins signed by TAK.gov, and its API
is obfuscated, so a plugin for it must be built with that version's SDK and mapping. Until the
plugin goes through TAK.gov signing, it runs on the SDK's developer ATAK (see
[docs/07](docs/07-development-and-testing.md#developer-atak-on-a-test-device-and-release-atak)).

## How it works

ATAK loads a plugin's code into its own process: none of the plugin's activities, services or
providers can run. TAK Convo gives Conversations what it expects anyway:

- **Engine**: `XmppConnectionService` and the `Application` are created as plain objects on an
  `EmbeddedContext` (ATAK's identity, the plugin's resources, storage namespaced in ATAK's data
  directory, `startService`/`bindService` routed to the in-process service).
- **Screens**: Conversations' real activities are created with `Instrumentation.newActivity`,
  driven through their lifecycle by the plugin, and their views moved into an ATAK drop-down.
  A stand-in parent activity catches `startActivity`/`finish` and keeps a back stack. Results,
  permissions, context menus and action modes are routed through ATAK's activity and window.
- **Contacts and notifications**: a `ContactConnectorHandler` for ATAK's XMPP connectors opens
  chats and reports unread counts and presence. Conversations' notifications are posted as
  ATAK's; their taps, actions and alarms are redirected to ATAK's activity and to a receiver in
  ATAK's process, and their icons drawn as bitmaps.
- **Fork**: 56 files of that base changed and 2 added, all marked `TAKCONVO`: no Android service,
  hooks for trust, compatibility with the libraries ATAK loads, activities in a pane, files
  through ATAK's FileProvider, PendingIntents and notifications that work as ATAK's.

Details in [docs/](docs/README.md).

## Requirements

**Equipment.** Android 6.0+ (minSdk 23). Tested on a Samsung Galaxy S23 (Android 16) and an
Android 14 emulator.

**ATAK.** ATAK 5.6 or 5.8, developer build (the SDK's `atak.apk`). One APK per version, each
built against that version's SDK (ATAK-CAN 5.6.0.24, 5.8.0.5) and the library versions that
ATAK ships (`gradle/atak-runtime.gradle`).

**XMPP server.** Any standards-compliant server. Tested with Openfire (LDAP-backed, SASL PLAIN,
STARTTLS, certificate from an internal CA). Conversations uses MUC, HTTP upload (XEP-0363) and
MAM when the server offers them.

**Network (from the device).**

| Port | Protocol | For |
|---|---|---|
| 53 | DNS (UDP/TCP) | SRV (`_xmpp-client._tcp`, `_xmpps-client._tcp`) and A/AAAA lookups of the XMPP domain |
| 5222 | TCP, XMPP + STARTTLS | client-to-server (or the host/port set in `takconvo_xmpp_host`/`_port`) |
| 5223 | TCP, XMPP over TLS | if the domain's `_xmpps-client` SRV record points there |
| server-defined (Openfire: 7443) | HTTPS | file upload/download (XEP-0363) |
| 443 | HTTPS | only if a user opens "Discover channels" (queries search.jabber.network) |

## Configuration

Settings are ATAK preferences, provisioned by a `.pref` file (alone or in a mission package) or
edited in **Settings › Tool Preferences › TAK Convo**. Minimal provisioning:

```xml
<preferences>
    <preference version="1" name="com.atakmap.app.civ_preferences">
        <entry key="takconvo_xmpp_domain" class="class java.lang.String">xmpp.example.org</entry>
    </preference>
</preferences>
```

The JID becomes `<TAK username>@<domain>` (a TAK username with its own domain, such as a
Windows login `alice@corp.example`, gives `alice@<domain>`). All keys: [provisioning/takconvo-template.pref](provisioning/takconvo-template.pref)
and [docs/03](docs/03-provisioning-and-trust.md).

A `.pref` file can set everything:
- the plugin's settings (`takconvo_xmpp_*`, `takconvo_notification_*`), which are also on its
  settings page;
- an XMPP login (`takconvo_xmpp_username`, `takconvo_xmpp_password`): the password is moved to
  ATAK's encrypted credential store at once;
- Conversations' own settings (`takconvo_conversations_<key>`: OMEMO, read receipts, typing
  notifications, attachment size and compression, message deletion...), whose settings screen
  the plugin's replaces.

A change to where the credentials go (domain, host, port, which credentials and which TAK
server's, trusted CAs) waits for the user's **Connect** in the account pane
([docs/03](docs/03-provisioning-and-trust.md#approving-the-server)): data packages apply their
`.pref` files without asking.

### From an MDM (app config)

An MDM that pushes the APK (SOTI MobiControl, Intune...) can set the same settings as Android
managed configuration: the APK declares them (`res/xml/app_restrictions.xml`), so the console
lists TAK Convo's settings with descriptions. The keys are the preference keys above, plus
`takconvo_xmpp_password` and `takconvo_xmpp_trusted_ca_certificate` (a CA certificate as PEM
text). What the MDM sets:
- is locked: greyed out in the tool preferences, and put back if a `.pref` file changes it;
- needs no approval: the server it sets connects without the user's **Connect**;
- a username and password it sets are used instead of the TAK server credentials;
- applies when ATAK starts or comes back to the front; a key removed from the configuration
  goes back to its default.

Booleans and lists start at **Not managed**. Details:
[docs/03](docs/03-provisioning-and-trust.md#managed-configuration-mdm).

## Build and install

```bash
./gradlew assembleCivDebug --offline                       # for ATAK 5.8
./gradlew assembleCivDebug --offline -PatakVersion=5.6.0   # for ATAK 5.6
java tools/AtakLinkCheck.java <that ATAK SDK>/atak.apk app/build/outputs/apk/civ/debug/<apk>
adb install -r app/build/outputs/apk/civ/debug/<apk>
```

or, on Windows, `tools\deploy.ps1 -Serial <adb serial> [-AtakVersion 5.6.0]`, which also
re-enables the plugin in ATAK after the reinstall and restarts ATAK. See
[docs/07](docs/07-development-and-testing.md) for the setup (`local.properties` with the two
SDKs) and the test checklist.

```bash
tools/check-style.sh                        # line length, wildcard imports, catch-alls
./gradlew testCivDebugUnitTest --offline    # JVM tests of the provisioning and trust logic
```

GitLab's pipeline (`.gitlab-ci.yml`) runs both, the builds and the link checks for both ATAK
versions; its runner needs the ATAK SDKs
([docs/07](docs/07-development-and-testing.md#tests-and-ci)). Gradle checks every downloaded
library against `gradle/verification-metadata.xml`.

These debug builds load in the SDKs' developer ATAK only. For release ATAK (Play Store or an
organisation's), TAK.gov's Third Party Pipeline builds and signs the plugin from source:
`tools/tpp-package.sh` makes one zip per ATAK version (`build/tpp/takconvo-atak56.zip`,
`takconvo-atak58.zip`) to upload there. See
[docs/07](docs/07-development-and-testing.md#release-builds-and-takgovs-third-party-pipeline).

## Repository layout

```text
app/                      the ATAK plugin
  src/main/java/com/atakmap/android/takconvo/plugin/
    TakConvoPlugin.java   entry point (IPlugin)
    SensitiveLog.java     debug-only logs that name users
    Guard.java            runs ATAK's callbacks into the plugin without crashing ATAK
    xmpp/                 embedded engine: XmppEngine, EmbeddedContext, CallsignNicknames,
                          PendingIntents, notifications
    contacts/             XmppContacts: ATAK contact handler, unread badges
    config/               XmppSettings, ConversationsSettings, TrustSources, TrustedCa,
                          AppConfig (MDM app config) and its provider
    ui/                   account pane, tool preferences
    ui/host/              chat pane: EmbeddedActivityHost, HostParent, PaneFrame, ChatDropDown
    debug/                DebugReceiver (debug builds)
conversations/            besteffortdev/conversation (Conversations 2.20.4 fork), as an
                          Android library (GPLv3)
  UPSTREAM.md             base version, imported source sets
  upstream/               upstream manifests and proguard rules, for reference
gradle/atak-runtime.gradle  library versions ATAK provides at runtime
gradle/verification-metadata.xml  checksums of every library the build downloads
provisioning/             .pref template and test files
tools/                    AtakLinkCheck, check-style.sh, fork-diff.sh, deploy.ps1, tpp-package.sh
.gitlab-ci.yml            GitLab pipeline: style, manual, builds, tests, link checks
docs/                     design and maintenance documentation
```

## Documentation

[docs/README.md](docs/README.md): architecture, the embedded engine, provisioning and trust, the
activity host, the Conversations fork, ATAK's runtime, development and testing, ATAK contacts
and notifications, the code guidelines (error handling, logging, style), and what we learned
embedding a full Android app in ATAK ([docs/11](docs/11-findings.md)).

## License

`conversations/` is Conversations by Daniel Gultsch and contributors, with the changes of
besteffortdev/conversation, GPLv3 ([conversations/LICENSE](conversations/LICENSE)). The plugin APK includes it and is distributed
under the GPLv3 ([LICENSE](LICENSE)). The plugin's icons are Conversations' launcher icons. The plugin skeleton
comes from the ATAK-CIV SDK's plugin template.

## Contact

`besteffortdev` on GitHub: <https://github.com/besteffortdev/TAK-Convo>.
