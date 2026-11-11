## Current mobile pages (baseline)

This is an inventory of the server-rendered mobile pages as of version 5.15.1 (November 2026). The
Android app has to cover what is listed here, or deliberately drop it. Mark dropped items in
`Personal App.md` or `Student App.md`.

All of these pages are servlets that write HTML directly. They use the shared `CssServlet`
stylesheet and run as the signed-in web user in their active workspace.

---

### Sign-in and routing

`LoginServlet` offers a **Desktop / Mobile** choice. After sign-in:

- A user with `workflowType = "STUDENT"` always goes to `student/school`.
- Otherwise, choosing Mobile goes to `m/todo`, and choosing Desktop goes to the desktop home page.

The mobile footer has a "Desktop Mode" link. The student footer has "Logout" instead.

---

### Personal mobile (`/m/*`), `org.openimmunizationsoftware.pt.mobile.servlet`

Shared base: `MobileBaseServlet`. The top menu is **Project · Todo · Action**.
`isShowWork()` is hard-coded to `false`, so **only non-billable (personal) work appears**. A
project counts as billable when its bill code has `billable = Y`.

#### Todo (`m/todo`), `TodoServlet`

The landing page: "what do I need to do today".

- **Which actions:** `READY` actions in the active workspace, where the user is the owner
  (`contactId`) or the assignee (`nextContactId`), the description is non-empty, the action date is
  on or before the selected date, and the action is not billable.
- **Ordering:** action date, then priority (high first), then last-changed date, then description.
- **Today view:**
  - An **Overdue** section (actions dated before today), each marked "Overdue".
  - Then the selected day's actions grouped by time slot: **Wake, Morning, Afternoon, Evening**.
    A missing slot counts as Afternoon. Empty groups are hidden.
- **Other days:** only that date's actions, with no overdue section.
- **Each row:** the project icon if it has one, otherwise the project name (links to the project
  page), then the description (links to the action detail page). Three quick buttons:
  - ✔ **Complete**: status → `COMPLETED`, and actions blocked by this one are unblocked.
  - → **Reschedule** pop-up: pick a time slot (Wake/Morning/Afternoon/Evening) and/or a day (Today
    plus the next 7 days). This sets the date and slot.
  - ✖ **Cancel**: status → `CANCELLED`, and blocked actions are unblocked.
- **Date navigation:** Previous (hidden on today), Today, Next.
- **Title:** "Todo Today", "Todo Thursday" (1–7 days ahead), "Todo Next Thursday" (8–14 days
  ahead), otherwise "Todo <date>".

#### Project (`m/project`), `ProjectServlet`

- **List:** open (not `CLOSED`), non-billable projects in the workspace, by name, with a count of
  actions due today for each.
- **Detail (`?projectId=`):** "All Projects" and "Add Action" buttons, then the user's open `READY`,
  non-template actions for the project, grouped as **Overdue, Due Today, Due Later, Unscheduled**.
  Each row shows its date (links to that day's Todo) and description (links to the action detail
  page), with the same ✔ / → / ✖ quick buttons as Todo.

#### Action (`m/action`), `ActionServlet`

There are two modes.

**Action detail (`?viewActionId=`)** shows the date heading, the project (icon + name), the
description, an **Open Link** button if the action has a URL, and the **Notes**. Buttons:

- **Complete**, **Postpone** (to tomorrow, or to today if the action is overdue), and **Edit**.
- **Add Note**: a free-text box that appends to the action's notes (`ActionNotesService.append`).
- **Blocking Action ("Schedule & Block")**: type a description. This creates a new `WILL` action
  dated today on the same project, sets the current action as blocked by it, and clears the current
  action's date. When the blocking action finishes, the original comes back.
- **Complete & Next**: verb chips (*I will, I might, I am waiting, I will meet, I would like to, I
  have committed, I have set goal to*) pre-fill a sentence box, e.g. "I will go to the store
  tomorrow". Submitting completes this action. If the sentence has more than a bare verb, it also
  creates the next action through `ActionSentenceImportService.saveNewActionFromSentence`, which
  parses the date and other details out of the sentence. Afterwards the page returns to today's
  Todo.
- Navigation back to Todo or to the project.

**Add/Edit form (`?projectId=` for new, `?actionNextId=` to edit)** has these fields:

- Project (radio list of personal projects)
- Description (up to 1200 characters)
- Action type (*will, will meet, will contact, will follow up, will review, will document, might,
  would like to, waiting, committed to, goal*)
- Date and time slot (Afternoon if none is picked)
- Link URL
- Assignee contact (from the project's contacts)
- Note

There is also a **Delete** button, which sets the status to `CANCELLED`. On save, priority is
copied from the project, a new action gets a standard action set, and a user-authored action with
a description becomes `READY` even without a date. A meeting-type validation runs first
(`LinkedMeetingService.actionTypeError`).

#### Oops (`m/oops`), `OopsServlet`

This is the generic error page that the other mobile pages redirect to.

---

### Student (`/student/*`), `org.openimmunizationsoftware.pt.student.servlet`

Shared base: `StudentBaseServlet`. The top menu is **School · Store · Bank**. Points are recorded
in `GamePointLedger` rows (`pointChange`, `accountBucket` = `SPENDABLE` or `SAVINGS`, `entryType`
= `COMPLETE`, `PURCHASE`, `REFUND` or `TRANSFER`, plus a note and created date and user). A balance
is the sum of a bucket's rows. Older rows with no bucket count as spendable.

#### School (`student/school`), `StudentSchoolServlet`

- **Which actions:** the student's actions dated **exactly** on the selected day, with no overdue
  roll-forward. Statuses `READY`, `COMPLETED` and `CANCELLED` all show, so finished work stays
  visible.
- **Grouping:**
  - **Billable actions are "school"**, split into **Required** (*committed to* or *overdue to*),
    **Optional** (*might* or *would like to*), and **Assigned** (everything else). Required and
    Assigned show "All … work is complete for today" when they're empty.
  - **Non-billable actions are "Chores"**, with no points column.
- **Each row:** project icon + name, then the description. School rows show a **Points** column.
  Completed rows have a green tint and the label "Completed". Cancelled rows are grey and struck
  through.
- **Only action: ✔ Complete.** It is allowed only when the action is in the user's workspace, its
  date matches the viewed day, and it isn't already completed or cancelled. It sets the status to
  `COMPLETED`, unblocks dependent actions, and, if the action has `gamePoints`, writes a `COMPLETE`
  ledger row (SPENDABLE) for those points. There is no cancel, postpone, edit, or add.
- **Date navigation:** Previous, Today, Next.
- **Fixed footer:** "Points Earned For This Day: N". This is the sum of positive `COMPLETE` ledger
  rows **created** on that day.
- The school actions themselves come from school templates that the parent manages on the desktop
  (`ManageSchoolTemplatesServlet`, Plan Ahead template scheduler).

#### Store (`student/store`), `StudentStoreServlet`

- A header shows **Spendable Points**.
- Offers (`StudentOffer`: title, description, `pricePoints`, image, status) belong to the student's
  contact. They're grouped by status:
  - **Available** (`AVAILABLE`): **Buy**, enabled only if the price fits the spendable points.
  - **Bought** (`BOUGHT`): **Return**.
  - **Coming Soon** (`FULFILLING`)
  - **Delivered** (`DELIVERED`)
  - `WITHDRAWN` offers are not shown.
- **Buy:** writes a `PURCHASE` ledger row for minus the price, sets the status to `BOUGHT` and the
  bought date, and **emails the guardian** with a link to their store page.
- **Return:** only while `BOUGHT`. Writes a `REFUND` row for plus the price and sets the status
  back to `AVAILABLE`.
- Images come from `StudentOfferImageServlet?mode=view&size=thumb`.
- Moving an offer to Coming Soon or Delivered, and creating offers, is done by the guardian on the
  desktop (`StudentOfferSetupServlet`, `StudentOfferEditServlet`).

#### Bank (`student/bank`), `StudentBankServlet`

- Shows **Spendable** and **Savings** balances.
- **Transfer** a number of points: *Move to Savings* or *Move to Spendable*. This writes a pair of
  `TRANSFER` rows (minus from one bucket, plus to the other). The transfer must be positive and
  can't exceed the source balance.
