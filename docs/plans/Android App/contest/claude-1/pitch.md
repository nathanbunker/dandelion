## Sundial

**Tagline:** Four passes a day, and every pass ends clean.

### The big idea

Dandelion already splits Nathan's day into four parts: Wake, Morning, Afternoon and Evening.
Sundial treats each one as a **pass** with a clear start and a clear end. When a pass opens, Sundial
shows what's in it. When the pass closes, it doesn't nag about each item. It offers one
**Sweep**: a fast, full-screen run through whatever is left, one swipe per item. Swipe right to mark
it done, left to move it to tomorrow, up to push it to the next pass. Tap to pick any date. Every
swipe is undoable and nothing asks "are you sure?". A Sweep of five leftovers takes about ten
seconds, and then the pass is clean. Reminders exist only to open a Sweep, so they stay useful
instead of noisy. The app is native Kotlin and Compose, and it reuses Dandelion's own services on
the server. Quick capture, for example, sends "call Bob Thursday evening" through the existing
`ActionSentenceImportService`, so the phone and the web read sentences the same way.

### A day with it

**6:40, Wake.** Nathan picks up the S23. A home-screen widget shows the Wake pass with
checkboxes: vitamins, scripture, check the weather for the hike. He checks two off right on the
widget without opening the app.

**9:00, Morning opens.** One quiet notification: "Morning: 6 things, 1 overdue from yesterday."
He taps it, sees the list, and long-presses the overdue item to move it to Saturday.

**11:20, church meeting.** He has his church project open. He taps **+** and dictates "follow up
with the clerk about the dinner next Tuesday". It goes into that project, dated next Tuesday,
without a project picker. A minute later he opens it from the "just added" chip and pastes a link.

**12:30, Morning closes.** "Sweep Morning: 3 left." Right, up, left: done, afternoon, tomorrow.
Clean.

**Evening Sweep.** The evening Sweep has one extra card: a **waiting** item that's been sitting
for 9 days ("waiting on the plumber's quote"). One swipe creates "I will call the plumber"
for tomorrow.

**9:30.** The widget reads "Day clean." Tomorrow's Wake pass is already queued.

### Why Nathan will love it

1. **Reminders that close loops instead of nagging (P-21 to P-24).** There's one start
   notification and one Sweep per pass, grouped by Nathan's own rules (by project or action type,
   different on weekdays and weekends). They are never per-item, and he can snooze or skip a pass
   from the notification.
2. **Moving is the fastest gesture, because moving is the common case (P-8 to P-10).** A swipe
   handles the everyday moves and the date sheet jumps weeks ahead in one tap.
   Undo sits in a snackbar for every change (G-10).
3. **Waiting items finally get attention (P-4).** They show up a few at a time in the Evening
   Sweep, with three outs: follow up, move or clear. They also have their own tab, but the Sweep brings them to him.
4. **Capture that knows where he is (P-17 to P-20).** One sentence captures the item, and the
   current project is used when one is open. A Quick Settings tile and a widget button make
   capture one tap from anywhere. A "just added" chip lets him add details a minute later.
5. **Built on what Dandelion already does (G-13, D-5).** It uses thin REST endpoints over shared
   services that the `/m/*` servlets also switch to, with per-device keys built on `WebApiClient`
   that Nathan approves and revokes (G-3 to G-6). Everything is Gradle-wrapper and `adb`
   commands an agent can run. `minSdk 28` keeps the Note8 in play for student mode, which is
   another set of screens in the same app.

### The bet

**The end of each pass is the unit of the day, not the individual action.** Sundial stakes its
design on the Sweep: reminders, the widget, waiting items and moving all feed one ten-second ritual
four times a day. If the Sweep feels good, Nathan stays on top of his to-dos without the app ever
nagging him about a single item.
