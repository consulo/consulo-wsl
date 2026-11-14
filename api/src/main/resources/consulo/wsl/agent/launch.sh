set -eu
umask 077
binary=$1
port=$2
root=$3
permissions=${4:-*}
mkdir -p "$root/sessions"
session=$(mktemp -d "$root/sessions/run.XXXXXXXXXX")
child=
guardian=
cleanup() {
    trap - EXIT HUP INT TERM
    if test -n "$guardian"; then kill "$guardian" 2>/dev/null || :; wait "$guardian" 2>/dev/null || :; fi
    if test -n "$child"; then kill -TERM "$child" 2>/dev/null || :; wait "$child" 2>/dev/null || :; fi
    rm -rf "$session"
}
trap cleanup EXIT
trap 'exit 143' HUP INT TERM
# The token travels only over the launcher pipe, never in argv or on disk.
IFS= read -r AGENT_TOKEN
test -n "$AGENT_TOKEN"
export AGENT_TOKEN
unset AGENT_TLS AGENT_TLS_CERT AGENT_TLS_KEY
# dash gives an asynchronous list stdin=/dev/null before its explicit redirections, so `<&0` there would
# duplicate /dev/null: keep the launcher pipe on fd 3 for the guardian.
exec 3<&0
"$binary" --host 127.0.0.1 --port "$port" --tls --workspace "$session/workspace" --permissions "$permissions" </dev/null 3<&- &
child=$!
# Closing the Java lease (or losing the IDE) closes this pipe and stops this child only.
( IFS= read -r ignored || :; kill -TERM "$child" 2>/dev/null || : ) <&3 &
guardian=$!
status=0
wait "$child" || status=$?
child=
exit "$status"
