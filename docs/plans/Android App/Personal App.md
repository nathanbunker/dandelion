## Personal mode requirements

Audience: Nathan. Personal (non-billable) actions only, never work (billable) ones, enforced on the server. Baseline behavior is in
`Current Mobile Pages.md`.

**Overall direction.** The functions of the current mobile pages are the right set for the MVP.
Every one of them is used. Their interactions, though, are clunky. Proposals should keep the
functions and design better interactions for them. They shouldn't copy the web layout.

### Seeing what to do

- **P-1 Today first.** The main view is today: the actions due today plus any overdue ones.
- **P-2 Time-of-day view.** The day's actions are organized by time of day (Wake, Morning,
  Afternoon, Evening), so Nathan can open the current part of the day and check things off
  quickly.
- **P-3 Look ahead.** Nathan can look at other days, mainly to find items he'd like to do now
  instead of waiting. Moving day by day is enough for the MVP.
- **P-4 Waiting items.** Nathan can see the actions he's waiting on someone else for (the *waiting*
  action type). The current mobile pages don't show these. Their purpose is to prompt him to move
  the item, clear it, or follow up with the person. He doesn't use them as much as he should, so
  making them visible matters.
- **P-5 Projects.** Nathan can browse his open personal projects and see each project's open
  actions: overdue, due today, due later, and unscheduled.

### Acting on an action

- **P-6 Complete** an action. Any actions that were blocked by it become unblocked.
- **P-7 Cancel** an action.
- **P-8 Move quickly.** One-step options for the common moves: to tomorrow, to another time of day,
  or to one of the next few days.
- **P-9 Move to any date.** Full control to pick any future date and time of day. Moves of more
  than 7 days ahead are common.
- **P-10 Undo.** Complete, cancel and move happen immediately, with a short undo window and no
  confirmations (G-10).
- **P-11 Read notes** on an action.
- **P-12 Add to notes** on an action. Editing existing notes isn't needed on the phone.
- **P-13 Open link.** Open the action's link, if it has one.
- **P-14 Edit an action:** project, description, action type, date, time of day, link and
  assignee.
- **P-15 Block on a new action.** Create a new action that the current one waits on. The current
  action comes back when the new one is done (today's "Schedule & Block").
- **P-16 Complete and say what's next.** Complete an action and, in the same step, describe the
  follow-up action, including when it happens (today's "Complete & Next").

### Capturing new actions

- **P-17 Quick capture.** Adding an action must be fast. At minimum Nathan gives *what* needs to be
  done and *when*.
- **P-18 Project from context.** If Nathan is already looking at a project (e.g. in a church
  meeting with that project open), a new action goes to that project without picking it.
- **P-19 Pick a project.** Otherwise Nathan picks the project quickly.
- **P-20 Add details later.** Nathan can quick-add an action and then, a minute later, add more
  detail to it (notes, link, type, a more exact time).

### Reminders and schedule

Dandelion records each action's project, type, and general time of day (Wake, Morning, Afternoon,
Evening). It deliberately doesn't plan the day beyond that. The phone takes it from there and
decides when to alert, using settings that live in the app on top of what Dandelion knows.

**What the four parts of the day mean.** The names are not clock times. For Nathan:

| Part | Meaning | Example |
|---|---|---|
| **Wake** | First thing after getting up. Nathan's Wake starts at 4am. | |
| **Morning** | Has to get done **during the working day**, or during the day on a non-working day such as Saturday. Not early morning. | "Call the shop to see if my car is ready": they close at 5, so not at 6am, and not after work when they've closed. |
| **Afternoon** | After he clocks out (usually about 3pm), or second priority. | |
| **Evening** | Getting ready for bed. | |

So on a workday Morning runs alongside work, and the desktop dashboard shows personal Morning
actions in its Today column ("Personal (Morning)") because they're part of his regular work day,
even though they aren't work items. Personal Wake, Afternoon and Evening actions don't appear on
the dashboard.

- **P-25 Times are set by the user.** On the phone, each user sets for each part of the day: when
  it starts (the order is fixed), whether it has an end check (P-21), and when that check fires.
  Nathan's meanings and times are his starting values; they aren't built into the app.
- **P-25a Not an alarm clock.** Checks are ordinary notifications. The 7am Wake check means "record
  your morning routine", not "wake up".

- **P-20a The screen follows the day.** When a new part of the day starts, what the phone shows
  switches to it silently, so whenever Nathan looks it's in the right mode. Nathan's times: Wake
  4am, Morning 8am, Afternoon 12pm, Evening 8pm.
- **P-21 Check before the switch.** An hour before Wake, Morning and Afternoon end (7am, 11am,
  7pm for Nathan), if anything in that part of the day is still open (not completed, cancelled or
  moved), the phone reminds him once. If it's all cleared, nothing happens. **No check for
  Evening,** and no wake-up alert about the night before.
- **P-21a Carry-over.** Unfinished Morning items stay visible through Afternoon. In Evening, every
  unfinished item from today is shown. Wake items aren't carried past Wake. Morning items have to
  be done by noon; Afternoon items are "sometime later in the day", not due when Afternoon starts.
- **P-21b Routines expire.** Wake and Evening are almost always templated (recurring) actions.
  Most are set to Auto Cancel: if Nathan doesn't check one off, Dandelion cancels it after the day
  ends. The app treats these as expiring routines, not as overdue work, and doesn't offer to move
  them to tomorrow.
- **P-22 Start-of-period prompt.** *Dropped (2026-11-11).* Nathan doesn't want a prompt when a
  part of the day starts; the screen changing is enough (P-20a).
- **P-23 Configurable on the phone.** Nathan configures reminders in the app: when each period's
  reminders fire, and for which **groups or classes of actions** (for example by project or action
  type). Settings can differ by day of the week. Per-action reminder settings are not needed.
- **P-24 Built on Dandelion's time blocks.** The app starts from Dandelion's general time-of-day
  blocks and lets Nathan fine-tune the timing on the phone. Reminder settings don't need to change
  anything in Dandelion on the desktop.

Reminders are new, and Nathan is open to ideas. Proposals should suggest how reminders behave
(which ones to have, how often, how they are grouped and silenced) as well as how they're built.
