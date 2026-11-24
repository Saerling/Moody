// Mi servidor de música: fuente de canciones para Moody (protocolo de módulos web de Moody).
// Moody llama: GET /manifest.json, GET /search?q=artista título, GET /stream/{id}
// Sirve tu biblioteca (FLAC, MP3...) con byte-ranges y, si tienes yt-dlp, SoundCloud/YouTube a través de un proxy.
require('dotenv').config();
const express = require('express');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { Readable } = require('stream');
const { execFile } = require('child_process');

const PORT = Number(process.env.PORT || 3000);
const MUSIC_DIR = process.env.MUSIC_DIR || '/storage/emulated/0/Music';
const PUBLIC_URL = (process.env.PUBLIC_URL || '').replace(/\/$/, '');
const ENABLE_YTDLP = process.env.ENABLE_YTDLP === 'true';
const INDEX_FILE = path.join(__dirname, '.library-index.json');
const AUDIO = { '.mp3': ['mp3', 'audio/mpeg'], '.flac': ['flac', 'audio/flac'], '.m4a': ['aac', 'audio/mp4'], '.alac': ['alac', 'audio/mp4'], '.wav': ['wav', 'audio/wav'], '.ogg': ['vorbis', 'audio/ogg'], '.opus': ['opus', 'audio/ogg'], '.aac': ['aac', 'audio/aac'] };
const LOSSLESS = new Set(['flac', 'wav', 'alac']);

let library = new Map(); // id estable -> pista
const app = express();

const idOf = (rel) => crypto.createHash('sha1').update(rel).digest('hex').slice(0, 16);
const norm = (s) => (s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9 ]+/g, ' ');
const baseOf = (req) => PUBLIC_URL || `${req.headers['x-forwarded-proto'] || req.protocol}://${req.headers['x-forwarded-host'] || req.get('host')}`;
const b64 = (s) => Buffer.from(s).toString('base64url');
const unb64 = (s) => Buffer.from(s, 'base64url').toString();

// ---------- Biblioteca local (IDs estables por ruta, índice guardado en disco) ----------
function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, out);
    else if (AUDIO[path.extname(e.name).toLowerCase()]) out.push(full);
  }
  return out;
}

async function indexLibrary() {
  const mm = await import('music-metadata');
  const old = fs.existsSync(INDEX_FILE) ? JSON.parse(fs.readFileSync(INDEX_FILE, 'utf8')) : {};
  const next = new Map();
  for (const file of walk(MUSIC_DIR)) {
    const rel = path.relative(MUSIC_DIR, file);
    const id = idOf(rel);
    const mtime = fs.statSync(file).mtimeMs;
    if (old[id] && old[id].mtime === mtime) { next.set(id, old[id]); continue; }
    const ext = path.extname(file).toLowerCase();
    let t = { id, rel, mtime, title: path.basename(file, ext), artist: '', album: '', duration: 0, codec: AUDIO[ext][0] };
    try {
      const m = await mm.parseFile(file, { skipCovers: true, duration: true });
      Object.assign(t, {
        title: m.common.title || t.title,
        artist: (m.common.artists || [m.common.artist]).filter(Boolean).join(', '),
        album: m.common.album || '',
        isrc: (m.common.isrc || [])[0],
        duration: Math.round(m.format.duration || 0),
        codec: (m.format.codec || t.codec).toLowerCase().includes('alac') ? 'alac' : t.codec,
        sampleRate: m.format.sampleRate,
        bitDepth: m.format.bitsPerSample,
      });
    } catch (_) {}
    next.set(id, t);
  }
  library = next;
  fs.writeFileSync(INDEX_FILE, JSON.stringify(Object.fromEntries(library)));
  console.log(`✅ ${library.size} canciones indexadas en ${MUSIC_DIR}`);
}

function searchLocal(q) {
  const words = norm(q).split(' ').filter(Boolean);
  if (!words.length) return [];
  return [...library.values()]
    .map((t) => ({ t, hay: norm(`${t.artist} ${t.title} ${t.album}`) }))
    .filter(({ hay }) => words.every((w) => hay.includes(w)))
    .slice(0, 8)
    .map(({ t }) => ({ id: `l.${t.id}`, title: t.title, artist: t.artist, album: t.album, duration: t.duration, isrc: t.isrc, audioQuality: LOSSLESS.has(t.codec) ? 'LOSSLESS' : 'HIGH' }));
}

// ---------- SoundCloud / YouTube con yt-dlp ----------
function ytdlp(args, timeout = 15000) {
  return new Promise((resolve) => {
    if (!ENABLE_YTDLP) return resolve('');
    execFile('yt-dlp', [...args, '--no-warnings'], { timeout, maxBuffer: 8 << 20 }, (err, out) => resolve(err ? '' : out));
  });
}

async function searchRemote(q, prefix) {
  const out = await ytdlp([`${prefix}5:${q}`, '--dump-json', '--flat-playlist']);
  return out.trim().split('\n').filter(Boolean).flatMap((line) => {
    try {
      const i = JSON.parse(line);
      const page = i.webpage_url || i.url;
      if (!page) return [];
      return [{ id: `r.${b64(page)}`, title: i.title, artist: i.uploader || i.channel || '', duration: Math.round(i.duration || 0), audioQuality: 'HIGH' }];
    } catch (_) { return []; }
  });
}

const resolved = new Map(); // página -> { url, headers, until }
async function directUrl(page) {
  const hit = resolved.get(page);
  if (hit && hit.until > Date.now()) return hit;
  const out = await ytdlp(['-f', 'bestaudio[ext=m4a]/bestaudio', '-J', page], 25000);
  if (!out) return null;
  const info = JSON.parse(out);
  const f = info.requested_formats ? info.requested_formats[0] : info;
  const entry = { url: f.url, headers: f.http_headers || {}, ext: f.ext, until: Date.now() + 25 * 60 * 1000 };
  resolved.set(page, entry);
  return entry;
}

// ---------- Rutas ----------
app.get('/manifest.json', (req, res) => res.json({
  id: 'saerling-server',
  name: 'Mi servidor',
  version: '2.0',
  description: ENABLE_YTDLP ? 'Tu biblioteca local (lossless) + SoundCloud' : 'Tu biblioteca local (lossless)',
  author: 'Saerling',
  resources: ['search', 'stream'],
  settings: [{ key: 'extra', label: 'Buscar también en', default: 'soundcloud', options: ['soundcloud', 'youtube', 'ninguno'] }],
}));

app.get('/search', async (req, res) => {
  const q = String(req.query.q || '').trim();
  const local = searchLocal(q);
  const extra = String(req.query.extra || 'soundcloud');
  let remote = [];
  if (q && ENABLE_YTDLP && local.length < 3 && extra !== 'ninguno') {
    remote = await searchRemote(q, extra === 'youtube' ? 'ytsearch' : 'scsearch');
  }
  res.json({ tracks: [...local, ...remote].slice(0, 10) });
});

app.get('/stream/:id', async (req, res) => {
  const base = baseOf(req);
  const [kind, key] = [req.params.id.slice(0, 1), req.params.id.slice(2)];
  if (kind === 'l') {
    const t = library.get(key);
    if (!t) return res.status(404).json({ error: 'no encontrada' });
    return res.json({ url: `${base}/file/${t.id}`, format: t.codec, codec: t.codec, quality: LOSSLESS.has(t.codec) ? 'LOSSLESS' : 'HIGH', sampleRate: t.sampleRate, bitDepth: t.bitDepth });
  }
  if (kind === 'r' && ENABLE_YTDLP) {
    const d = await directUrl(unb64(key));
    if (!d) return res.status(502).json({ error: 'yt-dlp no pudo resolverla' });
    return res.json({ url: `${base}/proxy/${key}`, codec: d.ext === 'm4a' ? 'aac' : (d.ext === 'mp3' ? 'mp3' : 'opus'), quality: 'HIGH' });
  }
  res.status(404).json({ error: 'no encontrada' });
});

// Archivo local con soporte de Range (adelantar/retroceder al instante)
app.get('/file/:id', (req, res) => {
  const t = library.get(req.params.id);
  const file = t && path.join(MUSIC_DIR, t.rel);
  if (!file || !fs.existsSync(file)) return res.sendStatus(404);
  res.type(AUDIO[path.extname(file).toLowerCase()][1]);
  res.sendFile(file, { acceptRanges: true, cacheControl: false });
});

// Proxy: el servidor descarga y reenvía el audio. Así el enlace de YouTube/SoundCloud
// no queda atado a la IP del servidor y funciona también por datos móviles/Tailscale.
app.get('/proxy/:key', async (req, res) => {
  const page = unb64(req.params.key);
  for (let attempt = 0; attempt < 2; attempt++) {
    const d = await directUrl(page);
    if (!d) return res.sendStatus(502);
    const headers = { ...d.headers };
    if (req.headers.range) headers.Range = req.headers.range;
    const up = await fetch(d.url, { headers }).catch(() => null);
    if (up && (up.status === 403 || up.status === 410) && attempt === 0) { resolved.delete(page); continue; }
    if (!up || !up.body) return res.sendStatus(502);
    res.status(up.status);
    for (const h of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
      const v = up.headers.get(h);
      if (v) res.setHeader(h, v);
    }
    const body = Readable.fromWeb(up.body);
    req.on('close', () => body.destroy());
    return body.pipe(res);
  }
});

app.get('/health', (req, res) => res.json({ ok: true, canciones: library.size, ytdlp: ENABLE_YTDLP, uptime: Math.round(process.uptime()) }));
app.post('/reindex', async (req, res) => { await indexLibrary(); res.json({ ok: true, canciones: library.size }); });

app.listen(PORT, '0.0.0.0', async () => {
  const ip = Object.values(os.networkInterfaces()).flat().find((i) => i && i.family === 'IPv4' && !i.internal);
  console.log(`🎵 Servidor listo: http://${ip ? ip.address : 'localhost'}:${PORT}  (pega este enlace en Ajustes › Módulos)`);
  await indexLibrary();
  setInterval(() => indexLibrary().catch(console.error), 30 * 60 * 1000);
});
