# Regen and health rules

What `RegenWatch` and `HealthTracker` do, exactly, so the firmware port (`src/report/regen_watch.cpp`,
`src/dpf/health.cpp`) can match the phone. The Kotlin in `src/main/kotlin/.../core/RegenWatch.kt` and
`Health.kt` is the reference; `src/test/resources/regen-vectors/*.csv` are test vectors both sides run.

## What the logs showed

From 116 logged sessions up to 2026-11-07 (31 regen-flag episodes; `RealLogReplayTest` reproduces this
when the private logs are present):

- A regen completed **if and only if** `dpf_dist_since_regen_mi` fell to ~0: 15 of 15 completed
  episodes, 0 of 16 others. The reset arrives in the same 2103 reply as the flag going off.
- Soot is a model counter in the ECU (one byte x 100/255). It lags: 4.7 g on the row the regen
  completes in 11 of 15 cases, reaching 0-1.6 g up to 3 s later. "Soot <= 3 g" alone misses those.
- When the flag went off at a standstill without a reset, the engine was at 0 rpm on that same row
  (10 of 10): the key was turned. The ECU does not pause a regen at idle; completed regens kept the
  flag through up to ~100 s of standstill with the engine running.
- After a key-off the ECU starts the regen again on a later drive, sometimes within seconds of the
  restart. Several attempts with no distance reset between them are one regen.
- The flag can go off for a few seconds while driving and come back (once: 13 s at 35 km/h, soot 18 g).
- Five logs simply stop with the flag still on.

## Inputs, once per row (about 1 Hz)

| name | meaning | no reading |
|---|---|---|
| `t_ms` | monotonic milliseconds | - |
| `rpm` | engine speed | NaN |
| `speed_kmh` | road speed | NaN |
| `regen_flag` | 2103 byte 71 == 4 | NaN |
| `soot_g` | 21948001 byte 10 x 100/255 | NaN |
| `dist_since_regen_mi` | 2103 bytes 56-58, metres -> miles | NaN |
| `egt_c`, `dp_hpa` | only used in message text | NaN |

## Constants

| name | value | meaning |
|---|---|---|
| `STREAK_ROWS` | 3 | rows a change must hold: flag on, flag off, engine stopped |
| `ENGINE_RUNNING_RPM` | 500 | below this, or rpm NaN, the engine counts as stopped |
| `RESET_MAX_DIST_MI` | 1.0 | completion: distance below this ... |
| `RESET_DROP_MI` | 0.5 | ... and at least this far below the furthest distance seen in this regen |
| `EMPTY_SOOT_G` | 3.0 | fallback completion: last known soot at or below this |
| `GAP_MS` | 120 000 | no valid flag for this long with a regen open |
| `STILL_KMH` | 3.0 | above this the car is moving |
| `PAUSE_DRIVE_MS` | 180 000 | on hold and moving this long (summed, each row's step capped at 5 s) |
| `PAUSE_MAX_MS` | 1 200 000 | on hold this long in all |
| `TICK_MS` | 30 000 | "still running" ping interval |

## States

`IDLE` (no regen), `RUNNING` (flag on), `HOLD` (flag off or data lost, not yet decided; the code calls
it paused). `active` = RUNNING or HOLD.

Kept while a regen is open: `start_ms`, `last_valid_ms` (last row with a flag reading), `paused_at_ms`,
`moving_ms`, `dist_ref` (furthest distance seen), `reset_seen` (latched), last known soot, peak EGT.

Counters updated on every row that has a flag reading:
`active_streak` / `inactive_streak` (flag > 0.5 or not; one resets the other) and `stopped_streak`
(rows in a row with rpm NaN or < `ENGINE_RUNNING_RPM`). `engine_off` = `stopped_streak >= STREAK_ROWS`.

`reset_seen`: on a row with a distance reading while a regen is open, if `dist_ref` is known and
`dist < RESET_MAX_DIST_MI` and `dist + RESET_DROP_MI < dist_ref` then `reset_seen = true`; otherwise
`dist_ref = max(dist_ref, dist)`.

`done` = `reset_seen` or (soot known and soot <= `EMPTY_SOOT_G`).

## Transitions

Checked in this order on each row; the first that fires ends the row.

1. **Flag is NaN.** If a regen is open and `t - last_valid_ms > GAP_MS`: close it, END if `done` else
   INTERRUPTED ("no data from the engine"). Otherwise nothing. Nothing else is updated on this row.
2. **RUNNING and `t - last_valid_ms > GAP_MS`** (rows stopped arriving, now they are back): go to
   HOLD with `paused_at_ms = last_valid_ms`, clear the streaks, emit PAUSED. The following rows decide.
3. Update `last_valid_ms`, soot, peak EGT, the streaks and `reset_seen`.
4. **HOLD:**
   1. `active_streak >= STREAK_ROWS` -> RUNNING, emit RESUMED.
   2. flag off on this row and `done` -> IDLE, emit END.
   3. flag off on this row and `engine_off` -> IDLE, emit INTERRUPTED ("engine switched off").
   4. if `speed > STILL_KMH` add this row's time step (0-5000 ms) to `moving_ms`; if
      `moving_ms >= PAUSE_DRIVE_MS` or `t - paused_at_ms >= PAUSE_MAX_MS` -> IDLE, emit END if `done`
      else INTERRUPTED ("it did not start again").
   5. otherwise nothing (no TICK while on hold).
5. **IDLE and `active_streak >= STREAK_ROWS`** -> RUNNING, emit START. `dist_ref` = this row's
   distance, `reset_seen = false`, soot-at-start = last known soot.
6. **RUNNING and `inactive_streak >= STREAK_ROWS`** (the flag has dropped):
   1. `done` -> IDLE, emit END.
   2. `engine_off` -> IDLE, emit INTERRUPTED ("engine switched off").
   3. otherwise -> HOLD with `paused_at_ms = t`, emit PAUSED.
7. **RUNNING and `t - last_tick_ms >= TICK_MS`** -> emit TICK.

**`finish(t)`**, called when logging stops: if a regen is open, IDLE and emit END if `done`, else
INTERRUPTED ("logging stopped while it was running"). Otherwise nothing.

Durations in messages run from START to the flag drop (`paused_at_ms` when on hold), not to when the
decision was made.

`update()` without an `rpm` argument (`rpm = null` in Kotlin) keeps the previous rule for old callers:
at step 6, `reset_seen` -> END; standing (`speed <= STILL_KMH`) with soot above `EMPTY_SOOT_G` -> HOLD;
anything else -> END; steps 1, 4.2 and 4.3 do not apply. New code should always pass rpm.

## Messages

`text` = headline + `\n` + detail. `notify` tells the UI whether to alert the driver.

| event | notify | headline |
|---|---|---|
| START | yes | The engine is cleaning its exhaust filter. If you can, keep driving for about 10 more minutes. If you've arrived, it's fine to switch off. |
| END | yes | Filter cleaning finished. Nothing to do. |
| INTERRUPTED | yes | Filter cleaning was cut short. It will try again on the next drive. |
| PAUSED | no | Filter cleaning is on hold. |
| RESUMED | no | Filter cleaning carried on. |
| TICK | no | Filter cleaning in progress. |

Detail lines carry soot, EGT, minutes, DPF pressure and the clock time in the user's units.
No message promises that a held regen will carry on.

## Health

- **One regen per cycle.** A START is a *retry* when it belongs to the same cycle as the last history
  row: `|(odometer - dist_since_regen) - (row.odometer - row.miles_since_prev)| < SAME_CYCLE_MI` (2.0).
  If either odometer or distance is missing, the persisted `cycleHadStart` flag decides (set by any
  START, cleared when a distance reset is confirmed). A retry adds 1 to the row's `attempts`, clears
  its `interrupted` mark and moves its time to now; it does **not** add a history row, count towards
  `regensSinceOil`, or teach the trigger level or soot rate. INTERRUPTED sets `interrupted` on the last
  row; END clears it.
- **Saved state.** `regens=` rows are `odometer,milesSincePrev,interrupted,wallClock,attempts`; the
  fifth field is optional when reading (absent = 1), so earlier blobs load unchanged. `ver` stays 1.
- **Warm-up time** is engine-running time from a cold start to `WARMUP_DONE_C` (80 C):
  it starts only if the *first* coolant reading of the session is below `WARMUP_COLD_C` (40 C);
  each row adds the time since the previous row only if `rpm >= WARMUP_RUNNING_RPM` (500);
  if two rows are more than `WARMUP_GAP_MS` (120 000) apart, or coolant has been missing that long,
  the sample is dropped and no warm-up is recorded for that session.

## Test vectors

`src/test/resources/regen-vectors/*.csv`, cut from real episodes. Odometer, clock time, position and
every other column are removed; time starts at 0 and distance-since-regen is shifted to start at
2.000 mi (a reset to ~0 is left as logged).

Format: plain ASCII, `\n` line ends, a header line, then one line per row, always 9 comma-separated
fields, no quoting:

```
t_ms,rpm,speed_kmh,regen_flag,soot_g,dist_since_regen_mi,egt_c,dp_hpa,expected
```

- An empty field is "no reading" (NaN).
- `expected` is the event the row must produce: `START`, `END`, `INTERRUPTED`, `PAUSED`, `RESUMED`,
  or empty for none. TICK events are not listed; ignore them when comparing.
- The last line has `finish` in the first field and the other inputs empty: call `finish()` with the
  previous row's time; `expected` is what it must return (empty = nothing).

In C: `fgets` a line, check for `finish`, otherwise walk the 8 commas with `strchr`; an empty field
(next char is `,` or end of line) is NaN, else `strtod`.

| file | from | shows |
|---|---|---|
| `01_clean_completion` | a 15 min regen on the move | START, then END three rows after the flag drops: the distance reset is on the first of them (soot still 4.7 g), soot follows by the third |
| `02_keyoff_interrupt` | engine switched off 4 min in | INTERRUPTED 3 rows after the flag drops |
| `03_standstill_engine_running_completes` | regen finishing while stopped, engine idling | END, not a hold |
| `04_reattempt_same_cycle` | key-off after 30 s, restart 6 min later, flag blink, key-off again | two attempts, one regen for health |
| `05_flag_blink_while_driving` | flag off for 13 s at 35 km/h, soot 18 g | PAUSED then RESUMED, no END; `finish()` closes it |
| `06_log_ends_mid_regen` | log stops with the flag on | `finish()` returns INTERRUPTED |
