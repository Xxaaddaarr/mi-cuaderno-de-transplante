// Arma la página final: envuelve src/app.html en un documento completo, incrusta supabase-js
// (para que funcione sin internet dentro de la app de Android) y la configuración pública.
import fs from 'node:fs';
import path from 'node:path';

const root = path.dirname(new URL(import.meta.url).pathname);
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const safe = js => js.replace(/<\/script/gi, '<\\/script');

const app = read('src/app.html');
const cfg = JSON.parse(read('config.json'));
const sbjs = read('node_modules/@supabase/supabase-js/dist/umd/supabase.js');

const head = `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex">
<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>
<script>window.MCT_CONFIG=${JSON.stringify(cfg)}</script>
<script>${safe(sbjs)}</script>
</head><body>`;

const html = head + app + '</body></html>';
fs.mkdirSync(path.join(root, 'dist'), { recursive: true });
fs.writeFileSync(path.join(root, 'dist/index.html'), html);

const appDir = path.join(root, '../app/src');
if (fs.existsSync(appDir)) fs.writeFileSync(path.join(appDir, 'webapp.js'), `// Generado por web/build.mjs. No editar a mano.\nexport default ${JSON.stringify(html)};\n`);
console.log(`dist/index.html ${(html.length / 1024).toFixed(0)} KB`);
