# Harmony: User Research

`[ACTIVE SKILL: design:user-research]`

> **Read this first.** No real research exists for Harmony: no interviews,
> analytics, reviews or store data (see `PRODUCT.md`, "Evidence on Hand").
> The personas below are **proto-personas**. They are built from facts we do
> have:
>
> - the owner's own account of who uses the app and where;
> - the feature set extracted in `01-functional-spec.md`;
> - the friction found while auditing builds 172–173.
>
> Each claim says where it comes from. The test plan in §4 is how we turn
> these assumptions into evidence.

## 1. Who uses Harmony

The audience is the owner and a small circle of friends. They install
signed APKs by hand from a Windows script, they are Romanian speakers who
read English, and they keep their music as files, mostly FLAC and some
hi-res up to 24/96. *(Source: PRODUCT.md, the install guides.)*

That gives a tiny, high-trust and expert-leaning audience, but not a uniform
one. The owner built the app; the friends did not. The redesign has to work
for the friend who was handed an APK, not only for the person who knows what
"SpotiFLAC" means.

### Persona A: The Curator (the owner)

| | |
|---|---|
| **Snapshot** | Owns a large lossless library and grows it every week. Uses every source: SpotiFLAC, Soulseek, YouTube → FLAC and Spotify import. Runs the Windows app on the work PC. |
| **Knows** | Codecs, bit depth, what a transcode is, what a provider is. |
| **Wants** | To trust that a file is what it claims to be (*FLAC check, format card*), and to add music fast without babysitting the downloads. |
| **Frustrated by** | Downloads that stall without saying why; verification loops; having to remember which source works for what. |
| **Signature phrase** *(inferred)* | "Is that actually 24-bit, or did a provider upscale it?" |

### Persona B: The Passenger (the friend with an APK)

| | |
|---|---|
| **Snapshot** | Was given Harmony and has a few hundred songs, a mix of MP3 and FLAC. Uses it mainly as a player: in the car, on a walk, at a desk. Downloads once in a while, usually one song a friend mentioned. |
| **Knows** | How Spotify works. Not what Soulseek is, nor why a "provider" needs verifying. |
| **Wants** | Music to start in one tap and keep going ("play something like this"). To find a song they heard (recognition) and have it in the app. |
| **Frustrated by** | Jargon, settings that sound dangerous, screens that explain the machine instead of the music. |
| **Signature phrase** *(inferred)* | "I just want that song. Where do I tap?" |

### Persona C: The Driver (a context more than a person)

Persona C is either A or B, but **in the car or with only a glance free**:
on Android Auto, or with the phone in a mount or on a desk.

- **Needs:** read what is playing, skip, go back, pause, change the mood,
  all in under 2 seconds and with one thumb.
- **Cannot:** read small text, aim at small targets, or handle a dialog.

*(Source: the owner names "On the move / in the car" as one of the three
dominant situations.)*

## 2. Jobs to be done

These are written as "When … I want to … so I can …". Each one is tied to
the capabilities in `01-functional-spec.md` §3.

| # | Job | Personas | Capabilities |
|---|---|---|---|
| J1 | When I sit down or start driving, I want music to start right away and keep going on its own, so I can stop thinking about it. | A, B, C | 3, 13, 14, 15, Home Smart Mix |
| J2 | When something is playing, I want to see what it is and steer it (skip, queue, more like this, less of this) with a glance, so I stay in control without stopping what I am doing. | A, B, C | 3, 4, 7, 13 |
| J3 | When I hear about a song or album, I want it in my library, in the best quality available, with one decision from me, so I can collect without becoming a sysadmin. | A, B | 23, 24, 26, 28 |
| J4 | When I have a playlist on Spotify, I want it here with the missing songs filled in, so I can leave Spotify without losing my lists. | A, B | 27 |
| J5 | When I listen closely, I want to know the file is genuine and the output is untouched, so I can trust the sound. | A | 5, 6, 11, 18 |
| J6 | When my tastes drift, I want to find new music I will like and try it before I keep it, so my library does not go stale. | A, B | 21, 22 |
| J7 | When I am at my PC, I want the phone and the PC to share one queue and pass files, so I can switch devices without starting over. | A | 31–33 |
| J8 | When the sound is not right (car speakers, cheap earbuds), I want to fix it in seconds, so I can get back to listening. | A, B | 11, 12 |
| J9 | When my library gets messy, I want to fix tags and clear out songs I do not want, so I can keep it tidy and accurate. | A | 17, 19, 20 |

**Priority for the redesign.** J1, J2 and J3 are daily and shared by every
persona. J4–J9 matter, but they are occasional or belong to one persona.

## 3. Usage contexts

| Context | Posture | Attention | Session | Hand | Light | Network | Design implication |
|---|---|---|---|---|---|---|---|
| **Car / on the move** | Phone mounted or in pocket. Android Auto. | Glance, under 2 s | Long listening, very short interactions | One thumb, often the non-dominant hand | Bright sun, or night | Mobile data, patchy | Large type and targets; the essential controls in the thumb zone; no dialogs; the mix keeps going offline or says why it stopped. |
| **At the desk** | Phone flat on the table. PC nearby. | Peripheral | Hours | A reach and one tap | Office light | Wi-Fi | Glanceable status from across the desk; skip and queue in one tap; Connect to the PC is natural here. |
| **Collecting session** | Sitting, phone in both hands | Focused | 10–40 min | Two hands, typing | Any | Wi-Fi | Dense, informative lists are welcome; show progress and the reason a download failed; batch actions; let the user leave a screen while work continues. |
| **Walk / gym** | Phone in pocket, earbuds | Near zero | Long | Lock screen and notification | Bright | Mobile | The notification and lock screen must carry the job; Smart and Online mix must work on mobile data or say they are paused. |

## 4. Usability test plan: top 3 journeys

### 4.1 Study set-up

| Item | Plan |
|---|---|
| **Method** | Moderated task-based usability test, think-aloud, on the participant's own phone (a signed debug build of the redesign). |
| **Participants** | 5–6: the owner (Persona A), plus 4–5 friends who already have Harmony (Persona B). At least 2 of them should not have used the Downloads screen before. |
| **Where** | In person, so the car task can be done in a parked car with the phone in a mount, and the desk task at a table. Calls are acceptable for the collecting tasks. |
| **Length** | 40 minutes: 5 warm-up, 5 context, 25 tasks, 5 wrap-up. |
| **Data** | Screen recording (with consent), time on task, success rated full, partial or fail, errors, SEQ (Single Ease Question, 1–7) after each task, quotes. |
| **Comparison** | Run the same tasks on build 173 first (the baseline) and then on the redesign, ideally 2 weeks apart, swapping the order for half the participants. |
| **Ethics** | Use the participant's own library. Make no purchases. Use test credentials for Spotify where possible. |

### 4.2 Journey 1: Start and steer music (J1 + J2, contexts Car and Desk)

Set-up: the app is closed and the phone is in a car mount.

| Task | Prompt to participant | Success criteria | Measures |
|---|---|---|---|
| 1.1 | "Get some music going, anything you'd like right now." | Audio is playing within 10 s, at most 3 taps, with no typing. | Time to first sound, taps |
| 1.2 | "Skip this song, then go back to it." | Done without looking for more than 2 s at a time. | Glance count and length (from video) |
| 1.3 | "You want the next few songs to be calmer." | Uses the mood or energy control, or "less like this". Understands that the queue changed. | Success, SEQ, can explain what happened |
| 1.4 | "Why is this song playing?" | Finds the pick reason without help. | Success; quote |
| 1.5 *(Vinyl)* | "Show the record, and change the song using just the record." | Opens Vinyl and swipes the record to skip. | Discoverability of the gesture |

### 4.3 Journey 2: Add a song or album to the library (J3, Collecting context)

Set-up: Wi-Fi on; download sources configured as on the owner's phone.

| Task | Prompt | Success criteria | Measures |
|---|---|---|---|
| 2.1 | "A friend recommended *[song]*. Get it onto your phone in the best quality you can." | The song is downloaded and found in the library. Source picked by default or deliberately. | Time, taps, wrong turns, SEQ |
| 2.2 | "Now get the whole album *[album]*." | The album is queued, and the participant can say how far along it is. | Success; reads the progress back correctly |
| 2.3 *(injected failure)* | A provider asks for verification. "Carry on." | Completes the check in the browser, returns, and the download resumes. Or the participant knowingly picks another source. | Success; understanding ("what happened?") |
| 2.4 | "You heard a song in a café. Find out what it is and keep it." | Recognition → result → download in one flow. | Success, time |

### 4.4 Journey 3: Bring a Spotify playlist over (J4, Collecting context)

Set-up: a test Spotify account holding a 30-song playlist, of which 12 songs
are not in the library.

| Task | Prompt | Success criteria | Measures |
|---|---|---|---|
| 3.1 | "Bring your *Road Trip* playlist from Spotify into Harmony." | Connects Spotify, including the Client ID step, and the playlist is listed. | Success, time, where they stalled |
| 3.2 | "Some songs aren't on your phone yet. Get the missing ones." | Understands what is missing, starts the download of the missing songs, and the playlist is created. | Success; can state the counts (have, missing, failed) |
| 3.3 | "Two songs failed. What would you do?" | Finds the reason and a next action (retry or another source). | Success; quote |

### 4.5 Wrap-up questions

- "What, if anything, felt confusing or made you hesitate?"
- "Which words on screen didn't mean anything to you?" Collect the list.
  This list feeds Step 6 (copy).
- "If you could change one thing, what would it be?"

### 4.6 Decision thresholds

| Signal | Threshold that means "redesign again" |
|---|---|
| Task success (full) | Below 80% on any Journey 1 task; below 70% on Journey 2 or 3 tasks |
| SEQ | Median below 5 on any task |
| Glances in the car (1.2) | Any single glance over 2 s, for 2 or more participants |
| Jargon list | The same unclear word named by 2 or more Persona B participants |

## 5. What we already believe, and how sure we are

These are the inputs for Step 3. Confidence is rated as follows:

- **H**: seen directly in the code or in the audit screenshots.
- **M**: the owner said it.
- **L**: inferred.

| Belief | Confidence | Evidence |
|---|---|---|
| Downloads has too many entry points: 4 sources, 2 modes (song and album), recognition, Spotify import, and the YT converter. | H | Capabilities 23–30 all sit in one tab; DownloadsViewModel exposes 50+ actions. |
| Failures are explained in machine terms (provider, verification, extension). | H | Error strings in SpotiFlac*, the Soulseek client, and the build 172/173 audit. |
| Smart Shuffle's controls are powerful but abstract: three sliders plus energy, mood and journey. | H | PlayerViewModel actions; the audit. |
| Pick reasons exist but are hard to find. | H | Build 172 audit; build 173 moved them into the queue. |
| The car is a primary context. | M | Owner. |
| Friends rarely use Soulseek or Connect. | L | Inferred from setup cost (an account, the PC app). |
| Vinyl is loved and is part of the identity. | M | Owner kept it as a hard requirement twice. |
| Nobody uses every EQ mode. Graphic, Winamp, Auto and Clarity overlap in purpose. | L | Four modes for one job. |
