// Builds the Harmony website into dist/. No dependencies: run `node build.mjs`.
//
//   dist/index.html        the complete page, ready for any static host
//   dist/assets/...        CSS, script, fonts, images
//
// With --preview it also writes dist/preview.html, the same page without the
// document wrapper, for hosts that supply their own <html>/<head>.
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { head, body } from './src/templates/page.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const out = join(root, 'dist');

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
cpSync(join(root, 'src/assets'), join(out, 'assets'), { recursive: true });

const page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
${head()}
</head>
<body>
${body()}
<script src="assets/site.js" defer></script>
</body>
</html>
`;
writeFileSync(join(out, 'index.html'), page);

if (process.argv.includes('--preview')) {
  writeFileSync(join(out, 'preview.html'), `${head({ title: 'Harmony' })}\n${body()}\n<script src="assets/site.js" defer></script>\n`);
}

console.log(`Built ${out}`);
