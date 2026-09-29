# ARISE — the SYSTEM （システム）

A Solo Leveling–style **System** for your real body. It issues a Daily Quest every day, tracks it
by itself, speaks to you in an echoing ghost voice, punishes you with the Penalty Zone when you
skip, and ranks you from **E** to **National Level** based on your real weight, height, age and
strength — with a small AI running **on your phone** that reads your stats *in your own words*
and writes your quests.

It is a separate Android app (`dev.aoidoki.arise`, launcher name **SYSTEM**). It has nothing to do
with Mayonaka/Termux in the rest of this repository and does not touch it.

```
   void     #05070D     system blue  #1EA7FF     cyan  #6FE3FF
   penalty  #FF2A3D     monarch      #8B5CF6     gold  #FBBF24
```

---

## Install

1. Get the APK:
   - **Releases** → `SYSTEM (ARISE) vX.Y.Z` → `SYSTEM-arise-vX.Y.Z.apk`, or
   - **Actions** → `arise` workflow → latest run → artifact `arise-apk`.
2. Open it on the phone and allow installing from your browser/file manager when Android asks.
3. Launch **SYSTEM**. *Accept.*

Built for an arm64 phone on Android 10+ (tuned for a Galaxy S25 Ultra). Every build is signed with
the committed `arise.jks`, so new versions install over old ones without losing progress.

## First launch — the Awakening

1. **The notification.** *You have acquired the qualifications to be a Player. Will you accept?*
2. **Registration.** Name, age, height, weight, goal weight (kg/cm or lb/ft). Goals below a healthy
   BMI (18.5) are raised to it.
3. **Tell the System who you are.** A free-text box and an unlimited list of stats **you name
   yourself** — `max push-ups: 22`, `plank: 1:30`, `5k: 34 min`, `left knee: old ACL tear`,
   `sleep: 6h`. No dropdowns, no presets. The System reads them (and the AI reads them better).
4. **Permissions.** Physical activity (steps), notifications, Health Connect (Samsung Health,
   Galaxy Watch, smart scales), and "never sleep" (battery optimisation exemption).
5. **The Core.** Download the on-device AI now (Wi-Fi, background) or later.
6. **Arise.** Your first Daily Quest arrives immediately.

Then do the **Assessment** on the Train tab: max push-ups, squats in 2 minutes, sit-ups in 1
minute, max plank — all counted by the tracker. It calibrates every quest after it.

## The game

| | |
|---|---|
| **Stats** | STR, AGI, VIT, SEN, INT — seeded from your measured numbers and your own words. +3 ability points per level (allocate them on the Status window), +2 every 7-day streak. |
| **HP** | 100 + 10×VIT + 5/level. Failing quests costs HP. Clearing a quest (or levelling up) fully restores it. **HP 0 = you die**: reborn one level lower, streak gone. |
| **MP** | 30 + 8×INT. Earned by meditation. Spend 30 MP to **reroll** today's quest. |
| **Fatigue** | Rises with work, falls with sleep (read from Health Connect). At 70+ the System issues a *Recovery Protocol* instead. |
| **Level** | `80 + 70×level` XP to the next. Every daily quest completed ≈ level 12 in a month, 30 in six months, 50 in a year. Weight milestones (every kg lost) pay 120 XP each. |
| **Titles** | Earned, never picked: *Wolf Slayer* (7-day streak), *The One Who Overcame Adversity* (survived a penalty), *The First Step*, *Iron Body* (−5 kg), *Demon Hunter* (goal reached), *Relentless*, *Reborn*, *Shadow Monarch*. |
| **Job** | None → Hunter (B) → Necromancer (A/S) → **Shadow Monarch** (National Level). |

### The Daily Quest

*Preparations to Become Powerful* — steps, push-ups, sit-ups, squats, and from D-rank on plank and
brisk walking. Targets come from your baselines, your rank and last week's completion rate, and
are clamped by the safety rails (below). Everything is tracked automatically:

| Objective | How it's tracked |
|---|---|
| Steps, distance | The phone's hardware step counter, all day, in a foreground service — merged with Health Connect so Galaxy Watch steps count too |
| Brisk minutes | Minutes above 100 steps/min |
| Push-ups, squats, sit-ups | **Camera**: prop the phone up, ML Kit Pose finds your body, joint angles count the reps (elbow / knee / hip), skeleton drawn in System blue. **Or sensors**: push-ups with the phone under your chest (proximity), squats with it in your pocket (accelerometer), sit-ups holding it to your chest (tilt) |
| Plank, meditation | A timer that only runs while you're still (and, on camera, while your body is straight) |
| Sleep | Health Connect sleep sessions |

"Log by hand" exists for when tracking can't work — it counts, at half XP.

### The Penalty Zone

Midnight settles the day. Incomplete means:

- HP loss in proportion to what you left undone, XP loss (never below your current level), streak reset.
- **Penalty Quest: Survival** — walk an extra 2,500–6,000 steps inside a 6-hour window. The window
  never opens before 07:00 (a late failure opens it the next morning), only steps taken inside it
  count, and the whole app turns red while it's open. The notification can't be swiped away.
- Fail the penalty: another HP hit and the **Weakened** debuff (−25% XP for 3 days).

Days are settled **exactly once, in order, whenever the System next runs** (on open, every 15 min in
the background, at boot and at the midnight alarm). A killed app or a dead phone delays a penalty;
it never skips or doubles it. A long absence costs a single penalty, not one per day.

**Recovery day** (4 per month): sick, injured, resting. Today's quest isn't penalised and any open
Penalty Zone moves to tomorrow.

### Ranks

**Hunter Power** (0–100) is four equal parts, all age-adjusted:

- **Body** — BMI distance from the healthy range + progress to your goal
- **Cardio** — 7-day average steps
- **Strength** — your measured reps against norms
- **Discipline** — streak + 14-day completion

| Rank | Power | Level |
|---|---|---|
| E | — | — |
| D | 20 | 5 |
| C | 35 | 12 |
| B | 50 | 20 |
| A | 65 | 30 |
| S | 80 | 40 |
| National Level | 92 | 50 |

Qualifying unlocks a **Rank-Up Trial** — a one-day, heavier quest (written by the AI when it's
installed). Pass it and you're promoted. Fail it and you may retry in 3 days.

## The System's voice

Android's own text-to-speech (it picks a female voice when one is installed), rendered to audio
and run through a custom effects chain in pure Kotlin:

1. a pitched-down **shadow voice** under the main one (−4 semitones, same timing)
2. **chorus** shimmer
3. **multi-tap echo** (170 / 340 ms)
4. **Freeverb** reverb with a dark low-passed tail

…preceded by a synthesized two-note System chime. Echo, reverb, ghost layer, pitch, speed and the
voice itself are all adjustable in Settings. For the best voice, install **Speech Services by
Google** and download an English (US) voice.

It speaks when quests arrive, at 25/50/75/100%, every 10 reps, on level-ups, rank-ups, titles, at
the 21:00 warning, when the Penalty Zone opens, and when you die.

## The Core (on-device AI)

A small language model running entirely on the phone with Google's MediaPipe LLM runtime. Nothing is
sent anywhere.

| | Model | Size |
|---|---|---|
| **Standard** | Qwen2.5-1.5B-Instruct (q8, 4k context) | 1.6 GB |
| **Lite** | Qwen2.5-0.5B-Instruct (q8) | 547 MB |

Both are downloaded from Hugging Face (`litert-community`, no account needed) with Android's
download manager (resumable, Wi-Fi-only by default) and verified against their published SHA-256.
You can also import any MediaPipe `.task` file.

What it does:

- **Reads you.** Your free text and self-named stats → starting stats, care flags (knee, back,
  shoulders, heart), baseline estimates and the System's written assessment of you.
- **Writes your Daily Quest** around your own words and last week, every morning in the background.
- **Designs your Rank-Up Trials** and writes your **evaluation** on the Rank tab.
- **Rewrites** a quest when you spend MP on a reroll.

The model is never trusted. Its output is dug out of whatever it says, parsed leniently, and then
every number goes through the same safety rails as the rules engine. Anything missing, malformed
or unsafe falls back to the rules. **The app is fully playable without the model.**

## Safety rails

The System is harsh in tone, never in substance. `engine/SafetyLimits.kt` applies to every quest,
whoever wrote it:

- Loads rise at most ~10% per week per exercise; hard caps scale with age and BMI.
- Injuries and conditions you mention are honoured: bad back → no sit-ups; wrists/shoulders → no
  push-ups; knees → half the squats, no jumping; heart/asthma/pregnancy → steps capped at 8,000
  and brisk work at 30 min.
- **Nothing is ever about eating less.** Fasting, skipping meals, calorie targets, purging,
  dehydration — any AI text mentioning them is discarded. Penalties are always movement.
- Goals below BMI 18.5 are refused; the goal pace shown is 0.5–1% of body weight per week.
- A one-time notice says the obvious: it's a game, not a doctor.

## Samsung / Galaxy notes

- **Settings → Battery → Background usage limits → Never sleeping apps → add SYSTEM**, or One UI
  will eventually kill the step tracker.
- **Samsung Health → Settings → Health Connect** → allow sync, so watch steps, sleep and scale
  weigh-ins flow in.

## Five-minute check on the phone

These can only be proven on real hardware:

1. **Voice** — Settings → *Test voice*. You should hear the chime and an echoing female voice.
2. **Steps** — walk 100 steps; the ongoing notification and the Quest tab should move.
3. **Camera reps** — Train → Push-ups → Camera → *Start*; do 5. A blue skeleton should track you.
4. **Sensor reps** — Train → Squats → Sensor, phone in pocket; do 5.
5. **AI** — after the core downloads, Quest → *Reroll*; the quest should come back tagged
   "Written by the core" within a minute.

## Building

```sh
cd arise
./gradlew recordRoborazziDebug   # unit tests + renders every screen to app/src/test/screenshots
./gradlew assembleRelease        # signed APK in app/build/outputs/apk/release
./gradlew connectedDebugAndroidTest   # smoke test on a device/emulator
```

CI (`.github/workflows/arise.yml`) runs the unit tests, lint and the build, verifies the
signature and manifest, runs the smoke test on an API 35 emulator, and — run it manually with
*publish* ticked — publishes a pre-release with the APK.

| Path | What |
|---|---|
| `engine/` | The rules: `Game` (the one place state changes), progression, ranks, penalties, safety rails, quest planner, profile parser |
| `sense/` | Step tracker service, Health Connect, rep counters, camera pose + sensor workout controller |
| `voice/` | TTS → GhostFx → AudioTrack, the chime, WAV I/O |
| `ai/` | Model download/verify, MediaPipe LLM engine, prompts, lenient JSON, the AI director |
| `work/` | Midnight/evening/morning alarms, 15-minute sync, boot, notifications |
| `ui/` | The System windows, every screen |

Fonts: Rajdhani and Exo 2, both SIL Open Font License (`licenses/`).
