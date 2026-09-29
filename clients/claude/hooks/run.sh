#!/bin/sh
# Runs the ai-comments binary for this machine: a local build in bin/ when there is one, otherwise the release binary
# for this plugin's version, downloaded once and checked against checksums.txt. Without a binary, hooks stay silent
# instead of failing, and the rule is still injected, only without the generated list of file types.
root="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) target=macos-arm64 ;;
    Linux-x86_64) target=linux-x64 ;;
    Linux-aarch64 | Linux-arm64) target=linux-arm64 ;;
    *) target= ;;
esac

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# Prints the path of the cached release binary, downloading it first when needed.
downloaded() {
    name="ai-comments-$target"
    expected="$(grep " $name\$" "$root/checksums.txt" 2>/dev/null | cut -d' ' -f1)"
    version="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$root/.claude-plugin/plugin.json")"
    [ -n "$expected" ] && [ -n "$version" ] && command -v curl >/dev/null 2>&1 || return 1
    dir="${CLAUDE_PLUGIN_DATA:-${XDG_CACHE_HOME:-$HOME/.cache}/ai-comments}/$version"
    cached="$dir/$name"
    [ -x "$cached" ] && echo "$cached" && return 0
    mkdir -p "$dir" || return 1
    partial="$cached.$$"
    if curl -fsL --connect-timeout 5 --max-time 30 -o "$partial" \
        "https://github.com/war-in/ai-comments/releases/download/v$version/$name" &&
        [ "$(sha256 "$partial")" = "$expected" ]; then
        chmod +x "$partial" && mv -f "$partial" "$cached" && echo "$cached"
    else
        rm -f "$partial"
        return 1
    fi
}

if [ -n "$target" ]; then
    exe="$root/bin/ai-comments-$target"
    [ -x "$exe" ] || exe="$(downloaded)"
    [ -n "$exe" ] && exec "$exe" "$@"
fi
if [ "$1" = "rule" ] && [ -f "$2" ]; then
    sed 's/{{SUPPORTED_FILE_TYPES}}/the file types listed in the ai-comments README (no ai-comments binary for this machine, so Claude commits are not stripped)/' "$2"
fi
exit 0
