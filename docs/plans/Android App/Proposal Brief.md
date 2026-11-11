## Proposal brief: Dandelion Android app

You are one of two agents asked, separately, to propose how to build the Dandelion Android app.
Nathan will compare the two proposals and choose one or combine them. You won't see the other
proposal, and you shouldn't look for it.

Write your proposal to `docs/plans/Android App/Proposal <your name>.md`, e.g. `Proposal
Claude.md` or `Proposal Codex.md`. Don't change any other file in this folder. If you think a
requirement is wrong or missing, say so in your proposal rather than editing the requirements.

### Read first

1. `README.md` (this folder): goals, the two deliverables, the G-* and D-* requirements, and the
   decisions already made. **Decisions are settled. Don't reopen them.**
2. `Personal App.md`: the P-* requirements. **This is the MVP.**
3. `Student App.md`: the S-* requirements. This is deliverable 2. Show how it fits, but don't
   detail it.
4. `Current Mobile Pages.md`: what today's mobile and student web pages do, with class names.
5. The project's `CLAUDE.md` for conventions and the local environment.
6. The code these pages use, and the existing API: `org.openimmunizationsoftware.pt.mobile.servlet`,
   `...pt.student.servlet`, `...pt.api.v1` (REST resources and MCP tools),
   `...pt.api.common.ApiKeyAuthFilter`, and the services under `org.dandeliondaily`. Read enough to
   ground your server-side plan in the real code.

### Context

- **Server:** Java servlets plus Hibernate on Tomcat 9, with MySQL 8, built with Maven into
  `dandelion.war`. It's the same codebase for the web UI, the REST API (`/api/v1`, `X-Api-Key`
  auth, one key per client, tied to a user and a workspace) and the MCP endpoint. Production is
  `https://dandelion-daily.org`. Locally it runs at `http://localhost:8089/dandelion/`.
- **Phone:** Samsung Galaxy S23 Ultra, the only MVP install.
- **Dev machine:** Windows 11 Pro, Intel i7-1370P, 64 GB RAM. Already installed:
  - Android Studio and the Android SDK (`%LOCALAPPDATA%\Android\Sdk`), with platform
    `android-36.1`
  - system images for API 36 and 36.1, and two emulators (`Pixel_7`, `Medium_Phone`)
  - `adb` (platform-tools)
  - Java 25 and Maven 3.9 (for the server)

  Gradle isn't on the PATH, and `ANDROID_HOME` isn't set.
- **Who builds it:** mostly AI coding agents working in this repository, with Nathan reviewing and
  testing on his phone. Nathan is an experienced Java developer, but new to Android.

### What your proposal must cover

Keep it concrete and decision-ready. Where you make a choice, give the main alternative you
rejected and why. Aim for something Nathan can read in about 20–30 minutes. Use diagrams or
sketches only where they make something clearer.

1. **Summary.** Your approach in one page: the key choices and why.
2. **Interaction design for personal mode.** How each part of the day feels on the phone: the
   today and time-of-day views, completing and moving actions (quick and full-control), undo,
   quick capture with and without a project, adding details afterwards, waiting items, projects,
   and action detail. Nathan finds the current mobile pages clunky, so propose better
   interactions; don't port the web layout. Rough screen sketches (ASCII or a short description per
   screen) are welcome.
3. **Reminders.** These are new, and Nathan is open to ideas (P-21 to P-24). Propose how reminders
   should *behave*: which kinds, default timing, how they are grouped, how they are configured by
   group or class of action and by day of the week, snoozing and silencing, and what the alert
   lets him do directly (for example, complete an item from the notification). Then propose how
   they work technically, given Android's limits on background work and exact alarms.
4. **App architecture.** Language, UI toolkit, the main libraries, project structure, and where the
   app code lives (this repository or a separate one, and why). Include how personal and student
   modes share one app (G-1).
5. **Minimum Android version (G-12).** The minimum and target versions you propose. If the minimum
   is above Android 9, say what that buys Nathan, and what it means for student mode on the Note8.
6. **Authentication and devices (G-3 to G-7).** How the app signs in, stays signed in, and gets
   approved or revoked, and how that builds on or changes the existing API-key system.
7. **Server-side changes.** The new or changed API surface, and how the logic now inside the
   mobile and student servlets moves into shared services that the web pages and the app both use
   (G-13). Include ownership checks (G-8) and any schema changes. Keep this at the level of
   endpoints, services and responsibilities, not full code.
8. **Development setup and workflow (D-1 to D-5).** Step by step:
   - What Nathan needs to install or configure beyond what's already there.
   - Emulator vs. the real phone: what each is used for, and how the phone is connected (USB,
     wireless debugging).
   - How an agent builds, installs, runs and tests from the command line.
   - How the app talks to the local server during development (the emulator and the phone reach
     `localhost:8089` differently) and to production in real use.
   - How Nathan installs and updates the app on his phone over time, without a store.
   - The testing approach: what is unit-tested, what is checked on a device, and how reminders are
     tested.
9. **Build order.** The sequence of steps for deliverable 1, each one usable or testable on its own.
   Say what Nathan can do with the app at the end of each step. Then give a short outline of how
   deliverable 2 follows.
10. **Requirements coverage.** A table with every G-*, P-* and D-* requirement and how your design
    meets it. Mark any requirement you meet only partly, or think should change, and say why. Give
    S-* requirements a single line on how they fit.
11. **Risks and open questions.** What could go wrong, what you're unsure of, and what Nathan
    needs to decide.

### Ground rules

- **Requirements are the contract.** Don't quietly drop or reinterpret one. Challenge it openly in
  section 10 if you disagree.
- **Respect the project conventions** in `CLAUDE.md`: the UI and the API share services, DAOs live
  in `...pt.doa`, migrations are sequential `src/db/vN.M.sql` files, and so on.
- **Prefer boring, well-supported choices** for a one-person project maintained mostly by agents.
  Justify anything unusual.
- **Don't write app code** or change server code. This is a proposal only.
