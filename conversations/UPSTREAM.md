# Upstream

This module is a fork of **Conversations 2.20.4**
(https://codeberg.org/iNPUTmice/Conversations, tag `2.20.4`, commit `bf3269cd`), licensed GPLv3
(see `LICENSE`).

Imported source sets (the `conversations` + `free` variant):

- `src/main`
- `src/conversations` (java + res)
- `src/free` (java)
- `src/conversationsFree` (java)
- `libs/annotation`, `libs/annotation-processor`

The upstream manifests live in `upstream/` for reference only. They are not built, because
Conversations' activities and services cannot run inside the ATAK process.

Every local change to upstream code is marked with a `TAKCONVO` comment. Run
`grep -rn TAKCONVO conversations/src` to list them.

**The full list of changes, the reasons for them, the dependency differences, and the
procedure to move to a newer upstream release are in
[docs/05-conversations-fork.md](../docs/05-conversations-fork.md).** The exact diff can be
regenerated at any time with `tools/fork-diff.sh`.
