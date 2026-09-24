// App screens used on the page. Files live in src/assets/img as
// `<name>-390.webp` and `<name>-780.webp`; `w`/`h` is the source size, used
// for the aspect ratio so the layout is reserved before an image loads.
//
// The current images are rendered from Harmony 1.0's own interface code with
// invented songs, artists and generated cover art. Replace them with phone
// screenshots at the same names and sizes whenever you have them.

const phone = { w: 1170, h: 2532 };

export const screens = {
  home: { ...phone, alt: 'Harmony Home screen: a greeting, library search, the song now playing, Smart Shuffle and recently played albums, with the mini-player and navigation bar at the bottom.' },
  homeDark: { ...phone, alt: 'Harmony Home screen in dark mode.' },
  nowPlayingDark: { ...phone, alt: 'Full player in dark mode.' },
  nowPlaying: { ...phone, alt: 'Full player showing "Night Ferry" by Mira Sol with its cover and record, a lossless FLAC badge at 96 kHz, a waveform seek bar and playback controls with Smart Shuffle on.' },
  queue: { ...phone, alt: 'Queue sheet over the player: the song now playing, two songs added with Play next, then the rest of the queue with drag handles and remove buttons.' },
  librarySongs: { ...phone, alt: 'Library on the Songs tab: songs listed alphabetically with cover, artist and length, with the mini-player above the navigation bar.' },
  libraryAlbums: { ...phone, alt: 'Library on the Albums tab: album covers in a two-column grid with titles and artists.' },
  librarySearch: { ...phone, alt: 'Library search for "mira": matching songs, albums and artists grouped under separate headings.' },
  discoverPreferences: { ...phone, alt: 'Discover step 1, Preferences: "Built from your listening" lists the artists behind the profile with a reason for each, above a Find songs button.' },
  discoverLevels: { ...phone, alt: 'Discover exploration levels: For my taste, Balanced mix (selected) and Surprise me, then the playlist size set to 50 songs.' },
  discoverOffline: { ...phone, alt: 'Discover while offline: a notice that new online recommendations are paused, and a Build from my library button.' },
  discoverSongs: { ...phone, alt: 'Discover step 2, Songs: a large song card with its reason, "Close to your taste" and "Needs download" labels, a playing preview and actions to keep, like, replace, reject or remove the song.' },
  discoverList: { ...phone, alt: 'The full list of suggested songs, each with its reason; one is marked as kept.' },
  discoverReview: { ...phone, alt: 'Discover step 3, Playlist: an editable playlist name, "12 of 20 in your library", and a button to create the playlist with the 12 available songs.' },
  discoverPlaylist: { ...phone, alt: 'The finished playlist "Discover · 23 Sep" with 12 songs, a cover made from their artwork, and Play and Shuffle buttons.' },
  playlist: { ...phone, alt: 'Playlist "Late Night Drive": a cover made from four album covers, 24 songs, Play and Shuffle buttons, and songs with drag handles and menus.' },
  detailNowPlaying: { w: 1122, h: 830, alt: 'Detail from Home: the song now playing with a pause button and progress, and a Start Mix button for Smart Shuffle.' },
  detailDiscoverCard: { w: 1110, h: 1180, alt: 'Detail from Discover: a suggested song, the reason it was picked, its labels, a preview button and song actions.' },
  detailPlaylistHeader: { w: 1170, h: 1380, alt: 'Detail from a playlist: its cover, name, song count and length, with Play and Shuffle buttons.' },
  detailDownloadsMethod: { w: 1170, h: 1405, alt: 'Downloads source picker: SpotiFLAC, Soulseek and YT Converter, with SpotiFLAC selected and described.' },
  detailDownloadsSoulseek: { w: 1170, h: 1429, alt: 'Soulseek sign-in card with username and password fields and a Connect button.' },
};

// Maps registry keys to file names.
export const files = {
  home: 'home', homeDark: 'home-dark', nowPlaying: 'now-playing', nowPlayingDark: 'now-playing-dark', queue: 'queue', librarySongs: 'library-songs',
  libraryAlbums: 'library-albums', librarySearch: 'library-search', discoverPreferences: 'discover-preferences',
  discoverLevels: 'discover-levels', discoverOffline: 'discover-offline', discoverSongs: 'discover-songs', discoverList: 'discover-list',
  discoverReview: 'discover-review', discoverPlaylist: 'discover-playlist', playlist: 'playlist',
  detailNowPlaying: 'detail-now-playing', detailDiscoverCard: 'detail-discover-card', detailPlaylistHeader: 'detail-playlist-header',
  detailDownloadsMethod: 'detail-downloads-method', detailDownloadsSoulseek: 'detail-downloads-soulseek',
};
