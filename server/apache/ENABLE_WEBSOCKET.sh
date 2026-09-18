#!/usr/bin/env bash
set -euo pipefail
sudo a2enmod proxy proxy_http proxy_wstunnel headers
sudo apache2ctl -t
sudo systemctl reload apache2
echo '[WolfTaxi] WebSocket proxy: dodaj /ws do vhosta wolftaxi.starcore.pl (patrz wolftaxi.starcore.pl.conf.example).'
