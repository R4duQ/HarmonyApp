# Harmony website

A single-page product site for Harmony: overview, features, how Discover works,
downloads, playlists, availability and FAQ. It is plain HTML, CSS and a small
script, built by a dependency-free Node script.

```sh
node website/build.mjs          # writes website/dist/
python3 -m http.server -d website/dist 8000   # preview at http://localhost:8000
```

Node 18 or later. Nothing to install.

## Where things live

| To change | Edit |
| --- | --- |
| Version, release date, download links, requirements, "Also in 1.0" list | `src/content/site.mjs` |
| FAQ questions and answers | `src/content/faq.mjs` |
| Screenshots and their alt text | `src/content/screens.mjs` and `src/assets/img/` |
| Section copy and layout | `src/templates/page.mjs` |
| Shared pieces (phone frame, disclosures, buttons) | `src/templates/components.mjs` |
| Colors, type, spacing, motion | `src/assets/site.css` (tokens at the top) |
| Mobile menu, FAQ deep links, active nav | `src/assets/site.js` |

`release.status` in `site.mjs` sets the main button everywhere: `stable` shows
"Download Harmony", `beta` shows "Try the beta", `none` shows "Follow
development" and links to the repository.

For a new release, update `release` in `site.mjs`. The download, checksum and
release-notes links follow from the tag. Check that
`docs/RELEASE-v<version>-STABLE.md` exists at that tag.

## Screenshots

The app screens are rendered from Harmony 1.0's own Compose code with invented
songs, artists and generated cover art. No real album artwork is used. The
footer and the hero caption say that the music shown is sample content.

To replace them with phone screenshots, keep the file names and export two
widths as WebP:

- phone screens: `<name>-390.webp` and `<name>-780.webp` (portrait, 1170×2532 source)
- interface details: `detail-<name>-560.webp` and `detail-<name>-1120.webp`

Then update `w`, `h` and `alt` in `screens.mjs` if the proportions or content
changed. If you move the playlist screenshot, check the numbered markers in
the Playlists section (`callouts` in `page.mjs`, positions in percent).

## Publishing

`.github/workflows/website.yml` builds the site and publishes it with GitHub
Pages when `website/` changes on `main`, or when run by hand from the Actions
tab. Before the first run, set **Settings → Pages → Source** to **GitHub
Actions**. The workflow passes the Pages address to the build as `SITE_URL`,
so the canonical link and social previews use absolute URLs.

Any static host works: upload the contents of `website/dist/`. Set `SITE_URL`
when building for another address.

## Checks run before handoff

The page was checked at 320, 390, 768, 1280 and 1440 pixels wide, in light
and dark mode, and at 200% default text size:

- no horizontal scrolling at any of those widths or text sizes
- mobile menu: opens, moves focus in, closes with Escape and on navigation
- keyboard: skip link first, visible focus ring, FAQ opens with Enter and Space
- `#faq-…` links open the matching answer
- every in-page anchor resolves and every external link returns 200
- images have width and height (no layout shift) and alt text; the decorative back phone in the hero is hidden from screen readers
- one `h1`, no skipped heading levels
- no animation when the system asks for reduced motion

## Before launch

Decisions only the owner can make. The site works without them, but they
shape what visitors see.

1. **Hosting and address.** Enable GitHub Pages (above), or choose another
   host or domain.
2. **Privacy policy.** None exists, so the site makes no privacy promises and
   links to none. Settings in the app says Harmony has no Harmony account,
   analytics or ads; publish that as a policy if you want the site to state it.
3. **License.** The repository has no license file. The footer uses the app's
   own copyright line ("All rights reserved").
4. **Support channel.** GitHub Issues is the only support route on the page.
   Add an email or another channel to `links` and the FAQ if you have one.
5. **Android Auto.** Listed with a "Known issue" label because some first
   connections show only a limited player. Remove the note once fixed.
6. **Service names in the footer.** The non-affiliation line extends the app's
   own wording to Spotify, Deezer, Apple Music and MusicBrainz, which the site
   also names. Review it if you prefer different wording.
7. **Product video.** There is no video section because no video was supplied.
   Add one with a poster image, an explicit play button and captions; no
   autoplay.
