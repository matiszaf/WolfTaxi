#!/usr/bin/env bash
set -euo pipefail
VHOST="${1:-/etc/apache2/sites-available/wolftaxi.starcore.pl.conf}"
if [[ ! -f "$VHOST" ]]; then
  echo "[WolfTaxi] Nie znaleziono vhosta: $VHOST"
  exit 0
fi
sudo cp "$VHOST" "$VHOST.bak-wolftaxi-081-$(date +%Y%m%d-%H%M%S)"
sudo python3 - "$VHOST" <<'PY2'
from pathlib import Path
import re,sys
p=Path(sys.argv[1]); s=p.read_text()
blocks=[
 ('/track-static/', 'http://127.0.0.1:8081/track-static/'),
 ('/track/', 'http://127.0.0.1:8081/track/'),
]
missing=[]
for route,target in blocks:
    if not re.search(r'^\s*ProxyPass\s+'+re.escape(route)+r'\s+',s,re.M):
        missing.append(f'    ProxyPass        {route} {target}\n    ProxyPassReverse {route} {target}\n')
if missing:
    if '</VirtualHost>' not in s: raise SystemExit('Nie znaleziono </VirtualHost>.')
    s=s.replace('</VirtualHost>','\n'+''.join(missing)+'\n</VirtualHost>',1)
    p.write_text(s)
    print('[WolfTaxi] Dodano: '+', '.join(route for route,target in blocks if any(route in x for x in missing)))
else:
    print('[WolfTaxi] Proxy tracking już skonfigurowany.')
PY2
sudo apache2ctl -t
sudo systemctl reload apache2
