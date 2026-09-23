// Questions for the FAQ section. Answers are HTML; keep each to a few
// sentences and link out for detail. Every answer reflects Harmony 1.0.
import { links } from './site.mjs';

export const faq = [
  {
    id: 'what',
    q: 'What is Harmony?',
    a: `<p>Harmony is a music player for Android. It plays the audio files stored on your phone, suggests new songs based on what you listen to, and helps you turn them into playlists. It can also download files from sources you connect, such as Soulseek.</p>`,
  },
  {
    id: 'devices',
    q: 'Which devices can run it?',
    a: `<p>Android phones running Android 10 or later with a 64-bit ARM processor. There is also a build for the Android emulator on a computer. There is no iPhone or desktop version.</p>`,
  },
  {
    id: 'offline',
    q: 'Can I listen offline?',
    a: `<p>Yes. Everything stored on your phone plays without a connection, including playlists, Smart Shuffle and the equalizer. Songs you find in Discover are not available offline until they have been downloaded as files to your phone.</p>`,
  },
  {
    id: 'discover-internet',
    q: 'Does Discovery require internet?',
    a: `<p>New online recommendations and 30-second previews need a connection. Offline, a selection you already made stays saved, and Harmony can build a selection from your own library instead.</p>`,
  },
  {
    id: 'recommendations',
    q: 'How are recommendations selected?',
    a: `<p>Harmony looks at your play history (recent plays count more), songs you finish or replay, skips, favorites, the songs in your own playlists and your "More like this" and "Not interested" choices. It mixes songs close to that taste with artists related to the ones you play, in the proportion you choose. Every suggestion shows the reason it was picked.</p>`,
  },
  {
    id: 'accounts',
    q: 'Do I need accounts with external providers?',
    a: `<p>Harmony has no account of its own, and listening and Discover need no sign-in. Some online features use yours:</p>
<ul>
  <li><strong>Soulseek</strong> downloads need your Soulseek username and password.</li>
  <li><strong>SpotiFLAC</strong> may ask you to verify in your browser before downloading, and again when that session expires.</li>
  <li><strong>Spotify playlist transfer</strong> needs a one-time setup: you register a Spotify developer app and paste its Client ID into Harmony.</li>
</ul>`,
  },
  {
    id: 'unavailable',
    q: 'What happens if some playlist tracks are unavailable?',
    a: `<p>You can save the playlist with the songs you already have. The rest stay in your selection and join the same playlist once they are downloaded, even after a restart. A song you remove from the playlist is not added back.</p>`,
  },
  {
    id: 'folder',
    q: 'Can I choose where downloads are saved?',
    a: `<p>Yes. In Downloads, choose a folder on your phone for files from Soulseek and SpotiFLAC. Harmony adds finished files to your library.</p>`,
  },
  {
    id: 'import-export',
    q: 'Can I import or export playlists?',
    a: `<p>Yes. Playlists import from and export to M3U files, a plain playlist format most music players read. You can also transfer a playlist from Spotify: songs already on your phone are matched in their original order, and missing ones can be downloaded.</p>`,
  },
  {
    id: 'problem',
    q: 'Where can I report a problem?',
    a: `<p>Open an issue on <a href="${links.issues}">Harmony's GitHub issue tracker</a>. Include your phone model, Android version and the steps that led to the problem.</p>`,
  },
];
