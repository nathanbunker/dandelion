## Seedclock

*Your day lives on your lock screen, one part at a time.*

(A dandelion seed head is called a "clock": children blow on it to tell the time. Dandelion
already splits the day into Wake, Morning, Afternoon and Evening. Seedclock tells that time.)

### The big idea

Most to-do apps want you to open them. Seedclock comes to you, as **one living notification**: the
**Now card**. It always shows the part of the day you're in ("Morning · 3 left") and lists those
actions with a ✔ beside each. Check one off from the lock screen and it vanishes, with a few
seconds of Undo. When the part of the day changes, the card rolls over instead of adding a new
alert. Reminders aren't a pile of pings; they're the same card changing state at the right moment.
If something is still undone when a part of the day ends, the card asks once, and a tap opens
**the Sweep**: the leftovers one at a time, each sorted with one swipe or tap (done, later today,
tomorrow, pick a date, cancel). The full app is still there for looking ahead, projects, details
and editing, but most days Nathan doesn't need it.

### A day with it

**6:40.** The alarm goes off. The Now card reads *Wake · 2*: take vitamins, check the weather for
the run. He taps ✔ twice from the lock screen without unlocking. **8:30.** The card rolls to
*Morning · 4*. On the way to work he long-presses the app icon, picks **Capture**, and says
"call the plumber Thursday". Seedclock reads the day from the sentence, and he taps his Home project from the recent ones. At his desk later he
opens it and adds the phone number to the notes. **12:00.** Morning ends with one thing left. The
card asks once: *1 left from Morning*. He opens the Sweep and swipes it to tomorrow. **19:00.**
*Evening · 3*. The card also mentions *2 waiting*: he's been waiting on Bob's quote for nine days.
In the Sweep he taps *Follow up* and adds "text Bob about the quote" for tomorrow morning.
**21:30.** Evening ends with nothing left. The card says *Day done*, and quietly leaves the
notification shade.

### Why Nathan will love it

1. **Check-off with no navigation (P-1, P-2, P-6).** The current part of the day is always one
   glance away, on the lock screen and in the notification shade. Ticking something off is one tap.
2. **Reminders that don't nag (P-21 to P-24).** There's only ever one Seedclock notification. Start
   and end prompts change that card instead of adding new alerts. Nathan sets the times for each
   part of the day, the day of the week, and which projects and action types count.
3. **Leftovers get handled, not ignored (P-8 to P-10).** The Sweep turns "move this" into a quick
   routine at the end of each part of the day, with one-tap moves, any date, and undo instead of
   confirmations.
4. **Waiting items come back on their own (P-4).** Waiting actions show up in the Sweep with
   *Follow up*, *Clear* and *Move*, so they stop going stale.
5. **Capture in two seconds, details later (P-17 to P-20).** Capture from the app icon's shortcut
   or a Quick Settings tile. It uses the open project if there is one. A notification right after
   saving offers *Add details*.

### The bet

**Seedclock's main screen is the notification, not the app.** All the design effort goes into the
Now card and the Sweep that opens from it: one ongoing notification that always shows the current
part of the day, updates the moment he acts on the phone and refreshes from Dandelion on a regular schedule, and is the only
way reminders reach Nathan. The app itself is a clean native companion (Kotlin, Jetpack Compose)
for looking ahead, browsing projects and editing. It talks to new `/api/v1` endpoints backed by the
same services the `/m/*` pages use. Student mode reuses everything except the Now card, which the
Note8 doesn't need.
