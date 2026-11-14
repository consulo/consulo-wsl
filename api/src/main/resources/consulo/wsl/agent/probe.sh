set -eu
root=${XDG_CACHE_HOME:-"$HOME/.cache"}/consulo/wsl-agent
case "$root" in /*) ;; *) echo 'WSL cache directory must be absolute' >&2; exit 1 ;; esac
printf 'CONSULO_WSL_PROBE\t%s\t%s\n' "$(uname -m)" "$root"
