#!/bin/sh
# Empaqueta module/ en build/liquid-glass.moodymodule.zip
set -e
cd "$(dirname "$0")"
python3 -c "import json; json.load(open('module/manifest.json'))"
rm -rf build && mkdir -p build
cd module && zip -q -X -r ../build/liquid-glass.moodymodule.zip manifest.json glass.agsl icon.png banner.webp
echo "build/liquid-glass.moodymodule.zip"
