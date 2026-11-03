#!/usr/bin/env bash
# Upload the build, the catalog and the audio cache to the SongRush server,
# then restart the game. Runs on the owner's PC in Git Bash with OpenSSH only.
#
#   bash deploy/upload.sh [--dry-run] <user@host> [<ssh key>]
#
# Audio files whose remote size equals the local size are skipped, a smaller
# remote file is resumed (reput), and everything else is sent in full. One
# sftp batch does all of it; one ssh then swaps in the jar, restarts, and
# waits for /health. On failure it prints the command that restores the last
# jar that answered /health.
set -euo pipefail

usage() {
    echo "usage: bash deploy/upload.sh [--dry-run] <user@host> [<ssh key>]" >&2
}

dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=1
    shift
fi
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || [ -z "$1" ] || [[ $1 == -* ]]; then
    usage
    exit 2
fi
host=$1
key=${2:-}

# Builtins only up to here, so a missing tool is reported before anything runs.
for tool in ssh sftp; do
    if ! command -v "$tool" > /dev/null 2>&1; then
        echo "upload.sh: '$tool' was not found; install OpenSSH (Git for Windows ships it)" >&2
        exit 3
    fi
done

script=${BASH_SOURCE[0]}
case $script in
    */*) script_dir=${script%/*} ;;
    *) script_dir=. ;;
esac
cd "$script_dir/.."

JAR=backend/build/libs/backend-0.0.1-SNAPSHOT.jar
DIST=frontend/dist
CATALOG=data/catalog.json
AUDIO=data/audio-cache
AUDIO_INDEX=$AUDIO/index.json
REMOTE=/opt/songrush

if [ ! -f "$JAR" ]; then
    echo "upload.sh: $JAR is missing; build it with '.\gradlew.bat build' in backend/" >&2
    exit 4
fi
if [ ! -f "$DIST/index.html" ]; then
    echo "upload.sh: $DIST/index.html is missing; build it with 'npm run build' in frontend/" >&2
    exit 4
fi
if [ ! -f "$CATALOG" ] || [ ! -f "$AUDIO_INDEX" ]; then
    echo "upload.sh: $CATALOG or $AUDIO_INDEX is missing; run the data tools first" >&2
    exit 4
fi

ssh_opts=()
if [ -n "$key" ]; then
    ssh_opts=(-i "$key")
fi

# A path as one sftp argument. Inside double quotes sftp takes * ? [ ]
# literally, so only the double quote itself needs escaping.
q() {
    local s=${1//\"/\\\"}
    printf '"%s"' "$s"
}

# Remote audio sizes: "audio-cache/<name>" -> bytes. A dry run assumes none.
declare -A remote_size=()
if [ "$dry_run" -eq 0 ]; then
    listing=$(ssh "${ssh_opts[@]}" "$host" \
        'cd /opt/songrush/data && find audio-cache -type f -printf "%s %p\n" 2>/dev/null || true')
    while read -r size path; do
        path=${path%$'\r'}
        if [ -n "$path" ] && [[ $size =~ ^[0-9]+$ ]]; then
            remote_size[$path]=$size
        fi
    done <<< "$listing"
fi

batch_text() {
    local d f size name have
    echo "-mkdir $(q "$REMOTE/app")"
    echo "-mkdir $(q "$REMOTE/web")"
    echo "-mkdir $(q "$REMOTE/data")"
    echo "-mkdir $(q "$REMOTE/data/audio-cache")"
    while IFS= read -r d; do
        echo "-mkdir $(q "$REMOTE/web/$d")"
    done < <(find "$DIST" -mindepth 1 -type d -printf '%P\n' | LC_ALL=C sort)
    echo "put $(q "$JAR") $(q "$REMOTE/app/songrush.jar.new")"

    while read -r size name; do
        have=${remote_size["audio-cache/$name"]:-}
        if [ -z "$have" ] || [ "$have" -gt "$size" ]; then
            echo "put $(q "$AUDIO/$name") $(q "$REMOTE/data/audio-cache/$name")"
        elif [ "$have" -lt "$size" ]; then
            echo "reput $(q "$AUDIO/$name") $(q "$REMOTE/data/audio-cache/$name")"
        fi
    done < <(find "$AUDIO" -maxdepth 1 -type f -name '*.ogg' -printf '%s %f\n' | LC_ALL=C sort -k2)

    echo "put $(q "$AUDIO_INDEX") $(q "$REMOTE/data/audio-cache/index.json")"
    echo "put $(q "$CATALOG") $(q "$REMOTE/data/catalog.json")"
    # index.html last, so it never points at assets that are not there yet.
    while IFS= read -r f; do
        if [ "$f" != "index.html" ]; then
            echo "put $(q "$DIST/$f") $(q "$REMOTE/web/$f")"
        fi
    done < <(find "$DIST" -type f -printf '%P\n' | LC_ALL=C sort)
    echo "put $(q "$DIST/index.html") $(q "$REMOTE/web/index.html")"
}

# One line of remote shell code. It is single-quoted so $i expands on the
# server; only the fixed remote root is spliced in. The rename never
# overwrites the jar a running JVM has open. /health is polled 60 times, 2 s
# apart; the first success saves the jar as songrush.jar.good (the last jar
# that answered), otherwise the journal is printed and the command fails.
FINAL='cd '$REMOTE'/app && mv -f songrush.jar.new songrush.jar && sudo systemctl restart songrush && for i in $(seq 1 60); do if curl -fsS -m 5 http://127.0.0.1:8080/health > /dev/null 2>&1; then cp -p songrush.jar songrush.jar.good && echo healthy; exit $?; fi; sleep 2; done; echo "songrush did not answer /health; last 50 journal lines:"; sudo journalctl -u songrush -n 50 --no-pager; exit 1'
RESTORE="cp -p $REMOTE/app/songrush.jar.good $REMOTE/app/songrush.jar && sudo systemctl restart songrush"

if [ "$dry_run" -eq 1 ]; then
    echo "# dry run: sftp batch for $host, assuming nothing exists remotely"
    batch_text
    echo "# then: ssh $host '$FINAL'"
    exit 0
fi

batch=$(mktemp)
trap 'rm -f "$batch"' EXIT
batch_text > "$batch"

echo "uploading to $host ..."
sftp -b "$batch" "${ssh_opts[@]}" "$host"
echo "restarting songrush on $host and waiting for /health ..."
if ssh "${ssh_opts[@]}" "$host" "$FINAL"; then
    echo "done."
    exit 0
fi
key_arg=""
if [ -n "$key" ]; then
    key_arg="-i $(printf '%q' "$key") "
fi
echo "upload.sh: songrush is not healthy on $host. To restore the last good jar (songrush.jar.good), run:" >&2
echo "ssh $key_arg$host '$RESTORE'" >&2
exit 1
