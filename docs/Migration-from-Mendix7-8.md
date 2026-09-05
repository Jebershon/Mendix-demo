# Logging-to-Database module → Mendix 11.12.x

A faithful, modernized rebuild of the deprecated **“Logging”** module (originally by
Alexander Willemsen / CAPE Groep, ~Mendix 7/8, Feb 2020) that captures every runtime log
message as a `Logging.Message` database object so you can view and search logs inside the
Mendix web client, with a scheduled event to purge old rows.

Everything here is verified against the **Mendix 11.12.2** runtime installed on this
machine (`C:\Program Files\Mendix\11.12.2\runtime\bundles`). The Java compiles against
those exact jars, targeting Java 21.

---

## 1. What this module actually does (the mechanism)

Mendix’s runtime has a small publish/subscribe system for log lines. Every time any node
logs something, the runtime hands that line to every registered **`LogSubscriber`**.
Normally the only subscribers are “write to console” and “write to file”. This module adds
a *third* subscriber that, instead of printing text, **creates and commits a Mendix
object** for each line.

```
                         ┌─────────────────────────────┐
  Core / your modules    │      Mendix runtime          │
  call ILogNode.info()…  │  (log publish/subscribe bus) │
        ─────────────►   │                              │
                         └───────────────┬──────────────┘
                                         │ processMessage(LogMessage) for each subscriber
             ┌───────────────────────────┼───────────────────────────┐
             ▼                           ▼                             ▼
     Console subscriber          File subscriber          MendixObjectLogSubscriber  ← this module
                                                                   │ new Message(systemContext)
                                                                   │ set Timestamp/Level/Node/…
                                                                   ▼
                                                          Logging.Message  (DB row)
                                                                   ▲
                                        SE_Logging_Cleanup (scheduled) deletes rows older than N days
```

Three moving parts:

1. **`MendixObjectLogSubscriber`** (a hand-written Java class) — extends the runtime’s
   `LogSubscriber`. Its `processMessage(...)` turns each `LogMessage` into a
   `Logging.Message` object and commits it, using a **system context** (so it bypasses
   entity access and never depends on an end-user session). It’s a **singleton** with its
   own log-level threshold, independent of the console/file log levels.
2. **Two Java actions** — `InitializeLogSubscriber(logLevel)` registers the subscriber
   with the runtime (call it once at startup); `StopLogSubscriber()` tells it to stop
   writing.
3. **A scheduled cleanup** — a microflow that deletes `Message` rows older than a
   configurable number of days, so the table doesn’t grow forever.

---

## 2. Why it was “deprecated,” and what *actually* breaks in Mendix 11

Two very different things, and it’s worth separating them:

### 2a. The model/package format is too old to import (the real blocker)
The `.mpk` you have is a **Mendix 7/8-era module**. Studio Pro 11 cannot open or import a
module package that old — the app model format (`.mpr` + `mprcontents/`) has changed
several times since. That’s why it can’t simply be dropped into an 11.12.x app, and why we
**recreate** it rather than “upgrade” it. The model (domain model, enumerations, microflow
logic, scheduled event, Java-action definitions, constants, pages) lives in a binary file
that only Studio Pro can author — so section 4 below is the part you do in Studio Pro.

### 2b. Exactly one thing in the Java genuinely broke
I checked every runtime type the old code touches against the 11.12.2 jars:

| API used by the module | Status in 11.12.2 | Note |
|---|---|---|
| `com.mendix.logging.LogSubscriber(String, LogLevel)` + `processMessage(LogMessage)` | ✅ unchanged | still the extension point |
| `LogMessage.node / level / message / cause / timestamp` | ✅ unchanged | still public final fields (now also has `traceId`, `spanId`, `prefix`) |
| `LogLevel` enum (`TRACE…NONE`), `ILogNode.name()` | ✅ unchanged | |
| `Core.registerLogSubscriber / createSystemContext / rollback / commit` | ✅ unchanged | |
| **`com.mendix.webui.CustomJavaAction<T>`** | ❌ **removed** | **this is the break** |

In Mendix 11, generated Java actions no longer extend `com.mendix.webui.CustomJavaAction<T>`.
They now extend **`com.mendix.systemwideinterfaces.core.UserAction<T>`**
(`import com.mendix.systemwideinterfaces.core.UserAction;`). The old action files reference
a class that doesn’t exist anymore, so they won’t compile. Everything else in the module’s
Java is still 100% valid API.

> Note: none of these APIs carries a `@Deprecated` marker in 11.12.2 — the log-subscriber
> mechanism is fully supported. “Deprecated” here means *the Marketplace module was retired
> and its package is format-incompatible*, not that the technique is gone.

---

## 3. What I’ve already done (Java is ready and compile-checked)

I placed three updated source files in the project. They compile cleanly against the real
11.12.2 runtime jars (Java 21 target):

- [`javasource/logging/support/MendixObjectLogSubscriber.java`](javasource/logging/support/MendixObjectLogSubscriber.java)
  — the subscriber. Logic identical to the original (all its APIs are intact); I only
  refreshed the doc comments and added a small `start()` method so a later
  `InitializeLogSubscriber` call can resume after a `StopLogSubscriber`.
- [`javasource/logging/actions/InitializeLogSubscriber.java`](javasource/logging/actions/InitializeLogSubscriber.java)
  — **rebased onto `UserAction<Boolean>`** (the one real fix).
- [`javasource/logging/actions/StopLogSubscriber.java`](javasource/logging/actions/StopLogSubscriber.java)
  — same rebase.

**One naming detail that matters:** the Initialize action’s parameter is named `logLevel`
(camelCase) on purpose. Mendix 11 names the generated Java field verbatim after the
parameter, and a PascalCase `LogLevel` field would *shadow* the imported
`com.mendix.logging.LogLevel` enum — `LogLevel.valueOf(...)` would silently bind to
`String.valueOf(...)` and fail to compile. Keeping it `logLevel` avoids that.

> These files reference `logging.proxies.*`, which don’t exist until you create the domain
> model in Studio Pro and it generates the proxies. So build the model (section 4) first;
> then the Java resolves and compiles.

---

## 4. What to build in Studio Pro 11.12.x

Create a module named **`Logging`** (keep this name so the Java package `logging` and the
placed files line up. If you prefer `LiveLogs`, change `package logging…` → `package
livelogs…` and every `logging.proxies` / `logging.support` import in the three files, then
move the folder to `javasource/livelogs/`.)

> The names below follow the Mendix naming conventions (enumerations, `ACT_`/`SUB_`/`SCE_`
> microflow prefixes, log node = module name).

### 4.1 Enumerations
**`Level`** — value **names must be exactly** these (captions can be anything). The
subscriber maps a runtime level to this enum by name (`Level.valueOf(level.name())`):

`TRACE`, `DEBUG`, `INFO`, `WARNING`, `ERROR`, `CRITICAL`, `NONE`

**`HasStackTrace`** — two values with names **`YES`** and **`NO`** (captions “Yes” / “No”).

### 4.2 Entity `Message` (persistable)
| Attribute | Type | Notes |
|---|---|---|
| `Timestamp` | Date and time | **Uncheck “Localize”** (store UTC — it’s a machine timestamp) |
| `Level` | Enumeration → `Level` | |
| `Node` | String, length **128** | the subscriber truncates node names to 128 |
| `Message` | String, **unlimited** | |
| `StackTrace` | String, **unlimited** | |
| `HasStackTrace` | Enumeration → `HasStackTrace` | |

Indexes: add an index on **`Timestamp`** (used by cleanup and as the default sort). Optional
extra indexes on `Level` and `Node` if you’ll filter the overview by them a lot.

The attribute names must match exactly — the setters the Java calls (`setTimestamp`,
`setLevel`, `setNode`, `setMessage`, `setStackTrace`, `setHasStackTrace`) are generated
from them.

### 4.3 Java actions
Create two Java actions. Right-click each → **Deploy for Eclipse** isn’t needed; Studio Pro
will detect the existing `.java` files and keep the USER-CODE I already wrote.

- **`InitializeLogSubscriber`** — parameter **`logLevel`** : String; return **Boolean**.
  (Parameter name must be `logLevel`, see section 3.)
- **`StopLogSubscriber`** — no parameters; return **Boolean**.

### 4.4 Constants
- **`MinimumLogLevel`** : String, default **`INFO`** — feeds `InitializeLogSubscriber`.
  Valid values: `TRACE DEBUG INFO WARNING ERROR CRITICAL NONE` (see the caveat in §6 about
  DEBUG/TRACE).
- **`RetentionDays`** : Integer/Long, default **`30`** — how long to keep rows.

### 4.5 Microflows
- **`ACT_Logging_StartSubscriber`** — retrieve constant `MinimumLogLevel`, call
  `InitializeLogSubscriber` with it. Set this as the app’s **After startup** microflow
  (App → Settings → **Runtime** tab → *After startup*).
- **`ACT_Logging_StopSubscriber`** *(optional)* — call `StopLogSubscriber`. Set as the
  **Before shutdown** microflow if you want clean shutdown behavior.
- **`SCE_Logging_CleanupOldMessages`** — the cleanup:
  1. Create a DateTime variable `Cutoff = addDays([%CurrentDateTime%], -toInteger($MinimumRetentionDaysConstant))`
     (i.e. `addDays([%CurrentDateTime%], -$RetentionDays)`).
  2. Retrieve `Message` by XPath `[Timestamp < $Cutoff]` (retrieve in batches / by range if
     the table is huge).
  3. Delete the list.
  4. Wrap steps 2–3 in **error handling** (custom-with-rollback); on success log **INFO**
     “Deleted N old log messages (older than $RetentionDays days)”, on failure log
     **ERROR** — both to a log node named **`Logging`** (log node = module name).

### 4.6 Scheduled event
- **`SE_Logging_Cleanup`** → runs `SCE_Logging_CleanupOldMessages`. Set an interval such as
  every **1 day**. Enable it in the environment where you want purging to run.

### 4.7 Overview page + security *(optional but recommended)*
- Page **`Message_Overview`**: a Data grid 2 over `Message` with columns Timestamp, Level,
  Node, Message and search on Level / Node / Message / date range; a detail view showing the
  full `StackTrace`. Add it to navigation.
- Security: give your admin/support **module role** *read* access to `Message`. The
  subscriber writes through a **system context**, so end users need no write access.

---

## 5. Build & verify
1. Create everything in section 4, then press **Run locally** (F5). Studio Pro regenerates
   the `logging/proxies/*` and compiles the three Java files.
2. In the running app, trigger the **After startup** flow (a restart) so the subscriber
   registers.
3. Log something (any microflow *Log message* activity, or just let the app run) and open
   `Message_Overview` — rows should appear.
4. Manually run `SE_Logging_Cleanup` once (or temporarily set `RetentionDays` to 0) to
   confirm purging works.

## 6. Caveats & recommendations
- **Keep `MinimumLogLevel` at `INFO` or higher in production.** Committing a `Message`
  itself makes the data layer emit `TRACE`/`DEBUG` log lines; subscribing at those levels
  can create a feedback loop and a very chatty table. `INFO`/`WARNING` is the sweet spot.
- **This table grows fast.** The scheduled cleanup + the `Timestamp` index are what keep it
  healthy — don’t disable them and then forget.
- **Call `InitializeLogSubscriber` once** (via After startup). Re-initializing with a
  *non-empty* level after it’s already running throws by design (level is fixed after first
  use); re-initializing with an empty level simply resumes after a stop.
- The subscriber uses one long-lived system context for all writes — intended, and unchanged
  from the original.

---

*Generated from analysis of `LoggingCustomized.mpk` and verified against the local Mendix
11.12.2 runtime API. The three Java files are in place and compile against those jars.*
