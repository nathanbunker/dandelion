## Student mode requirements (deliverable 2)

Audience: Nathan's daughter. Device: Samsung Galaxy Note8, Android 9 (G-12). It's a shared family
phone that she uses only while doing homework, and it's always online. Baseline behavior is in
`Current Mobile Pages.md`.

**This is not part of the MVP.** It comes after personal mode, in the same app. The student web
pages work well enough until then. Proposals should show how this fits into the deliverable 1
design without rework, but don't need to detail it.

**Overall direction.** The current student pages work well for her. The wording and structure are
fine. Her one complaint is being logged out (G-4). The app should keep what works.

### School

- **S-1 Day view.** She sees one day's school work and chores. It opens on today.
- **S-2 Same groups.** School work is grouped as **Required, Assigned, Optional**, with chores in
  their own group. Keep these labels.
- **S-3 Points shown.** Each school item shows its points.
- **S-4 Finished items stay.** Completed and cancelled items stay visible and are clearly marked as
  done or cancelled.
- **S-5 Complete.** She can mark an item done. This earns its points.
- **S-6 Undo.** She can undo a completion herself for a few seconds afterwards, to recover from an
  accidental tap. The points it earned are taken back (G-10).
- **S-7 Other days.** She can go to previous days and complete their unfinished items. The points
  count toward **today**, the day she completes them. This matches the current behavior.
- **S-8 Today's points.** She can always see how many points she has earned today.
- **S-9 No editing.** She can't add, edit, cancel or reschedule items. Those are done by Nathan on
  the desktop.

### Store

- **S-10 Spendable balance.** She sees her spendable points.
- **S-11 What she can buy.** She sees the available offers (picture, title, description, price) and
  can tell which ones she can afford.
- **S-12 Buy.** She can buy an offer she can afford. Points are deducted and Nathan is emailed, as
  happens today.
- **S-13 Return.** She can return an offer she has bought but hasn't received yet, and she gets her
  points back.
- **S-14 Order status.** She sees what she has bought, what is coming soon, and what has been
  delivered.

### Bank

- **S-15 Balances.** She sees her spendable and savings balances.
- **S-16 Move points** between spendable and savings.
- A points history isn't needed. Balances and what she can buy are enough.

### Not needed

- Reminders. She uses the phone only during homework.

### Open for proposals

- **Undo for purchases.** Return (S-13) already exists, and buying emails Nathan. Whether buying
  also needs a quick undo, and what happens to that email, is left to the proposals.
