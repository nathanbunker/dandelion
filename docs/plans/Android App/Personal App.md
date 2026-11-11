## Personal app: MVP scope (draft)

Audience: Nathan. Purpose: **see what I need to do and mark it off**, plus quick capture. This is
personal (non-billable) projects only, the same as the `/m/*` pages today. Baseline behavior is in
`Current Mobile Pages.md`.

Status: **first draft for review.** Items marked **?** need a decision.

### Screens

#### 1. Today (home)

The Todo page, reworked for a phone app.

- An **Overdue** group, then **Wake / Morning / Afternoon / Evening** groups for the day.
- Each row shows the project icon or name and the description.
- **Tap a row** to open Action Detail.
- **Swipe right to complete. ? Swipe left to reschedule.** Cancel moves into the reschedule sheet
  or Action Detail, so a stray swipe can't throw away an action. Today's ✖ button is one tap from
  data loss.
- **Reschedule sheet** (bottom sheet): time slot chips plus day chips (Today and the next 7 days),
  the same choices as the web pop-up. ? Add a "Pick date…" calendar for anything further out.
- **Day navigation:** swipe left and right between days, or use a date header with
  Previous/Today/Next.
- **Undo** snackbar after complete, cancel, or reschedule. This is new; the web pages have no undo.
- Pull to refresh.
- A **+** button opens Quick Add.

#### 2. Action detail

- Project, description, date and slot, **Open Link**, and **Notes**.
- Buttons: **Complete**, **Postpone** (to tomorrow), **Reschedule…**, **Edit**, **Cancel**.
- **Add note**: appends to the notes. ? The `Action Notes Notepad` plan makes notes one editable
  text value. If that ships first, the app should edit the whole notepad.
- **Complete & Next**: verb chips plus a sentence box, parsed on the server by the existing
  sentence import.
- **Schedule & Block**: type a blocking action. The current action waits on it.

#### 3. Projects

- A list of open personal projects with today's count for each.
- Project detail: **Overdue / Due Today / Due Later / Unscheduled**, with the same row gestures,
  and an **Add action** button pre-set to that project.

#### 4. Quick Add / Edit action

- ? **Sentence-first:** one box ("I will call the dentist Friday morning") parsed by the server.
  This is the same mechanism as Complete & Next, and it's the fastest way to capture on a phone.
  The structured form below stays available for editing.
- Structured form fields: project, description, action type, date, time slot, link, assignee, and
  note.
- **Delete** (it cancels the action) from the edit screen.

#### 5. Settings / account

- The signed-in user, the server (production by default; ? a dev/local option for testing against
  `localhost:8089`), and sign out.

### Dropped or changed from the web pages

- The separate "Action" menu tab goes away. Adding is the + button, and editing starts from Action
  Detail.
- The "Desktop Mode" link is replaced by an "Open in browser" item, if it's wanted at all.

### Not in the MVP

- Work (billable) projects. These are planned as a later **Personal / Work** toggle.
- Meetings, time tracking, Plan Ahead, focus mode, daily review, and narratives.
- Notifications, widgets, and offline use.

### Open questions for Nathan

1. Swipe gestures vs. visible buttons: which do you prefer for complete and reschedule?
2. Should cancel stay as a one-tap action on the list, or move into the reschedule sheet or detail
   screen?
3. Sentence-first quick add, or the structured form as the default?
4. Should the Today screen also show actions you're **waiting on** (`WAITING` type), or keep them
   out as today?
5. Should the date heading follow your Dandelion time zone setting or the phone's? (Today it's the
   Dandelion setting; see `docs/TIMEZONE-DATE-HANDLING.md`.)
