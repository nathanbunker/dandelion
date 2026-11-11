## Student app: MVP scope (draft)

Audience: Nathan's daughter. Purpose: **see today's school work and chores, mark them done, earn
points, and spend or save them.** Baseline behavior is in `Current Mobile Pages.md`.

The student app is the same Android app. A user with `workflowType = STUDENT` signs in and gets
student mode (see `README.md`, architecture question 1).

Status: **first draft for review.** Items marked **?** need a decision.

### Screens

Bottom navigation: **School · Store · Bank**, the same three areas as the web pages.

#### 1. School (home)

- The day's date at the top. Swipe or tap arrows for Previous/Next, and a Today button.
- Sections for **Required**, **Assigned**, **Optional** (school, with points), and **Chores** (no
  points). "All done!" messages show for empty Required and Assigned sections.
- **Tap the checkbox** to complete. The row turns green with a short celebration and the points
  shown, e.g. "+10".
- Completed and cancelled items stay visible (green, and greyed with strike-through), the same as
  today.
- A **points earned today** bar stays at the bottom.
- ? **Undo** for a mistaken check. Today a completion can't be reversed by the student, and it has
  already written a points ledger row. Undoing would need a reversing ledger entry. Options: allow
  undo for a few seconds (snackbar) only, or not at all.
- The student can't add, edit, cancel, or postpone, the same as today.

#### 2. Store

- A **Spendable points** balance at the top.
- **Available** offers as cards with an image, title, description, and price. **Buy** is disabled
  (with a "need N more" hint) when points are short.
- A confirmation before buying ("Buy *Movie night* for 150 points?").
- **Bought** (with Return), **Coming Soon**, and **Delivered** sections.
- Buying still emails the guardian, which is server behavior and unchanged.

#### 3. Bank

- **Spendable** and **Savings** balances.
- Move points between them with a number entry and two buttons, the same as today.
- ? A short **history** list from the ledger (earned, bought, refunded, transferred), which the web
  page doesn't show today. It would help her see where points came from.

### Not in the MVP

- Guardian functions: creating offers, moving offers to Coming Soon or Delivered, editing school
  templates, and approving purchases. These stay on the desktop web.
- Notifications ("You have 3 assignments left today").
- Offline completion with sync later.

### Open questions for Nathan

1. What device will she use (phone or tablet, which Android version)? Is there Wi-Fi where she does
   school? This decides how soon offline matters.
2. Should she be able to undo a completion, and for how long?
3. Should she see **past days**? (Today she can go back and complete earlier days' items, which
   then count toward *today's* points total.) Should completing a past day's work be allowed?
4. Should the Required, Assigned, and Optional labels be the words she sees, or should they be
   friendlier?
5. Add the points history to the Bank?
6. Should sign-in be a password, or a magic link sent to an address you control (so you approve
   each new device)?
