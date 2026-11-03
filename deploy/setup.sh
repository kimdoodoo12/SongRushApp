#!/usr/bin/env bash
# One-time server setup for SongRush on Ubuntu 24.04. Safe to run again.
#
#   sudo bash setup.sh <hostname>
#
# Run it as the login user (normally ubuntu) through sudo, from a copy of the
# deploy/ folder: the login user ($SUDO_USER) becomes the owner of the upload
# folders, so upload.sh can write to them directly.
set -euo pipefail

usage() {
    echo "usage: sudo bash setup.sh <hostname>   (for example game.example.com)" >&2
}

if [ "$#" -lt 1 ] || [ -z "$1" ]; then
    usage
    exit 2
fi
host=$1
if ! [[ $host =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
    echo "setup.sh: '$host' is not a hostname" >&2
    usage
    exit 2
fi
if [ "$(id -u)" -ne 0 ]; then
    echo "setup.sh: run it with sudo" >&2
    exit 1
fi
login_user=${SUDO_USER:-}
if [ -z "$login_user" ] || [ "$login_user" = "root" ]; then
    echo "setup.sh: run it with sudo from the login user (for example ubuntu), not as root" >&2
    exit 1
fi
login_group=$(id -gn "$login_user")

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
for f in songrush.service Caddyfile; do
    if [ ! -f "$here/$f" ]; then
        echo "setup.sh: $here/$f is missing; copy the whole deploy/ folder" >&2
        exit 1
    fi
done

export DEBIAN_FRONTEND=noninteractive

echo "== Java 17"
apt-get update
apt-get install -y openjdk-17-jre-headless

echo "== Caddy (official apt repository)"
caddy_key=/usr/share/keyrings/caddy-stable-archive-keyring.gpg
caddy_list=/etc/apt/sources.list.d/caddy-stable.list
if [ ! -f "$caddy_key" ] || [ ! -f "$caddy_list" ]; then
    apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | gpg --batch --yes --dearmor -o "$caddy_key"
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > "$caddy_list"
    chmod o+r "$caddy_key" "$caddy_list"
    apt-get update
fi
apt-get install -y caddy

echo "== 1 GB swap"
if [ ! -f /swapfile ]; then
    fallocate -l 1G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
fi
if ! swapon --show=NAME --noheadings | grep -x /swapfile > /dev/null; then
    swapon /swapfile
fi
if ! grep -q '^/swapfile ' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== songrush user"
if ! id -u songrush > /dev/null 2>&1; then
    useradd --system --user-group --no-create-home \
        --home-dir /opt/songrush/run --shell /usr/sbin/nologin songrush
fi

echo "== /opt/songrush"
install -d -o root -g root -m 755 /opt/songrush
for d in app web data; do
    install -d -o "$login_user" -g "$login_group" -m 755 "/opt/songrush/$d"
    # Anything already uploaded stays readable by the songrush user.
    chmod -R a+rX "/opt/songrush/$d"
done
install -d -o songrush -g songrush -m 755 /opt/songrush/run

echo "== systemd unit"
install -o root -g root -m 644 "$here/songrush.service" /etc/systemd/system/songrush.service
systemctl daemon-reload
systemctl enable songrush
# Not started here: upload.sh puts the jar in place and restarts the service.

echo "== Caddyfile for $host"
tmp=$(mktemp)
sed "s/__SONGRUSH_HOST__/$host/g" "$here/Caddyfile" > "$tmp"
install -o root -g root -m 644 "$tmp" /etc/caddy/Caddyfile
rm -f "$tmp"
caddy validate --adapter caddyfile --config /etc/caddy/Caddyfile
systemctl reload caddy

echo "done. Next, from the PC: bash deploy/upload.sh $login_user@$host"
