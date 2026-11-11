## The Dandelion App Contest

Nathan is funding **one** Android app MVP to keep him on top of his to-do items. You are one of
several contestants, AI agents in separate threads, competing to have **your** idea selected and
funded. Contestants may be different models or other instances of the same model. Win on the
merits.

**The prize:** your proposal becomes the plan the app is built from.

**What wins:** an app Nathan will *love to use every day* to keep on top of his to-do items, that
meets the requirements, and that can realistically be built by AI agents working with one
developer.

### Your contestant ID

Nathan gives you a contestant ID when he starts your thread (e.g. `claude-1`, `claude-2`,
`openai`). All your files go in **your own folder**:

```
docs/plans/Android App/contest/<contestant-id>/
    pitch.md      phase 1
    proposal.md   phase 2
    index.html    phase 2
```

Never create, edit or delete files outside your folder. The requirement docs belong to the judges.

### Phase 1: Pitch

Do this phase **without reading other contestants' folders.** Your first idea should be your own.

1. Read the requirements: `README.md`, `Personal App.md`, `Student App.md`,
   `Current Mobile Pages.md`, and `Proposal Brief.md` (what the full proposal must cover). Look at
   the code enough to be credible.
2. **Name your app.** Give it a unique name of your own. Before you commit, check that no other
   contestant's `pitch.md` already uses the name. If one does, pick another.
3. Write `pitch.md`, one page at most, with these sections:
   - **Name**, and a one-line tagline
   - **The big idea:** what makes this app different, in a paragraph
   - **A day with it:** a short story of Nathan using it from waking up to evening
   - **Why Nathan will love it:** three to five points, tied to his requirements
   - **The bet:** the one design choice you're staking the proposal on
4. Commit **only your folder** (`git add "docs/plans/Android App/contest/<contestant-id>"`). Then
   run `git pull --rebase`, push, and stop. Tell Nathan your pitch is in, and wait for him to start
   phase 2.

### Phase 2: Proposal

Nathan starts phase 2 once every pitch is in. **Pitches are now locked.** Don't edit
`pitch.md`.

1. **Study the competition.** Read every other contestant's `pitch.md`.
2. **Sharpen your idea.** Your goal is a *singular* idea that stands out. You may borrow nothing
   that is another contestant's signature idea. You may change direction from your pitch, but the
   proposal must say what changed and why.
3. **Write `proposal.md`.** It must cover everything in `Proposal Brief.md`, sections 1–11, plus
   a short section 12, **"Why this one":** how your proposal compares with the other pitches,
   argued fairly and without misrepresenting them.
4. **Build `index.html`, a page that sells your proposal** to Nathan. It's the first thing the
   judges open. Requirements:
   - **One self-contained file.** Inline CSS, and JavaScript only if it's needed. No external
     fonts, scripts, images or network requests. It must work when opened straight from disk.
   - It should look good on a desktop and on a phone screen.
   - It shows the product: the name, the idea, and **mock screens of the app built in HTML/CSS**
     (the today view, an action, a reminder notification, quick capture). These count for a lot.
   - It lists the key facts: the minimum Android version, the build steps, and how Nathan gets it
     on his phone.
   - **Every claim must be backed by `proposal.md`.** Promising features the proposal doesn't
     plan counts against you.
5. Commit only your folder, run `git pull --rebase`, push, and tell Nathan you're done.

### Rules

- **Proposal only.** Don't write app code, and don't change server code, the database or the
  requirement docs.
- **Requirements are the contract.** You may argue that a requirement should change, openly, in
  your requirements coverage section. Don't quietly ignore one.
- **Stay in your folder** and commit only your own files. Other contestants are pushing to the same
  branch at the same time.
- **Compete on ideas.** Don't disparage other contestants or misstate what they proposed.

### Judging

The judges are Nathan and a Claude judge thread. Nathan has the final say. The Claude judge scores
every entry against this rubric and shows its reasoning.

| Weight | Criterion | What the judges look for |
|---:|---|---|
| 30% | **Nathan will love using it** | Daily interactions are fast and pleasant: today view, check-off, moving, undo, quick capture. Reminders feel helpful, not naggy. |
| 20% | **Meets the requirements** | Every G-*, P-* and D-* requirement is met, or a change is argued openly. Student mode fits later. |
| 20% | **Buildable** | A realistic architecture, server plan and build order for one developer plus AI agents. Each step is usable. The risks are honest. |
| 10% | **Development workflow** | Clear setup, build, install and update steps on Nathan's machine and S23 Ultra, which agents can run from the command line. |
| 10% | **Stands out** | A distinct, memorable idea, not a variation on another entry. |
| 10% | **Pitch and page** | `index.html` and `pitch.md` are persuasive, clear and **honest**: claims match the proposal, and the mock screens are convincing. |

The judges may combine ideas from several entries into the funded plan. The winning entry is the
base.
