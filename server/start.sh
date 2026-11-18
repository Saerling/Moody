#!/data/data/com.termux/files/usr/bin/bash
# Arranca el servidor en Termux sin que el teléfono se duerma
cd "$(dirname "$0")"
command -v termux-wake-lock >/dev/null && termux-wake-lock
[ -f .env ] || cp .env.example .env
[ -d node_modules ] || npm install --omit=dev
exec node server.js
