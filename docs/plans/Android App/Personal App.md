## Personal mode requirements

Audience: Nathan. Personal (non-billable) projects only. Baseline behavior is in
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
  instead of waiting. This is used less often than today's view.
- **P-4 Waiting items.** Nathan can see the actions he's waiting on someone else for (the *waiting*
  action type). The current mobile pages don't show these.
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

- **P-21 Reminders for unfinished items.** The phone alerts Nathan about actions that need to be
  done at the appropriate time, and especially about items in a time of day that he hasn't checked
  off.
- **P-22 Control over alerts.** Nathan has fine control over the alerts: which ones fire, and
  when.
- **P-23 Set my schedule.** Nathan can set his daily schedule from the phone (when each time of day
  starts), and the alerts follow it.

### Open questions

1. **Reminder triggers (P-21).** What should prompt an alert? Options include the start of a time
   of day, a set time before it ends, items still open at the end of it, and actions with a
   specific time. Is it one summary alert per time of day, or one per action?
2. **Alert controls (P-22).** What does "fine control" need to cover? For example: on/off per time
   of day, quiet days such as Sunday or vacation, snoozing, and per-project or per-action muting.
3. **Schedule (P-23).** Is the schedule the same every day, or does it differ by weekday (school
   days, Sundays)? Should the schedule set on the phone also change Dandelion on the desktop, or
   only control the phone's alerts?
4. **Waiting items (P-4).** When you look at waiting items, what do you do with them? For example:
   nudge the person, mark it received, or reschedule the check-in. And should they appear in
   today's list or in a separate place?
5. **Look ahead (P-3).** Besides browsing day by day, would a view of "everything in the next few
   days" be useful, or is day by day enough?
