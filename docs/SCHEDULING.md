# Scheduling

Nihongo Flow has two separate systems. Keeping them apart is what lets a learner miss days
without the app falsifying anything or overwhelming them.

## A. Memory scheduler (FSRS)

- Implementation: [java-fsrs](https://github.com/open-spaced-repetition/java-fsrs) 1.0.0,
  FSRS-6, by the open-spaced-repetition project (MIT licence). `FsrsScheduler` converts
  Nihongo Flow types to the library's and back; it does no scheduling maths itself.
- Defaults: desired retention 90%, library default FSRS-6 weights, learning steps 1 and
  10 minutes, relearning step 10 minutes, maximum interval 100 years, interval fuzz on.
- Real elapsed time is always used. A review is applied at the time it really happened, and
  a review earlier than the card's last review is rejected.
- Answer buttons show un-fuzzed previews, so the real interval can differ slightly from the
  preview for long intervals.

### Verification

`FsrsSchedulerTest` (14 tests) includes two reference cases copied from java-fsrs's own
suite, run through our wrapper:

- the interval sequence `0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40` for a fixed
  answer sequence with fuzzing off;
- stability 49.4472 and difficulty 6.8271 after a fixed review history.

Other tests cover each rating on new and review cards, preview accuracy, elapsed-time
handling, desired retention, retrievability at the due date, immutability of input state
and configuration validation.

### Parameter optimisation

java-fsrs cannot optimise parameters. Optimisation will need fsrs-rs (Rust, via JNI) or a
server-side py-fsrs run; this is an open decision for a later stage. The safeguards are in
place now (`ParameterSafeguards`): custom weights must be 21 finite numbers within
py-fsrs's published bounds, and optimisation is only allowed with at least 512 real
reviews (py-fsrs's own minimum). Nothing pads or synthesises history.

## B. Workload manager

`WorkloadManager.plan(request)` is a pure function that returns a `SessionPlan`. It reads
memory state but never changes it.

### Session size

- Capacity = how many reviews fit in the target session length (default 10 minutes) at the
  learner's pace (median of recent real answer times, default 20 s per review), capped at
  60 reviews. New cards fit in the remaining time up to the hard cap (default 15 minutes).
- The learner can choose a smaller (about half) or larger (+50% of a normal session)
  session for today.

### Missed-day recovery

1. **Recognise the return.** A gap of 3 or more days since the last study day starts a
   recovery with an ease-in ramp. A backlog bigger than one session without a break (for
   example after one missed day) also starts a recovery, but at normal session size.
2. **Ramp.** Gentle pacing (default) gives 60%, 75%, 90%, then 100% of a normal session on
   successive study days. Steady: 80%, 100%. Brisk: 100%.
3. **Pause new cards** while more is due than one session holds; reduce them while ramping.
4. **Prioritise.** Learning steps first. Then any card already left out of sessions on 5 or
   more study days, oldest first (the fairness guarantee). Then the rest by how much recall
   probability the card would lose by waiting one more day, `R(now) - R(now + 1 day)`, which
   favours memories fading fastest rather than ones long forgotten or still strong.
5. **Spread the rest.** Everything not shown stays due in FSRS and eligible for the next
   session; it is listed as deferred with a reason.
6. **Return to normal** once the backlog fits in a session and the ramp is finished.
7. **No hidden debt.** If a normal session is too small to shrink the backlog at all (more
   becomes due each day than fits), the plan sets `needsLargerSessions` so the app can
   suggest a slightly longer session or a lower desired retention.

### Adaptive new cards

New cards are also reduced, then paused, when the 7-day forecast already fills more than
70% of a normal session, because each new card brings several reviews in the following weeks.

### Holiday Mode

- **Full holiday / indefinite pause:** nothing suggested, no targets, no reminders, no new
  cards. Opening the app offers an optional casual-sized session.
- **Casual holiday:** a suggested 2 to 5 minute session, no targets, no new cards.
- Holiday-time deferrals do not count toward the fairness limit. FSRS still sees the real
  elapsed time, and the return uses the recovery ramp.

### Honest accounting

Every plan reports `dueByFsrs`, `plannedNow`, `deferredByWorkload` and `reviewedToday`, so
statistics can always distinguish cards due, presented, reviewed and deferred.

### Verification

`MissedDayRecoveryTest` builds a learner with two months of daily study on the real FSRS
scheduler, then simulates breaks of 1, 3, 7, 14 and 30 days and a return from an
indefinite pause. It checks that no reviews are invented and no memory state changes while
away or while planning, retrievability reflects the real elapsed time, every session stays
within its limits, every due card is either shown or reported as deferred, new cards pause
and later resume, the learner is back to normal within a bounded number of days, and every
review history chain is unbroken. A separate test checks that after a 30-day break with
short sessions, every card that was due on return is eventually shown.

Simulated results with default settings (800-card collection, about 11 to 26 reviews and 5
new cards a day before the break):

| Days away | Due on return | First session | Back to normal after |
| --- | --- | --- | --- |
| 1 | 34 | 30 reviews | 1 study day |
| 3 | 67 | 18 reviews | 4 study days |
| 7 | 102 | 18 reviews | 5 study days |
| 14 | 140 | 18 reviews | 6 study days |
| 30 | 208 | 18 reviews | 10 study days |
