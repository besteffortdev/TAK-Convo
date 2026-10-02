# 10 — ATAK integration ideas

Integrations not built yet, best value first, with the ATAK and Conversations hooks found for
each (ATAK-CIV 5.5.1 source). Listed 2026-10-01; nothing here is decided.

## Already there

- **The map's contact menu.** A TAK user's marker that advertises an XMPP address has an XMPP
  button in its contact sub-menu (`assets/menus/contact_submenu.xml`, next to GeoChat). It
  broadcasts `com.atakmap.xmppAction`; `CotMapComponent` passes it to
  `ContactConnectorManager.initiateContact(connector.xmpp, ...)`, which the plugin's handler
  answers ([08](08-contacts-and-notifications.md)): the chat opens in TAK Convo. Not yet tested
  with a second TAK user; both test phones run the plugin, so they can test it on each other.

## Ideas

| # | What | How | Size |
|---|---|---|---|
| 1 | **Locations through ATAK's map.** Opening a location in a chat centers ATAK's map on it with a marker named after the sender; sharing a location sends ATAK's own position or a point tapped on the map | `EmbeddedActivityHost` intercepts `ShowLocationActivity` (the `geo:` URI, from `GeoHelper`, `FixedURLSpan`, `MediaPreviewAdapter`) and `ShareLocationActivity` (result extras `latitude`, `longitude`, `accuracy`, read by `ConversationFragment` for `ATTACHMENT_CHOICE_LOCATION`). Self position: `MapView.getSelfMarker()`. Picking: ATAK's `MapClickTool` (callback intent with the point). Today both show "Not available inside ATAK" | small, plugin only |
| 2 | **Quick messages like GeoChat's**: a row of preset buttons above the message field, in sets the user switches between | GeoChat's presets: `res/xml/chat_modes.xml` (RGR, "in position", "all secure"...), 4 buttons per mode, editable by a long press. Presets in the `.pref` file | small, fork layout change |
| 3 | **"TAK Convo" in ATAK's Send dialog**: markers, routes, shapes, KML or a data package sent to a chat or room picked from a list | `URIContentManager.getInstance().registerSender(URIContentSender)`; `SendDialog` offers it for `file://` and `mpm://` (data package manifest, saved first as `SendDialog.saveMissionPackage` does). Sent with Conversations' HTTP upload, OMEMO-encrypted when on | medium |
| 4 | **"Import into ATAK"** for a received data package, KML/KMZ, GPX or CoT file, instead of handing it to another app | `ImportExportMapComponent.USER_HANDLE_IMPORT_FILE_ACTION` with the file. Pairs with 3: a route sent to the team room, imported on each device | small to medium |
| 5 | **"Show on map" in a one-to-one chat**, like GeoChat's pan-to-contact button | find the `IndividualContact` whose XMPP connector has the chat's address, then its map item (`MapItemUser`) or `GoTo` | small |
| 6 | **Coordinates in messages as links**: MGRS or lat/lon written in a message opens that point on the map | the fork's message linkifier; ATAK's `CoordinateFormatUtilities` to parse | medium, fork change |

Suggested order: 1 and 2 (quick, the GeoChat-like shortcuts), then 3 and 4 together.

## Not proposed

- Publishing the position over XMPP (XEP-0080) or showing XMPP users on the map: positions
  already go through the TAK server.
- CoT over XMPP: no other XMPP client understands it.
- ATAK emergency alerts forwarded to a room: safety-critical, needs its own design.
