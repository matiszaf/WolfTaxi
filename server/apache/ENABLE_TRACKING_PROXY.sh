#!/usr/bin/env bash
set -euo pipefail
VHOST="${1:-/etc/apache2/sites-available/wolftaxi.starcore.pl.conf}"
if [[ ! -f "$VHOST" ]]; then
  echo "[WolfTaxi] Nie znaleziono vhosta: $VHOST — pomijam automatyczne dodanie /track/."
  exit 0
fi
if grep -Eq '^[[:space:]]*ProxyPass[[:space:]]+/track/' "$VHOST"; then
  echo '[WolfTaxi] Proxy /track/ już jest skonfigurowany.'
  exit 0
fi
sudo cp "$VHOST" "$VHOST.bak-wolftaxi-07-$(date +%Y%m%d-%H%M%S)"
sudo python3 - "$VHOST" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
block='''\n    ProxyPass        /track/ http://127.0.0.1:8081/track/\n    ProxyPassReverse /track/ http://127.0.0.1:8081/track/\n'''
anchor='    ProxyPass        /health http://127.0.0.1:8081/health\n'
if anchor in s:
    s=s.replace(anchor,block+'\n'+anchor,1)
else:
    end='</VirtualHost>'
    if end not in s: raise SystemExit('Nie znaleziono </VirtualHost>.')
    s=s.replace(end,block+'\n'+end,1)
p.write_text(s)
PY
sudo apache2ctl -t
sudo systemctl reload apache2
echo '[WolfTaxi] Proxy /track/ aktywny.'
