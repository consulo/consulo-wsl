set -eu
umask 077
root=$1
digest=$2
mode=$3
directory=$root/bin/$digest
binary=$directory/remote-agent.exe
case "$digest" in ''|*[!0-9a-f]*) exit 2 ;; esac
test "${#digest}" -eq 64
command -v sha256sum >/dev/null 2>&1 || { echo 'WSL needs sha256sum for deployment' >&2; exit 1; }

valid() {
    test -f "$binary" && test -x "$binary" || return 1
    actual=$(sha256sum < "$binary")
    test "${actual%% *}" = "$digest"
}

if test "$mode" = check; then
    if valid; then printf 'CONSULO_WSL_CACHED\n'; else printf 'CONSULO_WSL_MISSING\n'; fi
    exit 0
fi
test "$mode" = upload
mkdir -p "$directory"
# mktemp + rename also permits simultaneous deployers of the same immutable binary.
temporary=$(mktemp "$directory/.upload.XXXXXXXXXX")
trap 'rm -f "$temporary"' EXIT
trap 'exit 143' HUP INT TERM
cat > "$temporary"
actual=$(sha256sum < "$temporary")
test "${actual%% *}" = "$digest" || { echo 'Agent checksum mismatch after transfer' >&2; exit 1; }
chmod 700 "$temporary"
mv -f "$temporary" "$binary"
printf 'CONSULO_WSL_DEPLOYED\n'
