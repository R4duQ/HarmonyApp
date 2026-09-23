// Small HTML builders shared by the page. Strings passed as `html` are
// trusted markup written in this repository; `esc` is for plain values.
import { screens, files } from '../content/screens.mjs';

export const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

const isExternal = (href) => /^https?:\/\//.test(href);

/** A link; external links get rel="noopener" but stay in the same tab. */
export const link = (href, text, cls = '') =>
  `<a href="${esc(href)}"${cls ? ` class="${cls}"` : ''}${isExternal(href) ? ' rel="noopener"' : ''}>${text}</a>`;

/** A button-styled link. `kind` is "primary" or "quiet". */
export const button = (href, text, kind = 'primary', extra = '') =>
  `<a class="btn btn-${kind}" href="${esc(href)}"${isExternal(href) ? ' rel="noopener"' : ''}${extra}>${text}</a>`;

/**
 * A responsive screenshot. `sizes` describes the rendered width so the
 * browser picks the 390 or 780 pixel file; width/height reserve the space.
 */
export function shot(key, { sizes = '(min-width: 1000px) 300px, 70vw', eager = false, lazy = !eager, cls = '', alt, dark } = {}) {
  const s = screens[key];
  const f = files[key];
  if (!s || !f || (dark && !files[dark])) throw new Error(`Unknown screen: ${!s || !f ? key : dark}`);
  const small = f.startsWith('detail-') ? 560 : 390;
  const large = f.startsWith('detail-') ? 1120 : 780;
  const set = (name) => `assets/img/${name}-${small}.webp ${small}w, assets/img/${name}-${large}.webp ${large}w`;
  const w = large;
  const h = Math.round((s.h * large) / s.w);
  // An optional dark-mode version of the same screen, chosen by the system theme.
  const source = dark ? `<source media="(prefers-color-scheme: dark)" srcset="${set(files[dark])}" sizes="${sizes}">` : '';
  const img = `<img class="${['shot', cls].filter(Boolean).join(' ')}" src="assets/img/${f}-${large}.webp" srcset="assets/img/${f}-${small}.webp ${small}w, assets/img/${f}-${large}.webp ${large}w" sizes="${sizes}" width="${w}" height="${h}" alt="${esc(alt ?? s.alt)}"${eager ? ' fetchpriority="high"' : ''}${lazy ? ' loading="lazy"' : ''} decoding="async">`;
  return source ? `<picture>${source}${img}</picture>` : img;
}

/** A screen inside a plain phone outline (no specific manufacturer). */
export const phone = (key, opts = {}) =>
  `<div class="phone${opts.cls ? ` ${opts.cls}` : ''}"${opts.hidden ? ' aria-hidden="true"' : ''}><div class="phone-screen">${shot(key, { ...opts, cls: '', alt: opts.hidden ? '' : opts.alt })}</div></div>`;

/** A cropped interface detail on a glass card. */
export const detail = (key, opts = {}) => `<div class="detail${opts.cls ? ` ${opts.cls}` : ''}">${shot(key, { sizes: opts.sizes ?? '(min-width: 1000px) 360px, 90vw', ...opts, cls: '' })}</div>`;

/** Native disclosure: accessible and keyboard-operable without script. */
export const disclosure = (summary, body, { id = '', open = false, cls = 'more' } = {}) =>
  `<details class="${cls}"${id ? ` id="${id}"` : ''}${open ? ' open' : ''}><summary><span>${summary}</span><svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg></summary><div class="details-body">${body}</div></details>`;

/** The Harmony mark: the launcher's slanted H on its amber field. */
export const mark = (size = 32, label = '') =>
  `<svg class="mark" width="${size}" height="${size}" viewBox="6 6 96 96"${label ? ` role="img" aria-label="${esc(label)}"` : ' aria-hidden="true"'}><rect x="6" y="6" width="96" height="96" rx="26" fill="#F3A311"/><path d="M45 34 34 74M74 34 63 74M39.5 54h29" fill="none" stroke="#1A1403" stroke-width="12.5" stroke-linecap="round"/></svg>`;

/** Section heading block: small label, heading, optional lead. */
export const heading = ({ label, title, lead = '', id, level = 2 }) =>
  `<header class="section-head">${label ? `<p class="eyebrow">${label}</p>` : ''}<h${level}${id ? ` id="${id}"` : ''}>${title}</h${level}>${lead ? `<p class="lead">${lead}</p>` : ''}</header>`;
