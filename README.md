# Logging — log messages to the database (Mendix 11)

A Mendix module that captures your application's runtime log into database records
(`Logging.Message`) so you can **view and search the log inside the running app** — no
server file access or console needed. It runs alongside the normal file/console logging
and has its own independent log level. A scheduled event trims old records so the table
never grows unbounded.

> Modernized for **Mendix 11.12.x** (Java 21) by **Jebershon**, based on the original
> "Logging" module by Alexander Willemsen (CAPE Groep). The runtime log-subscriber API is
> unchanged since Mendix 7/8; the module was rebuilt for the Mendix 11 model/package format
> and the current Java-action base class (`UserAction`, which replaced the removed
> `CustomJavaAction`).

---

## Features

- **Every log line becomes a `Logging.Message` object** — timestamp, level, log node,
  message text, and full stack trace (when present).
- **Independent log level** via the `MinimumLogLevel` constant — capture at `INFO` while
  the console runs at something else.
- **`LogGrid`** — a reusable snippet built on **Data Grid 2** (over a database datasource, so
  the built-in filters work): a **Level drop-down filter**, **Timestamp date filter**,
  **Node / Message text filters**, a **Stack-trace filter**, sortable columns, and
  **live auto-refresh** (every 5 s) so new log lines appear on their own.
- **Overview page** (`Logger_Overview`) hosts the `LogGrid` snippet.
- **Scheduled cleanup** (`SE_Cleanup`) deletes messages older than `RetentionDays`.
- **Start/stop at runtime** through two Java actions.

## Requirements

- Mendix Studio Pro **11.12.x** (built and verified against 11.12.2).
- **JDK 21** for local builds/runs (Mendix 11.12 requirement).
- No Marketplace dependencies and no external JARs — only the Mendix runtime API.

## What's in the module

| Type | Name | Purpose |
|------|------|---------|
| Entity | `Message` | One row per captured log line (`Timestamp` indexed; `Message`/`StackTrace` unlimited). |
| Enumeration | `Level` | `TRACE, DEBUG, INFO, WARNING, ERROR, CRITICAL, NONE` (names match the runtime `LogLevel`). |
| Enumeration | `HasStackTrace` | `YES` / `NO`. |
| Java action | `InitializeLogSubscriber(logLevel)` | Registers the DB log subscriber. |
| Java action | `StopLogSubscriber()` | Stops writing to the database. |
| Microflow | `ASu_StartLogSubscriber` | **After-startup** microflow — starts the subscriber at the configured level. |
| Microflow | `BSu_StopLogSubscriber` | **Before-shutdown** microflow. |
| Microflow | `SCE_CleanupOldMessages` | Deletes messages older than `RetentionDays`. |
| Scheduled event | `SE_Cleanup` | Runs the cleanup daily (03:00 server time). |
| Constant | `MinimumLogLevel` | Minimum level stored (default `INFO`). |
| Constant | `RetentionDays` | Retention window in days (default `30`). |
| Snippet | `SNIPPET_LogGrid` | Reusable Data Grid 2 of log messages with built-in filters + live auto-refresh. |
| Page | `Logger_Overview` | Hosts `SNIPPET_LogGrid` (URL `/logger_overview`). |
| Support class | `logging.support.MendixObjectLogSubscriber` | The `LogSubscriber` implementation. |

## How it works

The Mendix runtime publishes every log line to all registered `LogSubscriber`s. This module
registers a third subscriber (besides console and file) whose `processMessage` creates and
commits a `Logging.Message` using a **system context**, so it never depends on an end-user
session. `ASu_StartLogSubscriber` is wired as the app's after-startup microflow, so the
subscriber is live from boot.

```
 runtime log bus ──► console subscriber
                 ──► file subscriber
                 ──► MendixObjectLogSubscriber ──► Logging.Message (DB) ──► Logger_Overview page
                                                          ▲
                                    SE_Cleanup deletes rows older than RetentionDays
```

## Configuration

| Constant | Default | Notes |
|----------|---------|-------|
| `Logging.MinimumLogLevel` | `INFO` | One of `TRACE DEBUG INFO WARNING ERROR CRITICAL NONE`. **Keep at `INFO` or higher in production** — see caveats. `NONE` disables DB logging. |
| `Logging.RetentionDays` | `30` | Age (days) beyond which messages are purged by `SE_Cleanup`. |

Enable `SE_Cleanup` in the environments where you want purging to run (it ships enabled).

## Security

- `Logging.User` module role: **read** `Message`, **view** `Logger_Overview`, and **execute**
  the page datasource microflows. It is added to the **Administrator** user role by default.
- Log writing happens in a system context, so end users need no write access.
- To let another user role see the log, add `Logging.User` to that role
  (`ALTER USER ROLE <Role> ADD MODULE ROLES (Logging.User);`) or grant directly.

## Using it

1. Run the app (Studio Pro **Run**, or `mxbuild` + your runtime). Requires JDK 21.
2. Log in as an Administrator and open **Log Messages** from the menu (or `/logger_overview`).
3. Filter by level, type in the search box, and use **Refresh** to reload.

## Caveats

- **Keep `MinimumLogLevel` at `INFO` or higher in production.** Committing a `Message` makes
  the data layer emit `TRACE`/`DEBUG` lines; subscribing at those levels can create a
  feedback loop and a very chatty table.
- **The table grows with log volume.** `SE_Cleanup` + the `Timestamp` index keep it healthy —
  keep them enabled. The overview grid caps at the newest 1000 rows per query; for very high
  volumes consider Mendix's built-in Data Grid 2 column filters and pagination.
- The overview filter runs server-side over the most recent 1000 rows (sorted newest-first).

## Publishing this module

1. In Studio Pro: right-click the **Logging** module → **Export module package…** → save
   `Logging.mpk`.
2. To share privately, distribute the `.mpk`. To publish to the Mendix Marketplace, sign in
   in Studio Pro and use **Share → Publish to Marketplace**, or upload the `.mpk` at
   <https://marketplace.mendix.com>. Set a version, description, and the minimum Studio Pro
   version (11.12).
3. Consumers import via **Marketplace → Import** or **App Explorer → Import module package**.

## Credits & license

Original concept and `LogSubscriber` implementation: Alexander Willemsen — CAPE Groep.
Mendix 11 rebuild and overview page: Jebershon. Add your preferred license here before
publishing (e.g. Apache-2.0 or MIT).
