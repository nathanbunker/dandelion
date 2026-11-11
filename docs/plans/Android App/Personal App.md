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
| **Wake** | First thing after getting up. (Assumed; Nathan hasn't described it further.) | |
| **Morning** | Has to get done **during the working day**, or during the day on a non-working day such as Saturday. Not early morning. | "Call the shop to see if my car is ready": they close at 5, so not at 6am, and not after work when they've closed. |
| **Afternoon** | After he clocks out (usually about 3pm), or second priority. | |
| **Evening** | Getting ready for bed. | |

So on a workday Morning runs alongside work, and the desktop dashboard shows personal Morning
actions in its Today column ("Personal (Morning)") because they're part of his regular work day,
even though they aren't work items. Personal Wake, Afternoon and Evening actions don't appear on
the dashboard.

- **P-25 Start times are set by the user.** Each user sets exactly when each part of the day
  starts, on the phone. Nathan's meanings above are his; they aren't built into the app.

- **P-21 End-of-period nudge.** At the end of a time of day, if items in it are still undone, the
  phone prompts Nathan about them.
- **P-22 Start-of-period prompt.** At the start of a time of day, the phone can prompt Nathan with
  what's in it, e.g. "Time to be done with work. Here's what you need to do today."
- **P-23 Configurable on the phone.** Nathan configures reminders in the app: when each period's
  reminders fire, and for which **groups or classes of actions** (for example by project or action
  type). Settings can differ by day of the week. Per-action reminder settings are not needed.
- **P-24 Built on Dandelion's time blocks.** The app starts from Dandelion's general time-of-day
  blocks and lets Nathan fine-tune the timing on the phone. Reminder settings don't need to change
  anything in Dandelion on the desktop.

Reminders are new, and Nathan is open to ideas. Proposals should suggest how reminders behave
(which ones to have, how often, how they are grouped and silenced) as well as how they're built.
