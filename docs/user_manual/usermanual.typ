
#import "formatting.typ": *

// TAK.gov's pipeline writes both versions (gradle/typst.gradle)
#show: userguide.with(
   plugin-name: "TAK Convo",
   plugin-version: "0.5.0",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview
#side-by-side(columns: (1.2fr, 9fr))[
  #image("plugin_icon.png", width: 100%)
][
TAK Convo puts the Conversations XMPP chat app inside ATAK: one-to-one chats and group chats,
end-to-end encryption (OMEMO), files, photos and voice messages, next to the map.

- It signs in with your TAK server account: nothing to type once your administrator has set
  the XMPP server.
- Your ATAK callsign is your nickname.
- TAK users who run TAK Convo are reachable from ATAK's contact list.
- Positions in messages lead to the map, and markers, shapes and data packages can be sent to
  a chat.

Your XMPP server must be reachable from the device. TAK Convo works with any standard XMPP
server and with the people on it, whatever app they use.
]
]

#tak-slide[
= Getting started
== Settings from your administrator
Your administrator gives you a settings file (`.pref`), on its own or in a data package. Open
the data package in ATAK, or import the file in *Settings › Tool Preferences › TAK Convo ›
Import settings file (.pref)*.

When a settings file names a new server, or the password would come from another of your TAK
servers, TAK Convo asks before sending your password there: a notification says the chats are
offline, and the account screen names the server with *Connect*. Connect only if you or your
administrator made the change.

== Signing in
Tap the TAK Convo button (a speech bubble) in ATAK's toolbar or in the Tools menu. TAK Convo
signs in with the username and password of your TAK server connection, so connect to your TAK
server first. Until the connection is up, a red strip at the top of the chats says you are
not connected yet, or why not (for example _Server not found_). Tap it to see the account.

To use another account, tap *Use an XMPP account* and enter your XMPP address and password.

== The server's certificate
TAK Convo trusts the certificate authorities of your TAK server connections, as ATAK does,
plus the public ones. If the account screen says the server's certificate isn't trusted, connect
to your TAK server first, or in the settings trust the device's CA store or a CA certificate
file.
]

#tak-slide[
= Chats
The chats open in a pane beside the map, like ATAK's own tools. *Back* goes back one screen,
and closes the pane from the chat list. Drag the pane's handle toward the map for full screen,
and back again to return.

- *Start a chat*: the *+* button, then an XMPP address, or create or join a group chat.
  *Discover channels* lists the group chats of your XMPP server.
- *Messages* are encrypted with OMEMO when the other side supports it.
- *Attachments*: the attachment button sends files, photos, a camera picture, a voice message
  or a location.
- *Your nickname* follows your ATAK callsign, in your contacts' lists and in group chats. A group
  chat where someone already has your callsign keeps your previous nickname.
- *Notifications* are off by default: the Conversations app on your device, signed in to the
  same account, notifies. ATAK's contacts show unread messages either way. Turned on in the
  settings, new messages notify while the pane is closed; tap one to open the chat, or reply
  and mark as read from the notification.

Calls (audio and video) are not available inside ATAK.
]

#tak-slide[
= ATAK contacts
- A TAK user who advertises an XMPP address (TAK Convo does it for you) has an *XMPP* entry in
  ATAK's contact list. Tap it to open the chat.
- Unread messages show on the contact, on ATAK's Contacts button and on the TAK Convo button.
- Your group chats are listed in ATAK's contacts too, with the group icon.
- On the map, a TAK user's radial menu › contact › *XMPP* opens the chat.
]

#tak-slide[
= On the map
== Locations
Tap a location in a chat to see it on the map: TAK Convo puts a marker there and centers the
map on it. The marker is an ordinary local marker: it stays until you delete it.

To share a location, use the attachment button › *Location*, then *My position* or *A point on the
map* (tap the point).

== Positions in messages
Positions written in a message are links to the map:

#table(
  columns: (auto, auto),
  stroke: none,
  inset: (x: 0.6em, y: 0.3em),
  [MGRS], [`18T VR 44690 31520` or `18TVR4469031520`],
  [decimal degrees], [`45.4215, -75.6972`],
  [with hemispheres], [`45.30N 75.88W`],
)

== Show on map
In a chat with a TAK user who is on the map, the chat menu's *Show on map* centers the map on
them. In the chat with yourself, it shows your own position.
]

#tak-slide[
= Sending map data
== A marker or shape to a group chat
Open the item's *Send*. ATAK's contact list shows your group chats among the contacts: pick one
or more and tap *Send*. The group chat gets a line naming the item with its MGRS position, and
a data package with the item itself. TAK users picked in the same list get it from ATAK as
usual.

== Data packages and exports
ATAK's *Send* dialog (Data Packages › *Send*, or after an export) lists *TAK Convo*: choose it,
then the chat.

== Receiving map data
Tap a received data package, KML, KMZ, GPX, CoT, GeoJSON or imagery file, then *Import into
ATAK*. ATAK imports it as if you had opened it from its own import tool.
]

#tak-slide[
= Quick messages
Quick messages are buttons above the message field with ready-made texts, like GeoChat's. A tap
adds the text to the message; you still tap send.

They are off by default. Turn them on in *Settings › Tool Preferences › TAK Convo › Show quick
messages*, and change the texts in *Quick messages*, separated by `|`. The default texts:
Roger, Wilco, Say again, In position, Moving, All secure. A change shows the next time a chat
opens.
]

#tak-slide[
= Settings
*Settings › Tool Preferences › TAK Convo*. Your administrator's settings file can set all of
them. So can your device management (MDM): then they show greyed out, under _Managed by your
organization_, and TAK Convo connects to the server they name without asking you.

#table(
  columns: (auto, 1fr),
  stroke: none,
  inset: (x: 0.6em, y: 0.25em),
  [*Account*], [Connection enabled · Use TAK server credentials · TAK server for the
    credentials (Automatic: the one on your XMPP server's domain) · XMPP account · Use the ATAK
    callsign as nickname],
  [*Notifications*], [Message notifications (off by default) · Sound · Vibration],
  [*Chat*], [Show quick messages · Quick messages],
  [*Server*], [XMPP domain · Custom host and port (only when DNS doesn't find the server) ·
    Discover channels on (your XMPP server, another server, or the public directory)],
  [*Server certificate*], [Use TAK server truststore · Use Android CA store · Additional CA
    certificate],
  [*Provisioning*], [Import settings file (.pref)],
)

ATAK's *Clear Content* also deletes TAK Convo's chats, received files, encryption keys and
login from the device. The messages stay on the server.
]
