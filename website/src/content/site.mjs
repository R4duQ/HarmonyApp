// Product facts for the website. Change a release, a link or a requirement
// here; every section that shows it reads from this file.
//
// Every value below is taken from the app's source or its GitHub release.
// If a fact is not confirmed, leave it out rather than guessing.

const repo = 'https://github.com/R4duQ/HarmonyApp';

export const release = {
  version: '1.0',
  versionCode: 101,
  tag: 'v1.0',
  date: '2026-09-24', // GitHub release published_at
  // 'stable' shows "Download Harmony"; 'beta' shows "Try the beta";
  // 'none' shows "Follow development" and links to the repository.
  status: 'stable',
};

export const links = {
  repo,
  release: `${repo}/releases/tag/${release.tag}`,
  apkPhone: `${repo}/releases/download/${release.tag}/Harmony-v${release.version}-arm64-v8a.apk`,
  apkEmulator: `${repo}/releases/download/${release.tag}/Harmony-v${release.version}-x86_64.apk`,
  checksums: `${repo}/releases/download/${release.tag}/SHA256SUMS.txt`,
  releaseNotes: `${repo}/blob/${release.tag}/docs/RELEASE-v${release.version}-STABLE.md`,
  allReleases: `${repo}/releases`,
  issues: `${repo}/issues`,
  newIssue: `${repo}/issues/new`,
  thirdParty: `${repo}/blob/${release.tag}/THIRD_PARTY_NOTICES.md`,
};

const actions = {
  stable: { label: 'Download Harmony', short: 'Download', href: links.release },
  beta: { label: 'Try the beta', short: 'Try beta', href: links.release },
  none: { label: 'Follow development', short: 'Follow', href: links.repo },
};
export const primaryAction = actions[release.status];

export const site = {
  name: 'Harmony',
  title: 'Harmony · Music player for Android',
  socialTitle: 'Harmony · Your files. Your music. Your rules.',
  description:
    'Harmony is a music player for Android. Listen to the music files on your phone, discover new tracks, and build playlists that feel like you.',
  // Absolute URL of the published site, used for the canonical link and
  // social previews. The Pages workflow passes it in as SITE_URL; set it here
  // if you host the site elsewhere. Empty means relative paths only.
  url: process.env.SITE_URL || '',
  owner: 'r4duq',
  year: 2026,
};

export const nav = [
  { label: 'Overview', href: '#overview' },
  { label: 'Features', href: '#features' },
  { label: 'How it works', href: '#how-it-works' },
  { label: 'FAQ', href: '#faq' },
];

// Shown in "Get Harmony". `value` may contain simple inline HTML.
export const requirements = [
  { label: 'Platform', value: 'Android phones with a 64-bit ARM processor (arm64-v8a)' },
  { label: 'Minimum', value: 'Android 10' },
  { label: 'Version', value: `<span class="mono">${release.version}</span>, released 24 September 2026` },
  { label: 'Status', value: 'Stable release' },
  { label: 'Install', value: 'APK file from GitHub Releases' },
  { label: 'Also built', value: 'An x86_64 APK for the Android emulator, for testing on a computer' },
  { label: 'Not available', value: 'iPhone, iPad, Windows, macOS, Linux' },
];

// Extra capabilities listed under "Also in Harmony 1.0". `status` is
// 'available', 'in-development' or 'known-issue' (with a `note`).
export const extras = [
  { name: 'Equalizer', detail: 'Ten bands, a simple bass and treble view, and profiles you can save.', status: 'available' },
  { name: 'Volume levelling', detail: 'ReplayGain evens out loudness between tracks, by track or by album.', status: 'available' },
  { name: 'Crossfade', detail: 'Blend songs together over 1 to 12 seconds.', status: 'available' },
  { name: 'Tag editor', detail: "Fix a song's title, artist, album or artwork.", status: 'available' },
  { name: 'FLAC check', detail: 'A spectrum view that shows whether a FLAC file looks genuinely lossless, with its sample rate and bit depth.', status: 'available' },
  { name: 'Themes', detail: 'Light, dark or follow the system, with a true-black option and wallpaper colors on Android 12 and later.', status: 'available' },
  {
    name: 'Android Auto',
    detail: 'Browse and play your library from the car display.',
    status: 'known-issue',
    note: 'On some first connections the car shows only a limited player. This is being investigated.',
  },
];
