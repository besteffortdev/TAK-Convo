# 09 Code guidelines

The rules TAK Convo's code follows, drawn from Android's and Google's style guides and
Android's security and performance guidance (sources at the end). They apply to the plugin
(`app/`) and to the fork's own code (`TakConvoCompat`, the `TAKCONVO` hunks). Upstream
Conversations' code keeps its own style, so the fork diff stays small.

The project is Java. Kotlin's conventions are listed too, for any Kotlin added later.

## Error handling

| Do | Don't |
|---|---|
| Catch the exceptions a call can throw, several in one multi-catch: `catch (IOException \| GeneralSecurityException e)` | `catch (Exception e)` or `catch (Throwable t)` around ordinary code |
| Log what was caught (`Log.w`/`Log.e` with the exception), then return a safe value, rethrow, or recover | Leave a catch block empty, or return a default without a trace |
| Keep the cause when rethrowing: `new IllegalStateException(msg, e)` | Throw a new exception that drops the original |
| Clean up in `finally` (or try-with-resources), e.g. undo a half-done step | Catch `Throwable` only to clean up and rethrow |
| Comment the rare catch that is deliberately empty, saying why | Swallow silently |

**The plugin's one deliberate exception**: code that calls into Conversations or ATAK
from ATAK's process catches `RuntimeException | LinkageError` (or `Exception | LinkageError`
when checked exceptions are involved) and logs it. This covers starting and stopping the
engine (`TakConvoPlugin`) and an embedded activity's lifecycle (`EmbeddedActivityHost.guarded`).
A plugin must not crash its host: an uncaught exception there kills ATAK. `LinkageError`
covers a library that ATAK provides not matching what the plugin was built against
([06](06-atak-runtime-and-classloading.md)). `Error`s like `OutOfMemoryError` are not caught.
The AOSP style guide allows this for "top-level code where you want to prevent errors from
showing up in the UI".

## Logging

| Do | Don't |
|---|---|
| Keep error and warning logs, without user data | Log message text, passwords, tokens |
| Log user data only in debug builds: `SensitiveLog.d` (addresses, callsigns, room names) | Use `Log.d` for anything that names a user |
| Log a failure once, with the exception | Log successes above debug level |
| Use the class's `TAG` (`TakConvo.<Area>`) | Use `System.out` |

`SensitiveLog.d` checks `BuildConfig.DEBUG`, a compile-time constant, so javac drops those
calls from release builds. R8 can't strip logs here: ATAK's required R8 section has
`-dontoptimize`, which disables `-assumenosideeffects`.

Other apps can read logcat only through adb or a bug report, but those still reach people the
data isn't meant for.

## Android

- **Main thread**: no blocking work. Use `SharedPreferences.apply()`, not `commit()`. Network
  and database work belong to Conversations' own threads. The engine and the panes run on
  the main thread by design: their methods say so.
- **Receivers**: register runtime receivers with `RECEIVER_NOT_EXPORTED` (Android 13+). The
  only exported one is `DebugReceiver`, which adb's shell needs; it is registered in debug
  builds only.
- **PendingIntents**: explicit targets, immutable unless the system has to fill in the
  intent. `EmbeddedPendingIntents` keeps Conversations' own flags. Upstream makes a few
  notification actions mutable: reply, mark as read, delete, show location.
- **Contexts**: long-lived objects keep the application context (`XmppEngine`,
  `EmbeddedPendingIntents`, `EmbeddedNotifications`). Static fields hold no Activity. The
  exception is `TakConvoPreferenceFragment`: its static field holds the plugin context, which
  lives as long as the plugin, as ATAK's template does.
- **Saved state**: views refilled from the model don't save their state
  (`setSaveEnabled(false)`). That avoids stale text coming back, and a password in a Bundle.
- **Resources**: strings shown inside ATAK come from the plugin context. ATAK's context
  resolves a plugin's resource id in ATAK's package ([04](04-chat-pane-activity-host.md)).
- **Hidden APIs and reflection**: only where there is no API (`Activity.mResultCode`,
  `onPostResume`). Catch `ReflectiveOperationException` and log it.

## Style (Java)

From the Google Java style guide, which Android's own guide follows:

- 100-character lines, 4-space indent, 8 for continuation lines.
- Braces on every `if`/`else`/`for`/`while`, even for one statement.
- No wildcard imports. Imports in groups: android, androidx/com/gov, the fork's (`eu.`, `im.`),
  then `java`/`javax`, each sorted.
- `@Override` wherever it applies.
- Constants `UPPER_SNAKE_CASE`, everything else `lowerCamelCase`. Acronyms as words
  (`XmppEngine`, `jid`).
- No dead code. Unused template classes are removed.
- Java 17: TAK.gov's pipeline has no newer JDK. No pattern `switch`, record patterns or
  `case null`; use `instanceof` chains (see [05](05-conversations-fork.md#k-java-17-for-takgovs-pipeline)).
- A TLS socket of our own verifies the server through `HostnameVerifier.verify(host, session)`:
  TAK.gov's scan reports any other check as missing
  ([05](05-conversations-fork.md#l-server-identity-checks-takgovs-scan-recognizes)).

## Comments

- A comment says **why**, not what the code already says. One line when it can be.
- Javadoc on classes and on public methods that aren't self-explanatory. Start with a short
  summary phrase ("Shows the chat pane...", "The account of..."). Skip `@param`/`@return`
  when the sentence covers them.
- Design, history and alternatives belong in `docs/`. A class comment points there
  ("See docs/04").
- The fork's changes keep a `TAKCONVO:` marker on each hunk, which docs/05 and
  `tools/fork-diff.sh` rely on. Keep the marker's text to one line where possible.
- `TODO:` with what is left and when, never a bare `TODO`.

## Kotlin (if any is added)

- Follow the Kotlin coding conventions and the Android Kotlin style guide.
- Prefer `val`, immutable collections and expression `if`/`when`/`try`.
- Use nullable types instead of null checks scattered around, and `require()`/`check()` for
  arguments and state.
- Handle coroutine errors where the coroutine is launched (a `CoroutineExceptionHandler`, or
  `try` around `await`). Never swallow `CancellationException`.
- KDoc: a summary phrase. `[param]` links instead of `@param` tags.

## Checking

- `tools/deploy.ps1` builds, link-checks against ATAK and installs: run it for each change.
- Before committing, these should find nothing:
  - a line over 100 characters: `grep -rnE "^.{101,}$" app/src/main/java`;
  - a catch-all: `grep -rnE "catch \((final )?(Throwable|Exception) " app/src/main/java`, apart
    from the boundaries listed above;
  - a `Log.d` that names a user.

## Sources

- [AOSP Java code style for contributors](https://source.android.com/docs/setup/contribute/code-style):
  exceptions, logging levels, line length, TODOs.
- [Google Java style guide](https://google.github.io/styleguide/javaguide.html): Javadoc, caught
  exceptions, `@Override`, formatting.
- [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide) and
  [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- [Log info disclosure](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)
  and [security tips](https://developer.android.com/privacy-and-security/security-tips):
  logging, receivers, PendingIntents, TLS.
- [ANRs](https://developer.android.com/topic/performance/vitals/anr): main-thread work, receivers,
  StrictMode.
- [Handlers and inner classes](https://www.androiddesignpatterns.com/2013/01/inner-class-handler-memory-leak.html):
  context leaks.
