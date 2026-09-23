// The landing page. Copy lives here; facts, links and FAQ come from content/.
import { site, release, links, nav, primaryAction, requirements, extras } from '../content/site.mjs';
import { faq } from '../content/faq.mjs';
import { esc, link, button, shot, phone, detail, disclosure, mark, heading } from './components.mjs';

const arrow = '<svg class="arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"/></svg>';
const download = '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>';

const statusLabel = { stable: 'Stable', beta: 'Beta', none: 'In development' }[release.status];

function header() {
  const items = nav.map((n) => `<li><a href="${n.href}">${n.label}</a></li>`).join('');
  return `
<a class="skip" href="#main">Skip to content</a>
<header class="site-header">
  <div class="wrap header-row">
    <a class="brand" href="#top" aria-label="Harmony, back to top">${mark(30)}<span>Harmony</span></a>
    <nav class="primary-nav" aria-label="Main">
      <ul>${items}</ul>
    </nav>
    <div class="header-actions">
      ${button(primaryAction.href, `${download}<span class="label-long">${primaryAction.label}</span><span class="label-short">${primaryAction.short}</span>`, 'primary', ' data-cta="header"')}
      <button class="menu-toggle" type="button" aria-expanded="false" aria-controls="mobile-menu">
        <span class="visually-hidden">Menu</span>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path class="l1" d="M4 8h16"/><path class="l2" d="M4 16h16"/></svg>
      </button>
    </div>
  </div>
  <nav class="mobile-menu" id="mobile-menu" aria-label="Main" hidden>
    <ul class="wrap">${items}</ul>
  </nav>
</header>`;
}

function hero() {
  return `
<section class="hero" id="top" aria-labelledby="hero-title">
  <div class="wrap hero-grid">
    <div class="hero-copy">
      <p class="status"><span class="dot" aria-hidden="true"></span>Version <span class="mono">${release.version}</span>&nbsp;· ${statusLabel}&nbsp;· Android&nbsp;10+</p>
      <h1 id="hero-title" class="statement">
        <span class="line">Your files.</span>
        <span class="line">Your music.</span>
        <span class="line"><span class="hl">Your rules</span></span>
      </h1>
      <p class="hero-lead">A home for your music collection. Listen to your favorites, discover new tracks, and build playlists that feel like you.</p>
      <div class="hero-actions">
        ${button(primaryAction.href, `${download}${primaryAction.label}`, 'primary btn-large', ' data-cta="hero"')}
        <a class="text-link" href="#features">Explore the features ${arrow}</a>
      </div>
      <p class="fine">APK download from GitHub for 64-bit Android phones. No Harmony account needed.</p>
    </div>
    <figure class="hero-visual">
      <div class="hero-phones">
        ${phone('nowPlaying', { cls: 'phone-back', hidden: true, lazy: false, dark: 'nowPlayingDark', sizes: '(min-width: 1000px) 260px, 1px' })}
        ${phone('home', { cls: 'phone-front', eager: true, dark: 'homeDark', sizes: '(min-width: 1000px) 300px, (min-width: 600px) 300px, 68vw' })}
      </div>
      <figcaption>Harmony 1.0 on Android. Songs and cover art are samples.</figcaption>
    </figure>
  </div>
</section>`;
}

function overview() {
  const item = (key, name, title, body, href, more) => `
    <li class="pillar">
      ${detail(key, { sizes: '(min-width: 1000px) 340px, (min-width: 700px) 30vw, 88vw' })}
      <h3><span class="pillar-name">${name}</span> ${title}</h3>
      <p>${body}</p>
      <a class="text-link" href="${href}">${more} ${arrow}</a>
    </li>`;
  return `
<section class="section overview" id="overview" aria-labelledby="overview-title">
  <div class="wrap">
    ${heading({ label: 'Overview', title: 'Three things Harmony does for your music', id: 'overview-title' })}
    <ol class="pillars">
      ${item('detailNowPlaying', 'Listen.', 'Play what you already have.', 'Browse songs, albums and artists stored on your phone, and control playback from any screen.', '#listening', 'Listening and library')}
      ${item('detailDiscoverCard', 'Discover.', 'Find what to play next.', 'Get song suggestions based on your own listening, each with the reason it was picked.', '#discovery', 'How Discover works')}
      ${item('detailPlaylistHeader', 'Create.', 'Keep it in playlists.', 'Collect songs into playlists, reorder them, and play or shuffle with one tap.', '#playlists', 'Playlists')}
    </ol>
  </div>
</section>`;
}

function row({ id, title, body, visual, flip = false, extra = '' }) {
  return `
    <article class="feature-row${flip ? ' flip' : ''}"${id ? ` id="${id}"` : ''} aria-labelledby="${id}-title">
      <div class="feature-copy">
        <h3 id="${id}-title">${title}</h3>
        ${body}
        ${extra}
      </div>
      <div class="feature-visual">${visual}</div>
    </article>`;
}

function listening() {
  const controls = [
    'Play, pause, previous and next, or swipe the cover to skip',
    "Seek along the song's waveform",
    'Repeat all or repeat one',
    'Smart Shuffle or plain random shuffle',
    'Add to favorites',
    'Lyrics, when the file includes them',
    'Equalizer and the current audio output',
    'Format, bitrate and sample rate, with a lossless badge',
  ].map((c) => `<li>${c}</li>`).join('');
  return `
<section class="section listening" id="features" aria-labelledby="listening-title">
  <div class="wrap">
    ${heading({ label: 'Listening and library', title: 'Your music, the way you like to browse it', id: 'listening-title', lead: 'Harmony reads the music files on your phone and keeps them in order. There is nothing to sign in to.' })}
    <div class="rows" id="listening">
      ${row({
        id: 'library',
        title: 'Songs, albums and artists',
        body: `<p>Browse your collection by song, album or artist. Harmony finds the audio on your phone, and you can add a USB drive or another folder from Settings.</p>`,
        visual: phone('libraryAlbums'),
      })}
      ${row({
        id: 'search',
        flip: true,
        title: 'Find any track in a few letters',
        body: `<p>One search looks through songs, albums and artists at once and groups what it finds.</p>`,
        visual: phone('librarySearch'),
      })}
      ${row({
        id: 'player',
        title: 'Keep browsing while your music stays within reach',
        body: `<p>The mini-player sits above the navigation on every screen. Tap it to open the full player, and swipe down to go back to where you were.</p>`,
        extra: disclosure('Controls in the full player', `<ul class="ticks">${controls}</ul>`),
        visual: `<div class="pair">${phone('librarySongs', { sizes: '(min-width: 1000px) 240px, 44vw' })}${phone('nowPlaying', { sizes: '(min-width: 1000px) 240px, 44vw' })}</div>`,
      })}
      ${row({
        id: 'queue',
        flip: true,
        title: 'Decide what plays next',
        body: `<p>Swipe up in the player to see the queue. Songs you add with <em>Play next</em> come first. Drag songs to reorder them, or remove the ones you want to skip.</p>`,
        extra: disclosure('About Smart Shuffle', `<p>Smart Shuffle builds the queue from your library instead of picking at random. Pick a style:</p>
<dl class="pairs">
  <dt>Balanced</dt><dd>A natural mix of favorites, forgotten tracks and smooth transitions.</dd>
  <dt>Familiar</dt><dd>Leans into favorites and songs you usually finish.</dd>
  <dt>Discover</dt><dd>Surfaces tracks you rarely play or haven't heard in a long time.</dd>
  <dt>Flow</dt><dd>Keeps energy and genre steady, with fewer abrupt changes.</dd>
</dl>
<p><em>Journey</em> gradually moves the music toward a mood you choose. Smart Shuffle uses only the songs on your phone.</p>`),
        visual: phone('queue'),
      })}
    </div>
  </div>
</section>`;
}

function onlineOffline() {
  return `
<section class="section connectivity" id="offline" aria-labelledby="offline-title">
  <div class="wrap">
    <h2 id="offline-title" class="h-compact">What works offline</h2>
    <div class="split-list">
      <div class="panel">
        <h3><span class="badge badge-offline">No connection needed</span></h3>
        <ul class="ticks">
          <li>Playing music stored on your phone</li>
          <li>Library, search and playlists</li>
          <li>Smart Shuffle, equalizer and volume levelling</li>
          <li>A Discover selection you already made</li>
        </ul>
      </div>
      <div class="panel">
        <h3><span class="badge badge-online">Needs a connection</span></h3>
        <ul class="ticks">
          <li>New Discover recommendations and song previews</li>
          <li>Downloads from SpotiFLAC, Soulseek and YT Converter</li>
          <li>Transferring a playlist from Spotify</li>
        </ul>
      </div>
    </div>
    <p class="note">Songs you find online are not stored on your phone until they are downloaded as files.</p>
  </div>
</section>`;
}

function discovery() {
  return `
<section class="section band band-discover" id="discovery" aria-labelledby="discovery-title">
  <div class="wrap">
    ${heading({ label: 'Discover', title: 'Suggestions that start from your own listening', id: 'discovery-title', lead: 'Discover proposes songs you might like next and turns the ones you keep into a playlist. You choose how far from your usual music it goes.' })}
    <div class="discover-grid">
      <div class="discover-copy">
        <h3>What Harmony looks at</h3>
        <ul class="ticks">
          <li>Your play history, with recent plays counting more</li>
          <li>Songs you finish and replay, and songs you skip</li>
          <li>Your favorites and the songs in your own playlists</li>
          <li>Your <em>More like this</em> and <em>Not interested</em> choices</li>
        </ul>
        <p>Each suggestion tells you why it is there, for example “You've played Mira Sol a lot lately”. A skip only lowers an artist a little; it never removes them.</p>

        <h3>How far it goes</h3>
        <table class="levels">
          <caption class="visually-hidden">Exploration levels and their approximate mix</caption>
          <thead><tr><th scope="col">Level</th><th scope="col">Close to your taste</th><th scope="col">Discoveries</th></tr></thead>
          <tbody>
            <tr><th scope="row">For my taste</th><td class="mono">~80%</td><td class="mono">~20%</td></tr>
            <tr><th scope="row">Balanced mix</th><td class="mono">~60%</td><td class="mono">~40%</td></tr>
            <tr><th scope="row">Surprise me</th><td class="mono">~40%</td><td class="mono">~60%</td></tr>
          </tbody>
        </table>
        <p>Songs close to your taste come from artists and genres you already play. Discoveries come from artists that Deezer lists as related to yours, so even the adventurous picks stay connected to your taste. You can also filter by country, genre or keyword.</p>

        ${disclosure('Where the songs come from', `<p>Harmony makes the selection itself. Online catalogs only supply facts:</p>
<dl class="pairs">
  <dt>Deezer</dt><dd>Artist search, top tracks, related artists, genre charts and 30-second previews.</dd>
  <dt>Apple Music</dt><dd>Country charts, previews, and a backup search when Deezer does not answer.</dd>
  <dt>MusicBrainz</dt><dd>An artist's country, used only when you filter by country.</dd>
</dl>
<p>None of these services needs an account. Finding a song in a catalog does not mean a file is available: a song joins your library once it is downloaded.</p>`)}
      </div>
      <div class="discover-visual">
        <div class="pair">
          ${phone('discoverPreferences', { sizes: '(min-width: 1000px) 250px, 44vw' })}
          ${phone('discoverLevels', { sizes: '(min-width: 1000px) 250px, 44vw' })}
        </div>
      </div>
    </div>
    <aside class="callout" aria-labelledby="discover-offline-title">
      <div class="callout-copy">
        <h3 id="discover-offline-title">New online recommendations require an internet connection.</h3>
        <p>Offline, your current selection stays saved, and Harmony can build a selection from your own library instead. Replacing a song uses your library until you are back online.</p>
      </div>
      ${phone('discoverOffline', { cls: 'phone-small', sizes: '(min-width: 700px) 180px, 40vw' })}
    </aside>
  </div>
</section>`;
}

function walkthrough() {
  const step = (n, key, title, body) => `
      <li class="step">
        <div class="step-copy">
          <p class="step-num" aria-hidden="true">${n}</p>
          <h3>${title}</h3>
          <p>${body}</p>
        </div>
        ${phone(key, { sizes: '(min-width: 1000px) 250px, (min-width: 700px) 30vw, 70vw' })}
      </li>`;
  return `
<section class="section walkthrough" id="how-it-works" aria-labelledby="how-title">
  <div class="wrap">
    ${heading({ label: 'How it works', title: 'From discovery to playlist', id: 'how-title' })}
    <ol class="steps">
      ${step(1, 'discoverSongs', 'Explore suggested tracks', 'Go through the suggestions one card at a time and play a 30-second preview of any song.')}
      ${step(2, 'discoverList', 'Review and adjust your selection', 'Keep the songs you like, replace the ones you don\'t, ask for more like a song, or mark it as not interested. After removing songs, top the list back up in one tap.')}
      ${step(3, 'discoverReview', 'Name and save your playlist', 'Give it a name and see which songs are already on your phone before you save.')}
    </ol>
    <div class="result">
      <div class="result-copy">
        <p class="eyebrow">The result</p>
        <h3>A playlist in your library</h3>
        <p>If some tracks aren't available, save a playlist with the available songs and complete it later.</p>
        <p class="muted">Download the missing songs from the same screen. Each one joins the playlist once it is on your phone, even after a restart.</p>
      </div>
      ${phone('discoverPlaylist', { sizes: '(min-width: 1000px) 280px, 70vw' })}
    </div>
  </div>
</section>`;
}

function downloads() {
  return `
<section class="section downloads" id="downloads" aria-labelledby="downloads-title">
  <div class="wrap">
    ${heading({ label: 'Downloads', title: 'Add files to your library from sources you connect', id: 'downloads-title', lead: 'Downloading is separate from listening and Discover. Pick one source, and Harmony uses only that one. It never switches to another on its own.' })}
    <div class="downloads-grid">
      <div class="downloads-copy">
        ${disclosure('SpotiFLAC', `<p>Downloads from verified providers as FLAC, up to 16-bit/44.1 kHz or up to 24-bit/96 kHz, or as a 320 kbps MP3 made from the lossless file. Search by artist and title, then pick the exact result. Some providers ask you to verify in your browser first; Harmony then continues the same download.</p><p class="muted">Quality depends on the recording and the provider. Harmony does not upscale lower-resolution audio.</p>`, { open: true })}
        ${disclosure('Soulseek', `<p>A peer-to-peer network: files come from other people's shared folders. Sign in with your Soulseek account, then choose the peer and the file yourself. Harmony ranks results by how well they match, their quality, and each peer's queue and speed.</p><p class="muted">You can share a folder back to the network, which Soulseek relies on. Sharing is off until you choose a folder.</p>`)}
        ${disclosure('YT Converter', `<p>Paste a YouTube link and Harmony converts it on your phone to FLAC or MP3.</p>`)}
        ${disclosure('Where files go, and progress', `<ul class="ticks">
  <li>Choose the folder for Soulseek and SpotiFLAC downloads.</li>
  <li>Follow each download: queued, downloading, validating, saving. A banner keeps a running download visible on other screens.</li>
  <li>Before a batch download starts, Harmony checks that the source is ready and tells you what to do if it isn't, such as signing in or verifying.</li>
  <li>If a song fails, retry the missing songs. Files that already finished are kept when you pause.</li>
  <li>Optionally download only on unmetered networks such as Wi-Fi.</li>
  <li>Downloaded FLAC and MP3 files are checked, and the FLAC check shows whether a file looks genuinely lossless.</li>
</ul>`)}
        <p class="legal-note">Download only content you own or are authorized to download.</p>
      </div>
      <div class="downloads-visual">
        ${detail('detailDownloadsMethod', { sizes: '(min-width: 1000px) 400px, 88vw' })}
        ${detail('detailDownloadsSoulseek', { sizes: '(min-width: 1000px) 400px, 88vw' })}
      </div>
    </div>
  </div>
</section>`;
}

function playlists() {
  const callouts = [
    { n: 1, x: 81, y: 10.5, t: 'Cover', d: 'Made from the artwork of the songs inside.' },
    { n: 2, x: 19, y: 44, t: 'Name and details', d: 'Rename it any time. See the song count, length and artists.' },
    { n: 3, x: 50.5, y: 57, t: 'Play and Shuffle', d: 'Start the whole playlist in order or shuffled.' },
    { n: 4, x: 9.7, y: 62, t: 'Drag to reorder', d: 'Hold the handle and move a song up or down.' },
    { n: 5, x: 89, y: 62, t: 'Song menu', d: 'Play next, or remove the song from the playlist.' },
  ];
  const markers = callouts.map((c) => `<span class="marker" style="left:${c.x}%;top:${c.y}%" aria-hidden="true">${c.n}</span>`).join('');
  const legend = callouts.map((c) => `<li><span class="marker-num" aria-hidden="true">${c.n}</span><div><strong>${c.t}</strong><span>${c.d}</span></div></li>`).join('');
  return `
<section class="section band band-playlists" id="playlists" aria-labelledby="playlists-title">
  <div class="wrap">
    ${heading({ label: 'Playlists', title: 'Playlists you can shape', id: 'playlists-title', lead: 'Make as many as you like, from your library or from Discover.' })}
    <div class="annotated">
      <figure class="annotated-figure">
        <div class="phone"><div class="phone-screen annotated-screen">${shot('playlist', { sizes: '(min-width: 1000px) 320px, 76vw' })}${markers}</div></div>
      </figure>
      <div class="annotated-copy">
        <ol class="legend" aria-label="Parts of the playlist screen">${legend}</ol>
        <h3>Also for playlists</h3>
        <ul class="ticks">
          <li>Create, rename and delete playlists; deleting never removes the songs</li>
          <li>Add songs from your library, or from any song's menu</li>
          <li>Import and export M3U files, a plain playlist format most players read</li>
          <li>Transfer a playlist from Spotify, matching songs already on your phone</li>
          <li>Smart playlists that update themselves: Favorites, Recently added, Recently played, Most played, and highest or lowest energy</li>
        </ul>
      </div>
    </div>
  </div>
</section>`;
}

function more() {
  const tag = (e) =>
    e.status === 'known-issue' ? '<span class="badge badge-issue">Known issue</span>'
      : e.status === 'in-development' ? '<span class="badge badge-dev">In development</span>' : '';
  const items = extras.map((e) => `<li><h3>${esc(e.name)} ${tag(e)}</h3><p>${esc(e.detail)}</p>${e.note ? `<p class="muted small">${esc(e.note)}</p>` : ''}</li>`).join('');
  return `
<section class="section extras-section" id="more" aria-labelledby="more-title">
  <div class="wrap">
    <h2 id="more-title" class="h-compact">Also in Harmony ${release.version}</h2>
    <ul class="extras">${items}</ul>
  </div>
</section>`;
}

function availability() {
  const rows = requirements.map((r) => `<div class="spec"><dt>${esc(r.label)}</dt><dd>${r.value}</dd></div>`).join('');
  return `
<section class="section get" id="get" aria-labelledby="get-title">
  <div class="wrap get-grid">
    <div>
      ${heading({ label: 'Availability', title: 'Get Harmony', id: 'get-title' })}
      <dl class="specs">${rows}</dl>
    </div>
    <div class="get-actions panel">
      <h3>Download</h3>
      <p>For your phone, download the arm64 APK and open it. Android asks you to allow installs from your browser or file manager the first time.</p>
      ${button(links.apkPhone, `${download}Download APK for phones`, 'primary')}
      <p class="fine">Harmony-v${release.version}-arm64-v8a.apk</p>
      <ul class="link-list">
        <li>${link(links.release, `Release ${release.version} on GitHub ${arrow}`)}</li>
        <li>${link(links.releaseNotes, `Release notes ${arrow}`)}</li>
        <li>${link(links.checksums, `SHA-256 checksums ${arrow}`)}</li>
        <li>${link(links.apkEmulator, `Emulator APK (x86_64) ${arrow}`)}</li>
        <li>${link(links.repo, `Source code and project ${arrow}`)}</li>
      </ul>
      <p class="muted small">Already using an earlier Harmony release? Version ${release.version} installs over it and keeps your library, playlists and settings.</p>
    </div>
  </div>
</section>`;
}

function faqSection() {
  const items = faq.map((f) => disclosure(f.q, f.a, { id: `faq-${f.id}`, cls: 'faq-item' })).join('');
  return `
<section class="section faq" id="faq" aria-labelledby="faq-title">
  <div class="wrap faq-grid">
    ${heading({ label: 'FAQ', title: 'Questions, answered', id: 'faq-title', lead: `Something missing? ${link(links.issues, 'Ask on GitHub')}.` })}
    <div class="faq-list">${items}</div>
  </div>
</section>`;
}

function finalCta() {
  return `
<section class="final" aria-labelledby="final-title">
  <div class="wrap final-inner">
    ${mark(56)}
    <h2 id="final-title">Make room for your next favorite song.</h2>
    <p>Harmony ${release.version} for Android 10 and later.</p>
    ${button(primaryAction.href, `${download}${primaryAction.label}`, 'primary btn-large', ' data-cta="final"')}
  </div>
</section>`;
}

function footer() {
  return `
<footer class="site-footer">
  <div class="wrap footer-grid">
    <div class="footer-brand">
      <a class="brand" href="#top">${mark(26)}<span>Harmony</span></a>
      <p>A music player for Android.</p>
    </div>
    <nav aria-label="Footer">
      <ul class="footer-links">
        <li>${link(links.release, 'Download')}</li>
        <li>${link(links.releaseNotes, 'Release notes')}</li>
        <li>${link(links.allReleases, 'All releases')}</li>
        <li>${link(links.repo, 'Project on GitHub')}</li>
        <li>${link(links.issues, 'Support and bug reports')}</li>
        <li>${link(links.thirdParty, 'Third-party notices')}</li>
      </ul>
    </nav>
    <div class="footer-legal">
      <p>© ${site.year} ${esc(site.owner)}. All rights reserved.</p>
      <p>Harmony does not grant rights to music or other third-party content. Online download and peer-to-peer features should only be used for content you are permitted to access or download.</p>
      <p>Soulseek, YouTube, Spotify, Deezer, Apple Music, MusicBrainz and other service names belong to their owners. Harmony is not affiliated with, sponsored by or endorsed by them.</p>
      <p>App screens on this page show sample songs and generated cover art.</p>
    </div>
  </div>
</footer>`;
}

export function body() {
  return `${header()}
<main id="main">
${hero()}
${overview()}
${listening()}
${onlineOffline()}
${discovery()}
${walkthrough()}
${downloads()}
${playlists()}
${more()}
${availability()}
${faqSection()}
${finalCta()}
</main>
${footer()}`;
}

export function head({ base = '', title = site.title } = {}) {
  const abs = (p) => (site.url ? `${site.url.replace(/\/$/, '')}/${p}` : p);
  return `<title>${esc(title)}</title>
<meta name="description" content="${esc(site.description)}">
<meta name="theme-color" content="#F4FDFF" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#15121F" media="(prefers-color-scheme: dark)">
<link rel="icon" href="${base}assets/favicon.svg" type="image/svg+xml">
<link rel="icon" href="${base}assets/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="${base}assets/apple-touch-icon.png">
<meta property="og:type" content="website">
<meta property="og:title" content="${esc(site.socialTitle)}">
<meta property="og:description" content="${esc(site.description)}">
<meta property="og:image" content="${abs('assets/social-card.png')}">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="Harmony: Your files. Your music. Your rules. The Harmony Home screen on a phone.">
<meta name="twitter:card" content="summary_large_image">
${site.url ? `<link rel="canonical" href="${esc(site.url)}">\n<meta property="og:url" content="${esc(site.url)}">` : ''}
<link rel="preload" href="${base}assets/fonts/archivo-var.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="${base}assets/fonts/figtree-var.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="${base}assets/site.css">`;
}
