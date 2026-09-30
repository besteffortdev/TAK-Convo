# TAK Convo documentation

How TAK Convo embeds the Conversations XMPP client in ATAK, why each piece is the way it is,
and how to build, test and maintain it. Pseudocode in these documents follows the real code
closely; the class named at the top of each section is the reference.

| # | Document | Read it for |
|---|---|---|
| 01 | [Architecture](01-architecture.md) | the constraint behind the design, components, lifecycle, where data lives |
| 02 | [Embedded engine](02-embedded-engine.md) | running `XmppConnectionService` as a plain object in ATAK's process: `EmbeddedContext`, service routing, start/stop, the fresh resource |
| 03 | [Provisioning and trust](03-provisioning-and-trust.md) | settings and `.pref` keys, TAK credentials, `provision()`, the trust sources, the account pane, tool preferences |
| 04 | [Chat pane: activity host](04-chat-pane-activity-host.md) | Conversations' own activities in an ATAK drop-down: creation, back stack, lifecycle, back, context menus, permissions, files |
| 05 | [The Conversations fork](05-conversations-fork.md) | **every change to upstream Conversations 2.20.4**, what the plugin relies on, how to move to a newer upstream |
| 06 | [ATAK runtime and class loading](06-atak-runtime-and-classloading.md) | parent-first loading, desugared default methods, `AtakLinkCheck`, SDK versions, plugin loading |
| 07 | [Development and testing](07-development-and-testing.md) | setup, build, deploy, debug broadcasts, logs, the device test checklist, gotchas |
| 08 | [ATAK contacts and notifications](08-contacts-and-notifications.md) | the XMPP connector handler, unread counts and presence in ATAK's contacts, the toolbar badge, notifications posted as ATAK's: taps, actions, icons |

`user_manual/` holds the end-user manual (Typst) from the ATAK plugin template; it is not
written yet.

Tools referenced throughout:

| Tool | Purpose |
|---|---|
| `tools/AtakLinkCheck.java` | find `AbstractMethodError` / `NoSuch...Error` between the plugin and ATAK before running |
| `tools/atak-link-ignore.txt` | accepted link-check findings, with reasons |
| `tools/fork-diff.sh` | the fork's exact diff against upstream Conversations |
| `tools/deploy.ps1` | build, link-check, install on a device, restart ATAK with the plugin loaded |
