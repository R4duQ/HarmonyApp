# Harmony: UX Copy Deck

`[ACTIVE SKILL: design:ux-copy]`

**Voice:** plain, warm and literal, like a friend who knows audio and doesn't
show off. Every message says **what happened**, then **what Harmony already
tried**, then gives **one next action** (P4). Technical words appear only one
tap deep (P1).

All strings move to `strings.xml`, so that a Romanian translation becomes
possible (F13). Romanian equivalents are given for the navigation and for
the core actions, as a guide for the translator.

---

## 1. Glossary: one word per thing

| Use | Never on the default path | RO |
|---|---|---|
| **Get** (a song/album) | Download, fetch, grab | Descarcă (critique U7) |
| **Add** (destination) | Downloads, Acquire | Adaugă |
| **Your library** | Local files, MediaStore | Biblioteca ta |
| **Mix** | Smart Shuffle, smart queue, bandit | Mix |
| **Online mix** / "Keep going online" | YouTube Music radio, InnerTube | Continuă online |
| **Keep** (an online song) | Save to library, persist | Păstrează |
| **Source** (in source preferences) | Provider, engine, extension, backend | Sursă |
| **Quick check in your browser** | Verification, challenge, captcha, session grant | Verificare rapidă în browser |
| **Lossless** / **Hi-Res 24-bit / 96 kHz** | FLAC 24/96 (shown as a fact in details only) | Fără pierderi / Hi-Res |
| **Untouched audio** | Bit-perfect | Sunet neatins |
| **Volume levelling** | ReplayGain | Egalizare volum |
| **Sound** | Equalizer, DSP | Sunet |
| **Your computer** | PC, Harmony Connect, desktop client | Calculatorul tău |
| **Activity** | Workers, queue, status center | Activitate |
| **Identify** | Recognize, Shazam | Identifică |
| **Tidy up** | Library maintenance, review deletions | Fă ordine |

## 2. Navigation and titles

| Element | EN | RO |
|---|---|---|
| Bottom bar | Listen · Library · Add | Ascultă · Bibliotecă · Adaugă |
| Top bar actions (a11y) | Activity · Sound · Settings | Activitate · Sunet · Setări |
| Now Playing close | Close player | Închide playerul |
| Library segments | Songs · Albums · Artists · Playlists | Melodii · Albume · Artiști · Playlisturi |
| Add doors | Identify a song · From Spotify · Discover new music · From your computer | Identifică o melodie · Din Spotify · Descoperă muzică · De pe calculator |
| Sound segments | Simple · Graphic · Winamp · Clarity Live | Simplu · Grafic · Winamp · Clarity Live |

## 3. Core CTAs

| Where | Copy | Notes |
|---|---|---|
| Listen, Resume card | **Pick up where you left off** / [Resume] | |
| Listen | [Mix my library] [Shuffle all] | |
| Mood chips | Calm · Focus · Energy · Happy · Late night · Party · More moods | Map to CALM, FOCUS, WORKOUT, HAPPY, DARK, PARTY; RELAXING, SAD, EPIC, EMOTIONAL and AGGRESSIVE go under "More moods" as Relaxed, Melancholy, Epic, Emotional and Intense |
| Now Playing, steering | Calmer · Livelier · Mood · Tune… | Snackbars: "Calmer from the next song." / "Livelier from the next song." |
| Now Playing, no mix | Start a mix from this song | |
| Mood sheet | Ease into **Calm** over the next 5 songs | Journey |
| Tune sheet | Mix it up · Old favourites · Something new · Smooth flow | Styles BALANCED, FAMILIAR, DISCOVER, FLOW |
| Tune sheet sliders | Familiar ↔ New · Similar ↔ Varied · Energy (Any) | |
| Tune sheet switch | Keep going online when my songs run out | Sub: "Finds similar songs on YouTube Music. You can keep the ones you like." |
| Queue | Up next · You added · From the mix · Online · [Keep] · [Keep all] · [New mix] · [Stop mix] | |
| Add search placeholder | Song, album, or paste a link | |
| Add result | [Get] · "In your library ✓" · "Lossless" · "Hi-Res 24-bit / 96 kHz" | |
| Add result, other ways | Not what you want? Try Soulseek · YouTube · a direct link | |
| Get progress | Getting… 34% · Getting song 4 of 9 | |
| Get success | **Added to your library** [Play] | |
| Spotify match CTA | [Get 12 songs and create playlist] | Pluralised: "Get 1 song and create playlist" |
| Spotify, nothing missing | [Create playlist] · "You already have every song." | |
| Spotify done | **Road Trip is ready**: 38 of 40 songs. [Play] [Open playlist] | |
| Identify | [Listen] · "Listening… hold your phone near the music" · [Stop] | |
| Identify match | [Play] (owned) · [Get it] (not owned) · "Not right? Listen again" | |
| Taste round | [Not for me] · [Undo] · [Like it] | Never icon-only |
| Round end | **You liked 7.** [Make a playlist] [Another round] | |
| Discover build steps | Step 1 of 3 · What you like / Step 2 of 3 · Songs / Step 3 of 3 · Name and save | |
| Discover save | Also get the songs I don't have · [Create playlist] | |
| Connect pairing | Enter the 4-digit code shown on your computer | |
| Transfer offer | **Desk PC wants to send 12 songs** (1.4 GB). [Accept] [Not now] | |
| Tag editor | [Save] · "Saved in Harmony; the file itself isn't changed." | |
| Tidy up | [Remove 23 songs] · [Decide later] | |

## 4. Confirmations (destructive actions only)

| Action | Title | Body | Buttons |
|---|---|---|---|
| Delete one song | Delete "So What" from your phone? | The file is removed from your phone. This can't be undone. | [Delete] (wine) · [Keep] |
| Delete album | Delete "Kind of Blue" (9 songs)? | The files are removed from your phone. This can't be undone. | [Delete 9 songs] · [Keep] |
| Delete playlist | Delete the playlist "Road Trip"? | Only the playlist goes; your songs stay in your library. | [Delete playlist] · [Keep] |
| Tidy up | Remove 23 songs from your phone? | Android will ask you to confirm once more. | [Remove 23 songs] · [Go back] |
| Stop album | Stop getting "Kind of Blue"? | The 4 songs already added stay in your library. | [Stop] · [Keep going] |
| Forget computer | Forget "Desk PC"? | You'll need the 4-digit code to pair again. | [Forget] · [Keep] |
| Disconnect Spotify | Disconnect Spotify? | Playlists you've already brought over stay. | [Disconnect] · [Keep connected] |

Removing a song from the queue or from a playlist is **not** confirmed: an
**Undo** snackbar follows instead ("Removed from Up next. [Undo]").

## 5. Empty states

Each one gives what this is, why it is empty, and how to start.

| Screen | Title | Body | Action |
|---|---|---|---|
| Listen, no permission | Let Harmony see your music | Harmony plays the music files on your phone. Allow access and they'll appear here. | [Allow access] |
| Listen, empty library | Your library is empty | Add songs from your computer, or get them in Add. | [Add music] |
| Library, songs | No songs yet | Music you add shows up here, sorted A to Z. | [Add music] |
| Library, playlists | No playlists yet | Make one from any songs, or bring one over from Spotify. | [New playlist] · [From Spotify] |
| Library search, no results | Nothing called "…" in your library | Want to look for it online? | [Search in Add] |
| Playlist, empty | This playlist is empty | Add songs from your library. | [Add songs] |
| Up next, empty | Nothing up next | Harmony stops after this song. | [Start a mix from this song] |
| Activity, idle | Nothing running | Songs you get and files from your computer show their progress here. | — |
| Add, recent | Nothing added yet | Search above, or try one of the four ways in. | — |
| Discover shelf | Couldn't load this shelf | Check your connection. | [Try again] |
| Moods while analysing | Moods are almost ready | Harmony is listening to your songs to learn their mood: 42% done. | — |

## 6. Permission primers (shown before the system prompt)

| Permission | When | Primer |
|---|---|---|
| Music & audio | First Listen or Library visit | **Let Harmony see your music.** It only reads your music files, to play them. [Allow access] |
| Microphone | First tap on [Listen] in Identify | **Harmony needs to hear the song.** It listens for a few seconds, only while this screen is open. [Continue] |
| Notifications | The first mix, Get, or transfer | **Want to know when it's done?** Harmony shows progress and playback controls in your notifications. [Allow] · [Not now] |
| Delete (system consent) | Delete or Tidy up | — (the confirmation above comes first) |
| Folder picker | Source preferences › Download folder | **Choose where new songs go.** Harmony will save everything you get into that folder. [Choose folder] |

**When a permission is denied**, the screen says what can't be done and
offers [Open settings]. For example: "Identify needs the microphone. You can
turn it on in Settings."

## 7. Errors: API and engine → copy

Every error has three parts. The *Details* link reveals the raw engine
message, for the Curator (P1). The **Source** column cites where the error
comes from in the code.

### 7.1 Getting songs (SpotiFLAC / Mornye: `SpotiFlacFailureKind`)

| Source | What happened | What Harmony tried | Action |
|---|---|---|---|
| `NOT_FOUND` | Couldn't find "So What" in lossless. | Looked on Tidal, Qobuz, Deezer and Amazon. | [Try another way] → Soulseek/YouTube, pre-filled |
| `VERIFICATION` | Tidal wants a quick check in your browser. | Your download is paused and will continue by itself. | [Open browser] |
| Verification, came back unfinished | The check in your browser wasn't finished. | Your download is still paused. | [Open browser again] |
| Verification, browser failed | Couldn't open your browser. | — | [Copy link] (paste it in any browser) |
| `RATE_LIMITED` | Tidal asked Harmony to slow down. | Harmony will try again in 2 minutes. | [Try another source now] |
| `TIMEOUT` | The source stopped answering. | Tried twice. | [Try again] |
| `NO_NETWORK` | You're offline. | Harmony will continue when you're back online. | — (auto) |
| `INTEGRITY` | The file arrived damaged. | Deleted the broken copy. | [Get it again] |
| `LOCAL`, disk full | Your phone is out of space. | Nothing was saved. | [Open storage settings] |
| `LOCAL`, folder lost | Harmony can't write to your download folder any more. | — | [Choose folder] |
| `LOCAL`, conversion | Couldn't convert the file. | — | [Try another way] |
| `OTHER` | Something went wrong getting "So What". | — | [Try again] · [Details] |
| Lossless unavailable, lossy fallback | Only a lossy copy is available (320 kbps). | No lossless source had it. | [Get it anyway] · [Try another way] |
| Duplicate | You already have "So What". | — | [Play it] · [Get it anyway] |

### 7.2 Album queue (`AlbumDownloadViewModel` / `AlbumQueueViewModel`)

| Situation | Copy | Action |
|---|---|---|
| A track failed | Song 5 of 9 couldn't be found. The other songs keep coming. | [Retry] · [Skip] |
| Paused | Paused: 4 of 9 songs added. | [Resume] |
| Waiting | Waiting for Wi-Fi: 4 of 9 songs added. | [Use mobile data] |
| Album not found | Couldn't find this album in lossless. Looked on Tidal, Qobuz, Deezer and Amazon. | [Search again] · [Try Soulseek] |

### 7.3 Soulseek (`SoulseekClient`)

| Source | Copy | Action |
|---|---|---|
| `INVALIDPASS` | Soulseek didn't accept that password for **user**. | [Try again] |
| `INVALIDUSERNAME` (+ reason) | Soulseek didn't accept the username **user**: *reason*. | [Change username] |
| Server unreachable | Can't reach Soulseek right now. Tried for 20 seconds. | [Try again] |
| No results | Nobody on Soulseek is sharing "…" right now. | [Search YouTube] · [Try later] |
| Queued by peer | You're number 12 in **user**'s queue. Harmony will wait and get it when it's your turn. | [Find another copy] |
| Peer went offline | **user** went offline mid-song. Kept what arrived. | [Find another copy] |
| No free slots ("free slots only" on) | Everyone sharing this is busy. | [Include busy users] |

### 7.4 YouTube → FLAC (yt-dlp + FFmpeg)

| Situation | Copy | Action |
|---|---|---|
| Not a song link | That link isn't a YouTube video or song. | [Paste another link] |
| Title check | Is this the right one? "Miles Davis – So What (Official Audio)" | [Convert to FLAC] · [Cancel] |
| Extraction failed | YouTube didn't let Harmony read this video. Tried twice. | [Try again] · [Details] |
| Conversion failed | Couldn't convert the audio. | [Try again] |
| Quality note (always shown before converting) | YouTube audio is lossy. FLAC keeps it exactly as is, but it won't add detail. | — |

### 7.5 Spotify (`SpotifyPlaylistClient`, OAuth)

| Situation | Copy | Action |
|---|---|---|
| No Client ID | Paste your Client ID to connect. Or paste a playlist link: that needs no account. | [How do I get one?] |
| Invalid Client ID | Spotify doesn't recognise that Client ID. Check it was copied fully. | [Paste again] |
| Redirect not registered | Spotify needs Harmony's address added to your app: `com.harmony.app://spotify-playlist-callback` | [Copy address] · [Open Spotify dashboard] |
| Browser didn't open | Couldn't open your browser. | [Copy sign-in link] |
| User cancelled | You didn't finish connecting to Spotify. | [Connect] |
| Token expired, refresh failed | Your Spotify connection expired. | [Connect again] |
| Private or unavailable playlist | Spotify won't share this playlist. It may be private or deleted. | [Connect your account] (for private) |
| Rate limited (429) | Spotify asked Harmony to wait a moment. Trying again in 30 seconds. | — (auto) |
| Some tracks not matched | 2 songs couldn't be found anywhere. They're listed below. | [Retry] |

### 7.6 Identify (`RecognitionOutcome`)

| Source | Copy | Action |
|---|---|---|
| `NoMatch` | Couldn't recognise this one. Listened for 12 seconds. | [Listen again] |
| `Failed(retryable)` | Lost the connection while checking. | [Listen again] |
| `Failed` | Identify isn't working right now. | [Try later] |
| Too quiet | Couldn't hear enough. Move closer to the music. | [Listen again] |
| Offline | Identify needs the internet. | [Try again] |
| No microphone permission | Identify needs the microphone. | [Open settings] |

### 7.7 Mix and online mix

| Situation | Copy (queue header and snackbar) | Action |
|---|---|---|
| Offline | Online mix paused: you're offline. Playing your own songs. | — |
| Mobile data not allowed | Online mix paused: you're on mobile data. | [Allow once] · [Always allow] |
| Out of songs | Ran out of similar songs. Playing from your library. | [New mix] |
| YouTube Music lookup failed | Couldn't find similar songs online. Tried 3 times. | [Try again] |
| Keep failed | Couldn't keep "So What". Looked for a lossless copy. | [Try another way] |
| Analysis not ready | Mixes get better once your songs are analysed (42%). | — |

### 7.8 Playback and output

| Situation | Copy | Action |
|---|---|---|
| Output lost | Headphones disconnected, so Harmony paused. | [Play on phone] |
| Unsupported file | Harmony can't play this file (DSD). Skipped to the next song. | [Details] |
| File missing | "So What" isn't on your phone any more. Skipped it. | [Rescan library] |
| PC stream dropped | Lost the connection to Desk PC. Paused. | [Reconnect] · [Play on phone] |
| USB can't do untouched | This DAC can't take untouched audio at 24/96. Harmony resamples it to 48 kHz. | — |

### 7.9 Your computer (Connect)

| Situation | Copy | Action |
|---|---|---|
| Not found | Can't find your computer. Check that both are on the same Wi-Fi and Harmony is open on the computer. | [Search again] |
| Wrong code | That code didn't match. Check the code on your computer. | [Try again] |
| Old version | Desk PC needs a newer Harmony (version 2 or later). | — |
| Went away | Desk PC disconnected. | [Reconnect] |
| Transfer refused | You declined the files from Desk PC. | — |
| Transfer damaged | 2 files arrived damaged and were removed. | [Ask again] |
| Transfer interrupted | Transfer stopped at 8 of 12 songs. It will continue when Desk PC is back. | — |

### 7.10 Library

| Situation | Copy | Action |
|---|---|---|
| Scan running | Finding your music… 1,240 songs so far. | — |
| Delete consent refused | Nothing was deleted. | — |
| Rescan done | Library up to date: 12 new songs, 3 removed. | — |

## 8. Status words (Activity and rows)

| State | Copy | Icon / colour |
|---|---|---|
| PENDING | Waiting | clock / muted |
| RUNNING | Getting… 34% | progress / amber |
| PAUSED | Paused | pause / muted |
| WAITING_FOR_VERIFICATION | Needs a quick check | open-in-browser / slate |
| SUCCESSFUL / DOWNLOADED | Added | check / moss |
| IN_LIBRARY | In your library | check / moss |
| FAILED | Couldn't get it | alert / wine |
| Analysis | Analysing your songs: 42% · paused until charging | — |

## 9. Localisation notes

- Romanian runs about 20–30% longer than English. Buttons must wrap to two
  lines, never truncate. Chips may scroll horizontally.
- Plurals use `<plurals>`, which Romanian needs (one / few / other): "1
  melodie", "2 melodii", "20 de melodii".
- Song, album and artist names are never translated, and are wrapped in
  `“…”` in RO strings.
- Keep "Winamp", "Clarity Live", "Soulseek", "Spotify", "YouTube", "Tidal",
  "Qobuz", "Deezer" and "Amazon" untranslated.
- Do not abbreviate units (kHz, kbps, GB). Numbers use locale formatting
  (`1.240` in RO).
