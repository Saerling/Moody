#!/usr/bin/env bash
# Crea la clave con la que se firman las versiones de Moody (una sola vez) y guarda su contraseña
# como secreto del repositorio. Con ella cada APK nuevo se instala encima del anterior, y la app
# se puede actualizar sola desde Ajustes.
#
#   ./scripts/crear-clave.sh
#
# Necesita openssl y gh (con sesión iniciada). No la vuelvas a crear: una clave nueva obliga a
# desinstalar la app para instalar la siguiente versión.
set -euo pipefail
cd "$(dirname "$0")/.."

dest="app/keystore/release.keystore"
if [ -e "$dest" ]; then
  echo "Ya existe $dest; no la reemplazo." >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pw="$(openssl rand -hex 24)"

openssl req -x509 -newkey rsa:4096 -nodes -days 10000 \
  -subj "/CN=Moody/O=Saerling" -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
mkdir -p "$(dirname "$dest")"
openssl pkcs12 -export -name moody -inkey "$tmp/key.pem" -in "$tmp/cert.pem" \
  -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256 \
  -passout "pass:$pw" -out "$dest"

printf '%s' "$pw" | gh secret set MOODY_KEYSTORE_PASSWORD
echo "Listo: $dest creada y secreto MOODY_KEYSTORE_PASSWORD guardado en GitHub."
echo "Haz commit de $dest (está cifrada con esa contraseña)."
