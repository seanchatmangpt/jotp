#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FIRST="$TMP/first"
SECOND="$TMP/second"
mkdir -p "$FIRST" "$SECOND"

python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$FIRST" --receipt "$FIRST/local-replay.json" \
  > "$TMP/first.json"
python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$SECOND" --receipt "$SECOND/local-replay.json" \
  > "$TMP/second.json"

sha256sum "$FIRST/src/main/java/io/github/seanchatmangpt/jotp/JotpCapabilities.java" \
  | sed "s#$FIRST/##" > "$TMP/first.sha256"
sha256sum "$SECOND/src/main/java/io/github/seanchatmangpt/jotp/JotpCapabilities.java" \
  | sed "s#$SECOND/##" > "$TMP/second.sha256"
diff -u "$TMP/first.sha256" "$TMP/second.sha256"

mkdir -p "$TMP/classes"
javac -d "$TMP/classes" \
  "$FIRST/src/main/java/io/github/seanchatmangpt/jotp/JotpCapabilities.java"
java -cp "$TMP/classes" io.github.seanchatmangpt.jotp.JotpCapabilities \
  | tee "$TMP/probe.out"
grep -q '^SUBJECT_ALIVE capabilities=30 gaps=4$' "$TMP/probe.out"

cp "$ROOT/ontology/jotp-capabilities.ttl" "$TMP/invalid-standing.ttl"
python3 - "$TMP/invalid-standing.ttl" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
path.write_text(text.replace('cap:standing "PARTIAL_ALIVE"', 'cap:standing "CROWNED"', 1))
PY
set +e
python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$TMP/invalid-output" --ontology "$TMP/invalid-standing.ttl" \
  > "$TMP/invalid-standing.out" 2> "$TMP/invalid-standing.err"
status=$?
set -e
[[ "$status" -eq 42 ]]
grep -q '^ONTOLOGY_STANDING_REFUSED:' "$TMP/invalid-standing.err"

cp "$ROOT/ggen.toml" "$TMP/duplicate-owner.toml"
cat >> "$TMP/duplicate-owner.toml" <<'TOML'

[[generation.rules]]
name = "illegal-second-owner"
query = { file = "ontology/queries/capabilities.rq" }
template = { file = "templates/JotpCapabilities.java.tera" }
output_file = "src/main/java/io/github/seanchatmangpt/jotp/JotpCapabilities.java"
skip_empty = false
mode = "Overwrite"
TOML
set +e
python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$TMP/duplicate-output" --config "$TMP/duplicate-owner.toml" \
  > "$TMP/duplicate-owner.out" 2> "$TMP/duplicate-owner.err"
status=$?
set -e
[[ "$status" -eq 43 ]]
grep -q '^DUPLICATE_OUTPUT_OWNER_REFUSED:' "$TMP/duplicate-owner.err"

python3 - "$ROOT/legacy/jotp-observable-contracts.json" "$TMP/legacy-missing.json" <<'PY'
from pathlib import Path
import json, sys
source = json.loads(Path(sys.argv[1]).read_text())
source['observations'] = [x for x in source['observations'] if x['id'] != 'generation-ownership']
Path(sys.argv[2]).write_text(json.dumps(source))
PY
set +e
python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$TMP/legacy-output" --legacy "$TMP/legacy-missing.json" \
  > "$TMP/legacy.out" 2> "$TMP/legacy.err"
status=$?
set -e
[[ "$status" -eq 52 ]]
grep -q '^LEGACY_CONTRACT_CLOSURE_REFUSED:' "$TMP/legacy.err"

mkdir -p "$ROOT/receipts"
python3 "$ROOT/tools/manufacture_capabilities.py" \
  --root "$ROOT" --output-root "$ROOT" --receipt "$ROOT/receipts/local-replay.json" \
  > "$TMP/final.json"

python3 - "$ROOT/receipts/local-replay.json" <<'PY'
from pathlib import Path
import json, sys
receipt_path = Path(sys.argv[1])
receipt = json.loads(receipt_path.read_text())
receipt['verification'] = {
    'projectionReplay': 'REPLAY_MATCH',
    'javaProbe': 'SUBJECT_ALIVE capabilities=30 gaps=4',
    'negativeControls': [
        'ONTOLOGY_STANDING_REFUSED',
        'DUPLICATE_OUTPUT_OWNER_REFUSED',
        'LEGACY_CONTRACT_CLOSURE_REFUSED',
    ],
    'commands': [
        'python3 tools/manufacture_capabilities.py (twice)',
        'diff -u first.sha256 second.sha256',
        'javac JotpCapabilities.java',
        'java io.github.seanchatmangpt.jotp.JotpCapabilities',
    ],
}
receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
PY

echo "VERIFIER_ALIVE replay=REPLAY_MATCH subject=capability-admission negatives=3"
