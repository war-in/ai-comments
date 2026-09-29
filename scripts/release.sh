#!/bin/sh
# Sets every artifact to one version. Used by the release workflow.
set -eu
version="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"

sed -i.bak "s/^aiCommentsVersion=.*/aiCommentsVersion=$version/" "$root/gradle.properties" && rm "$root/gradle.properties.bak"
python3 - "$version" "$root/clients/claude/.claude-plugin/plugin.json" "$root/clients/vscode/package.json" <<'PY'
import json, sys
version = sys.argv[1]
for path in sys.argv[2:]:
    manifest = json.load(open(path))
    manifest["version"] = version
    open(path, "w").write(json.dumps(manifest, indent=2) + "\n")
PY
