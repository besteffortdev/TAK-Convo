# 06 — ATAK's runtime: class loading, library versions and link checking

Most of the crashes met while embedding Conversations had one cause. **The plugin is compiled
against one set of libraries and runs against another**: ATAK's.

Files: `gradle/atak-runtime.gradle`, `tools/AtakLinkCheck.java`, `tools/atak-link-ignore.txt`.

## Parent-first class loading

ATAK loads a plugin's classes with a class loader whose parent is ATAK's. Class loading
delegates to the parent first, so for every class that exists in both, **ATAK's copy is the one
used**, whatever the plugin packaged. ATAK ships, among others:

| Library | ATAK 5.6.0.24 | ATAK 5.8.0.5 |
|---|---|---|
| `androidx.activity` | 1.8.1 | 1.8.1 |
| `androidx.core:core`, `core-ktx` | 1.17.0 | 1.17.0 |
| `androidx.fragment` | 1.8.9 | 1.8.9 |
| `androidx.lifecycle` | 2.9.4 | 2.10.0 |
| `androidx.savedstate` | 1.3.1 | 1.4.0 |
| `androidx.exifinterface` | 1.4.1 | 1.4.2 |
| `androidx.startup` 1.1.1, `tracing` 1.2.0, `profileinstaller` 1.4.0 | same | same |
| `com.squareup.okhttp3` | 4.11.0 | 4.11.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.8.1 | 1.9.0 |

(5.8 also ships `androidx.browser` 1.8.0, which the plugin doesn't use. ATAK 5.5.1.8 had
core 1.15.0, fragment 1.8.7, lifecycle 2.9.0, savedstate 1.3.0 and coroutines 1.7.3.)

`gradle/atak-runtime.gradle` (applied by both `app/` and `conversations/`) has a version table
per ATAK version and uses the one the build is for (`rootProject.ATAK_MINOR`):

```text
for every configuration:
    force ATAK's version of each library in atakProvidedVersions   # compile against what runs
excludeAtakProvidedFromRuntime(project):
    remove atakProvidedModules from the *RuntimeClasspath           # don't package dead copies
```

The versions were read from `META-INF/*.version` and the dex of each SDK's `atak.apk`. A new
ATAK version needs its own table; the build stops without one.

Consequences for the fork: OkHttp is held at 4.11.0 (upstream uses 5.x, whose API ATAK's copy
lacks), and several AndroidX and Material versions are held back
(see [05](05-conversations-fork.md#build-changes)).

## Desugared default methods

Even with the same library versions, ATAK's copies are **not** what the plugin compiled
against. ATAK's release build (minSdk below 24, R8) desugared the default methods of
interfaces. In ATAK's dex they are **abstract**, with the bodies moved into `$-CC` companion
classes, and R8 removed companions it didn't need. From `atak.apk`:

```text
Landroidx/fragment/app/FragmentManager$OnBackStackChangedListener;
    onBackStackChanged()             PUBLIC ABSTRACT
    onBackStackChangeStarted(..)     PUBLIC ABSTRACT     # default method in the source
    onBackStackChangeProgressed(..)  PUBLIC ABSTRACT     # default method in the source
    onBackStackChangeCommitted(..)   PUBLIC ABSTRACT     # default method in the source
    onBackStackChangeCancelled()     PUBLIC ABSTRACT     # default method in the source
Landroidx/fragment/app/FragmentManager$OnBackStackChangedListener$-CC;
    $default$onBackStackChangeCancelled / Committed / Progressed    # ...Started is gone
```

A plugin class that implements such an interface and relies on the defaults (a lambda, or an
anonymous class overriding only the abstract method) has no body for them. The first call
throws **`AbstractMethodError`**, on ATAK's main thread, which kills ATAK. That is what
happened when a chat was opened: `FragmentManager` called `onBackStackChangeStarted` on
`ConversationsActivity`'s lambda.

Rules that follow:

- implement **every** method of an interface ATAK provides, defaults included;
- no lambdas or method references for such interfaces if they have more than one method.

## Desugared `java.time`

The plugin uses core library desugaring. D8 rewrites `java.time.Duration` in the plugin to
`j$.time.Duration`. ATAK's OkHttp has `callTimeout(java.time.Duration)`, not the `j$` one, so
`builder.callTimeout(Duration.ofSeconds(5))` fails to link (`NoSuchMethodError`). Use the
`(long, TimeUnit)` overloads for calls into ATAK's libraries.

## Checking before running: `AtakLinkCheck`

None of this shows at compile time. `tools/AtakLinkCheck.java` reads both APKs' dex files and
reports what would fail to link:

```bash
java tools/AtakLinkCheck.java <ATAK SDK of the build>/atak.apk app/build/outputs/apk/civ/debug/<plugin>.apk [-v]
```

```text
read class definitions (super, interfaces, methods with their access flags, fields)
     and member references of every classes*.dex in both APKs
resolve(name) = ATAK's class if ATAK has it, else the plugin's        # parent-first

for each concrete plugin class C that ATAK doesn't shadow:
    for each interface I of C, transitively, that ATAK defines:
        for each ABSTRACT method m of ATAK's I:
            if neither C nor a superclass has a body for m:
                report "AbstractMethodError  I.m  by C"

for each method or field reference (K, member) in the plugin's dex:
    c = resolve(K)
    if the lookup involves an ATAK class, and member is found neither in c nor in its
       supertypes, and no platform class could supply it:
        report "NoSuch{Method,Field}Error  K.member"

list the plugin classes ATAK shadows (-v)
drop findings matched by tools/atak-link-ignore.txt
exit 1 if any finding remains
```

The ignore file lists accepted findings, one per line, each with a reason: e.g.
`DrawerLayout` and `SlidingPaneLayout` call `ViewDragHelper.setEdgeSize()`, which ATAK's older
`customview` lacks, but no layout of ours uses them.

The first run found 11 unimplemented methods and 3 missing members. The fork's fixes are
listed in [05](05-conversations-fork.md#d-ataks-copies-of-libraries-win-at-runtime); the rest
are in the ignore file. Run it after every dependency change and every upstream merge. The
development deploy script runs it before each install ([07](07-development-and-testing.md)).

Shadowed classes (80 at the moment: `androidx.annotation.*`, `ListenableFuture`, `jspecify`
annotations) are harmless: they are annotations or identical interfaces.

## SDK and Gradle versions

| | Version | Why |
|---|---|---|
| AGP | 8.13.0 | ATAK's `atak-gradle-takdev` plugin needs AGP 8 |
| compileSdk | 36 | newest AGP 8 supports; upstream 2.20.4 uses 37 |
| minSdk | 23 | |
| Java | 21 source/target, core library desugaring | as upstream |
| JDK to run Gradle | Temurin 21 | |

Constants that only exist in SDK 37 (`Build.VERSION_CODES.CINNAMON_BUN`,
`Manifest.permission.ACCESS_LOCAL_NETWORK`) are copied into `TakConvoCompat`. WebRTC is held at
129.0.0 because 149 needs compileSdk 37.

## Plugin loading

- **Release ATAK loads only TAK.gov-signed plugins.** The Play Store ATAK-CAN 5.8 logs
  `signature mismatch` and refuses the plugin, whatever `plugin-api` it declares. Development
  uses the SDKs' developer `atak.apk` (5.6.0.24, 5.8.0.5), which skips the check.
- `plugin-api` in the manifest is `com.atakmap.app@<ATAK_VERSION>.<FLAVOR>`: the build declares
  5.8.0 by default, 5.6.0 with `-PatakVersion=5.6.0`.
- The SDKs are used offline (`atak.sdk.5.6` / `atak.sdk.5.8` in `local.properties`, see
  [07](07-development-and-testing.md#setup)): `artifacts.tak.gov` sits behind an Appgate SDP
  gateway.
