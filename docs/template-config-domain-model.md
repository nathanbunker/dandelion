# Template Config — Domain Model and Schedule Pattern Reference

## Purpose

The `action_next_template_config` table stores per-template configuration for **recurring action generation**.
It is a satellite table to `action_next`, keyed on the same `action_next_id` as the template row.

This table separates generation-control metadata from the core action fields so that the base `action_next`
record remains unchanged regardless of whether auto-generation is enabled.

---

## Data Model

### Database

Migration: `src/db/v5.2.sql`

Table: `action_next_template_config`

| Column | Type | Default | Description |
|---|---|---|---|
| `action_next_id` | int (PK, FK) | — | References `action_next.action_next_id`, cascades on delete |
| `auto_generate` | char(1) | `Y` | Whether the scheduler should generate instances (`Y`/`N`) |
| `missed_action_behavior` | varchar(16) | `AUTO_CANCEL` | How to handle a generated instance that was not completed |
| `schedule_days_of_week` | varchar(30) | NULL | Active days pattern for Weekly templates |
| `schedule_days_of_month` | varchar(100) | NULL | Active days pattern for Monthly templates |
| `schedule_days_of_quarter` | varchar(200) | NULL | Active days pattern for Quarterly templates |
| `schedule_days_of_year` | varchar(400) | NULL | Active days pattern for Yearly templates |
| `last_generated_date` | date | NULL | Date through which instances were last generated (set by scheduler) |

The `action_next` table also has a new column added by this migration:

| Column | Type | Default | Description |
|---|---|---|---|
| `reschedule_locked` | char(1) | `N` | When `Y`, the scheduler will not move the generated instance to a later date |

### Hibernate / Model Mapping

- Class: `org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig`
- Mapping: `ActionNextTemplateConfig.hbm.xml`
- Generator: `assigned` (PK is set by the caller, not auto-incremented)
- Boolean fields use `yes_no` type (`Y`/`N` char storage)

### Row Lifecycle

- A config row is created or upserted whenever a template is saved via `TemplateEditService.save()` (the Templates page and MCP `update_template` both use it).
- Existing templates without a config row inherit defaults: `auto_generate=Y`, `missed_action_behavior=AUTO_CANCEL`.
- Deleting the template (`action_next` row) cascades to delete the config row automatically.

---

## `missed_action_behavior` Values

| Value | Meaning |
|---|---|
| `AUTO_CANCEL` | The generated instance is marked cancelled if not completed by the end of the day (default) |
| `CARRY_FORWARD` | The generated instance is moved to the next working day if not completed |
| `IGNORE` | The generated instance is left as-is (appears as late/overdue until manually resolved) |

---

## `schedule_days_of_*` Pattern Reference

These fields control **which days within the recurrence period** a generated instance should be created.
They are only relevant when `auto_generate = Y`.

A `NULL` or empty value means **every applicable day** within the period fires. For billable templates, work availability is interpreted according to `missed_action_behavior`: `AUTO_CANCEL` skips non-working dates, `IGNORE` generates on the scheduled date, and `CARRY_FORWARD` generates on the next working date. Non-billable personal templates are not restricted by work availability.

The `template_type` on `action_next` (`D`/`W`/`M`/`Q`/`Y`) determines which schedule field is active.
Daily templates (`D`) have no schedule field. Billable daily templates follow the configured missed-action behavior when a recurrence falls on a non-working date; non-billable personal daily templates fire every calendar day.

### Weekly (`schedule_days_of_week`)

Comma-separated day abbreviations.

| Example | Meaning |
|---|---|
| `MON` | Every Monday |
| `MON,WED,FRI` | Every Monday, Wednesday, and Friday |
| `TUE,THU` | Every Tuesday and Thursday |

Valid tokens: `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`, `SUN`

---

### Monthly (`schedule_days_of_month`)

Two formats may be combined in a comma-separated list.

**Format A — Calendar day number:**

The numeric day of the month.

| Example | Meaning |
|---|---|
| `1` | 1st of the month |
| `1,15` | 1st and 15th |
| `28` | 28th (or last day if month is shorter) |

**Format B — Week-position day:**

`W{week}-{day}` where `{week}` is `1`–`5` or `L` (last), and `{day}` is a day abbreviation.

| Example | Meaning |
|---|---|
| `W1-MON` | First Monday of the month |
| `W2-FRI` | Second Friday of the month |
| `WL-FRI` | Last Friday of the month |
| `W1-MON,WL-FRI` | First Monday and last Friday |

---

### Quarterly (`schedule_days_of_quarter`)

Two formats, same logic as monthly but counted within the quarter (90–92 days).

**Format A — Calendar day of the quarter:**

| Example | Meaning |
|---|---|
| `1` | First day of the quarter |
| `46` | 46th day of the quarter (mid-quarter) |
| `1,46,91` | First day, mid-quarter, and near end |

**Format B — Week-position day:**

`W{week}-{day}` where `{week}` is `1`–`13` or `L` (last week of the quarter).

| Example | Meaning |
|---|---|
| `W1-MON` | First Monday of the quarter |
| `W7-WED` | Wednesday of the 7th week of the quarter |
| `W13-FRI` | Friday of the 13th week of the quarter |
| `WL-FRI` | Last Friday of the quarter |
| `W1-MON,W13-FRI` | First Monday and last Friday of each quarter |

---

### Yearly (`schedule_days_of_year`)

Two formats, same logic as monthly but within the year.

**Format A — MMDD date:**

Four-digit string `MMDD` (no separator).

| Example | Meaning |
|---|---|
| `0101` | January 1st |
| `1225` | December 25th |
| `0101,0701,1225` | January 1st, July 1st, and December 25th |

**Format B — Month + week-position day:**

`{MON}-W{week}-{day}` where `{MON}` is a three-letter month abbreviation.

| Example | Meaning |
|---|---|
| `JAN-W1-MON` | First Monday of January |
| `DEC-WL-FRI` | Last Friday of December |
| `MAR-W1-MON,JUN-WL-FRI` | First Monday of March and last Friday of June |

Valid month tokens: `JAN`, `FEB`, `MAR`, `APR`, `MAY`, `JUN`, `JUL`, `AUG`, `SEP`, `OCT`, `NOV`, `DEC`

---

## Interaction with `reschedule_locked`

`reschedule_locked` is a column on `action_next` (not on the config table). It applies to **generated instances**, not to the template itself.

When the scheduler generates an instance and `missed_action_behavior = AUTO_CANCEL`, the generated instance
is created with `reschedule_locked = Y`. This signals to the rescheduler that the instance should not be
pushed to a future date — it should be cancelled instead.

For `CARRY_FORWARD` behavior, generated instances are created with `reschedule_locked = N` so the
rescheduler is free to move them forward.

---

## Scheduler Reconciliation

The scheduler generates future instances and then applies missed-action behavior to past READY instances.
For `CARRY_FORWARD`, an unfinished instance may move to a date outside the template's recurrence pattern.
For example, an unfinished billable Friday action moves to Monday when the weekend is non-working.
Routine scheduler runs preserve that moved instance rather than cancelling it for not falling on Friday.
Personal templates carry forward using calendar days instead.

Explicit template saves still reconcile future READY instances against the saved schedule, including
cancelling dates that no longer match. Disabling automatic generation cancels future READY instances.
Today's instances with recorded bill entries remain protected during edit reconciliation.

If a carry-forward destination already has a non-cancelled occurrence of the same template, the older
missed instance is cancelled rather than creating a duplicate on that date. Cancelled historical
instances are not restored by the scheduler.
