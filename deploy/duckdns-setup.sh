#!/usr/bin/env bash
# Points a DuckDNS name at this server, now and every 5 minutes. Safe to run again.
#
#   sudo bash duckdns-setup.sh <name>        (for <name>.duckdns.org)
#
# setup.sh runs it for a .duckdns.org hostname; it can also run on its own.
# The token is typed at a hidden prompt, so it never appears in a command
# line, in shell history or in output. It is stored only in
# /etc/songrush/duckdns.curl (mode 600, root), which curl reads with -K.
#
# SONGRUSH_ROOT (default empty) and SONGRUSH_TTY (default /dev/tty) exist for
# the tests; on a server leave both unset.
set -euo pipefail

usage() {
    echo "usage: sudo bash duckdns-setup.sh <name>   (for <name>.duckdns.org)" >&2
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi
name=${1,,}
if ! [[ $name =~ ^[a-z0-9-]{1,63}$ ]]; then
    echo "duckdns-setup.sh: '$1' is not a DuckDNS name (letters, digits and '-', at most 63)" >&2
    usage
    exit 2
fi

root=${SONGRUSH_ROOT:-}
tty=${SONGRUSH_TTY:-/dev/tty}
if ! { : < "$tty"; } 2> /dev/null; then
    echo "duckdns-setup.sh: cannot read the keyboard; run it in an interactive ssh session (ssh -t)" >&2
    exit 2
fi

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
for f in duckdns.service duckdns.timer; do
    if [ ! -f "$here/$f" ]; then
        echo "duckdns-setup.sh: $here/$f is missing; copy the whole deploy/ folder" >&2
        exit 1
    fi
done

printf 'Paste the DuckDNS token for %s.duckdns.org (it is not shown), then press Enter: ' "$name" >&2
token=
IFS= read -rs token < "$tty" || true
echo >&2
# Trim surrounding whitespace, including a CR from a Windows paste.
token=${token#"${token%%[![:space:]]*}"}
token=${token%"${token##*[![:space:]]}"}
if ! [[ $token =~ ^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$ ]]; then
    echo "duckdns-setup.sh: that is not a DuckDNS token; copy it again from the DuckDNS page" >&2
    exit 2
fi

echo "== DuckDNS settings"
conf_dir=$root/etc/songrush
curl_file=$conf_dir/duckdns.curl
umask 077
mkdir -p "$conf_dir"
# Written to a temp file next to the target, then moved into place, so a
# failed run never leaves a half-written file.
curl_tmp=$(mktemp "$curl_file.XXXXXX")
trap 'rm -f "$curl_tmp"' EXIT
printf 'url = "https://www.duckdns.org/update?domains=%s&token=%s&ip="\n' "$name" "$token" > "$curl_tmp"
mv -f "$curl_tmp" "$curl_file"
trap - EXIT
token=
umask 022

echo "== DuckDNS timer"
units=$root/etc/systemd/system
mkdir -p "$units"
cp -f "$here/duckdns.service" "$here/duckdns.timer" "$units/"
systemctl daemon-reload
systemctl enable --now duckdns.timer

echo "== DuckDNS update"
if ! reply=$(curl -fsS -K "$curl_file"); then
    echo "duckdns-setup.sh: could not reach DuckDNS; check the server's internet connection, then run it again" >&2
    exit 1
fi
reply=${reply#"${reply%%[![:space:]]*}"}
reply=${reply%"${reply##*[![:space:]]}"}
if [ "$reply" != "OK" ]; then
    echo "duckdns-setup.sh: DuckDNS did not accept the update: the name or token is wrong." >&2
    echo "Check <name> and the token on the DuckDNS page, then run it again." >&2
    exit 1
fi
echo "DuckDNS: $name.duckdns.org now points to this server"
