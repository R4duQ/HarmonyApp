# Discover albums — Harmony 1.4.5

Discover previously led with local track rows and general browser links. It now
centers on a native, horizontally swipeable album deck: a familiar song is the
entry point, and the record as a whole is the recommendation.

## Interaction

- Swipe left for the next album and right for the previous one. Previous/next
  buttons expose the same navigation. No swipe saves, deletes or downloads.
- Switch between **For you**, **All albums**, and **Saved**. Genre filters apply
  within each shelf. **New order** produces a fresh seeded order.
- A bookmark saves an album locally. **I know it** records explicit familiarity
  when there is no local song. **Heard the album** removes it from For you with
  a snackbar Undo; All albums and Saved still retain it.
- **Read on The Shfl** opens the verified source page in a Custom Tab, with an
  external-browser fallback. **Find locally** opens Harmony's search with the
  album title already entered.
- **Play** is available only for matched local files. Album playback names the
  actual local track count and orders the queue by disc and track. It never
  advertises an unavailable album as playable.

The visual design uses large covers, dark record sleeves, album-specific accent
colors, a song recognition panel and short listening notes. The surrounding
chrome retains Harmony's coral palette, including its dark mode. Vertical
scrolling remains available around the horizontal pager; long text is not put
inside a fixed-height card. Failed cover loads show a vinyl placeholder and
an explicit retry action. The screen is constrained in width on large displays.

## Recommendation behavior

The initial deck is a finite selection of 24 familiar albums with verified
public pages on The Shfl. It is not a live feed or an official API integration.
Titles, artists, years and source/cover URLs were checked on 2026-09-05.
Harmony supplies its own short listening notes; source reviews are not copied.
The info dialog and source footer identify this distinction.

Artist and album tags are compared locally. Artist aliases, punctuation,
diacritics and recognized remaster/deluxe suffixes are supported; fuzzy artist
substring matches and alternate live/remix/instrumental recordings are not
treated as studio entry tracks. A familiar single from a compilation can rank
its original album highly, but it is not added to that album's local queue.
Local library membership is labelled as such, not treated as proof of listening.

For you prioritizes a familiar entry track or a few local album tracks, followed
by explicit familiarity and other music by a known artist. Albums with many
local tracks receive a lower priority. This is a discovery heuristic, not an
assertion that an album is complete. Only the user's **Heard the album** action
marks an album as listened.

Library matching runs off the main thread. The deck order stays deterministic
for a chosen seed; saving alone does not reorder it. The shelf, filter, seed
and current album are retained in SavedStateHandle. Saved/familiar/listened
IDs are persisted separately in SharedPreferences with serialized mutations.
These do not modify playlists, favorites, music files or Spotify settings.

## Source catalogue

These are exact observed album URLs; slugs are not inferred from titles.

| Artist | Album / public source | Entry song |
| --- | --- | --- |
| Daft Punk | [Random Access Memories](https://theshfl.com/album/Random-Access-Memories) | Get Lucky |
| Fleetwood Mac | [Rumours](https://theshfl.com/album/Rumours) | Dreams |
| Radiohead | [OK Computer](https://theshfl.com/album/OK-Computer) | No Surprises |
| Amy Winehouse | [Back to Black](https://theshfl.com/album/Back-to-Black) | Rehab |
| Nas | [Illmatic](https://theshfl.com/album/Illmatic) | The World Is Yours |
| Daft Punk | [Discovery](https://theshfl.com/album/Discovery-1) | One More Time |
| Massive Attack | [Mezzanine](https://theshfl.com/album/Mezzanine) | Teardrop |
| Portishead | [Dummy](https://theshfl.com/album/Dummy) | Glory Box |
| The Strokes | [Is This It](https://theshfl.com/album/Is-This-It) | Last Nite |
| Kate Bush | [Hounds of Love](https://theshfl.com/album/Hounds-of-Love) | Running Up That Hill |
| Depeche Mode | [Violator](https://theshfl.com/album/Violator) | Enjoy the Silence |
| Lauryn Hill | [The Miseducation of Lauryn Hill](https://theshfl.com/album/The-Miseducation-of-Lauryn-Hill) | Doo Wop (That Thing) |
| Kendrick Lamar | [To Pimp a Butterfly](https://theshfl.com/album/To-Pimp-a-Butterfly) | Alright |
| Pink Floyd | [The Dark Side of the Moon](https://theshfl.com/album/The-Dark-Side-of-the-Moon) | Money |
| Pixies | [Doolittle](https://theshfl.com/album/Doolittle) | Here Comes Your Man |
| Gorillaz | [Demon Days](https://theshfl.com/album/Demon-Days) | Feel Good Inc. |
| A Tribe Called Quest | [The Low End Theory](https://theshfl.com/album/The-Low-End-Theory) | Check the Rhime |
| Eminem | [The Marshall Mathers LP](https://theshfl.com/album/The-Marshall-Mathers-LP) | Stan |
| Prince and the Revolution | [Purple Rain](https://theshfl.com/album/Purple-Rain) | When Doves Cry |
| Stevie Wonder | [Songs in the Key of Life](https://theshfl.com/album/Songs-in-the-Key-of-Life) | Isn't She Lovely |
| The Cure | [Disintegration](https://theshfl.com/album/Disintegration) | Lovesong |
| R.E.M. | [Automatic for the People](https://theshfl.com/album/Automatic-for-the-People) | Everybody Hurts |
| Fugees | [The Score](https://theshfl.com/album/The-Score) | Killing Me Softly with His Song |
| Michael Jackson | [Thriller](https://theshfl.com/album/Thriller-1) | Billie Jean |

Cover URLs are the 600px images linked from these public pages. Requests contain
only the cover resource URL; Harmony does not upload its library or listening
history. Covers require network access unless cached; the local deck, notes,
filters and bookmarks work without it. Saving in Harmony does not sync with
The Shfl accounts. Harmony is not affiliated with The Shfl.

## Maintenance

Add or update entries in `ShflAlbumCatalog` only after checking the exact artist,
album page and cover. Each album needs a stable slug, accurate entry tracks,
genre choices and an original note. The browser allowlist derives from the
catalogue; arbitrary album paths, queries and unexpected hosts remain blocked.
Do not expand this into a pretend live service or label unknown songs as heard.

The native pager follows [Android's Compose pager documentation](https://developer.android.com/develop/ui/compose/layouts/pager).
See `VALIDATION-v1.4.5.md` for checks and limitations.
