# Aesthetic Music

App de música para Android basada en [Mochy Music](https://github.com/realidkroo/Mochy-Music) (GPL-3.0). Junta en una sola app:

| Qué | Cómo |
|---|---|
| YouTube Music (buscar, reproducir, tu biblioteca) | Viene de Mochy |
| Tu música guardada en el celular | Viene de Mochy (Música local) |
| Tu servidor propio (FLAC/MP3 de tu biblioteca + SoundCloud) | Carpeta [`server/`](server) → se agrega como módulo |
| Playlists de Spotify, Apple Music y Tidal | Importar, viene de Mochy |
| Last.fm y Discord | Viene de Mochy (necesitan las claves, ver abajo) |
| Liquid Glass (estilo Convx) | Módulo [`modules/liquid-glass`](modules/liquid-glass) |
| Bit-perfect / USB DAC (estilo BitChord, LastWave) | Viene de Mochy (Audio Lab) |
| Letras palabra a palabra y Replay (estilo Prism) | Vienen de Mochy |

Se instala aparte de Mochy (`com.saerling.musichub`), así que puedes tener las dos.

## Descargar el APK

Cada push a `main` compila la app en **Actions → CI & Debug Build**. Entra a la última ejecución y baja el artefacto.

Para que funcionen Last.fm y Discord, agrega en **Settings → Secrets and variables → Actions**:
`LASTFM_API_KEY`, `LASTFM_SECRET` (crea las claves en <https://www.last.fm/api/account/create>) y `DISCORD_CLIENT_ID` (<https://discord.com/developers/applications>).

## Liquid Glass

En la app: **Ajustes → Módulos → Añadir un módulo → Liquid Glass**. Se publica solo cuando cambias algo en `modules/liquid-glass/module/`.

## Tu servidor

Hace que, cuando pones una canción, la app use **tu archivo** si lo tienes (en FLAC suena sin pérdida), y si no, lo busque en SoundCloud.

### En un celular viejo con Termux

1. Instala Termux desde [F-Droid](https://f-droid.org/packages/com.termux/) y ejecuta:
   ```bash
   pkg update && pkg install nodejs-lts git python ffmpeg -y
   termux-setup-storage
   pip install -U yt-dlp        # opcional, para SoundCloud/YouTube
   git clone https://github.com/Saerling/AestheticMusicHub --depth 1
   cd AestheticMusicHub/server && ./start.sh
   ```
2. Edita `server/.env` (carpeta de música, `ENABLE_YTDLP=true` si instalaste yt-dlp) y vuelve a correr `./start.sh`.
3. Copia la dirección que imprime (ej. `http://192.168.1.20:3000`) y pégala en la app: **Ajustes → Módulos → Añadir**.
4. Para usarlo fuera de casa, instala [Tailscale](https://tailscale.com/download) en los dos celulares y usa la IP `100.x.x.x`.
5. Para que el sistema no lo cierre: permite a Termux correr en segundo plano y sin restricciones de batería, y bloquéalo en recientes.

### En un PC o en Render

Igual: `cd server && npm install && npm start`. En Render solo tiene sentido con `ENABLE_YTDLP=true`, porque allá no tienes tu música guardada.

## Licencia

GPL-3.0, igual que Mochy. Si publicas la app, tienes que publicar también el código.
