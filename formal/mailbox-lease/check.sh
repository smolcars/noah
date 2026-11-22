#!/usr/bin/env bash
# Checks the mailbox lease model: the covered code still matches coverage.txt,
# MailboxLease.cfg passes, and every bug-* and finding-* config fails on the
# invariant its expectation names. TLC's exit status must agree with its log
# (0 for a pass, 12 for a safety violation), so a crashed or killed run fails.
# Any other outcome, or drift, exits non-zero.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
repo_root="$(git rev-parse --show-toplevel)"

TLA_VERSION="1.7.4"
TLA_SHA256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
tools_dir="$repo_root/formal/.tools"
jar="$tools_dir/tla2tools-$TLA_VERSION.jar"

# config file -> "pass", or the invariant TLC must report as violated.
expectations=(
  "MailboxLease.cfg=pass"
  "bug-86b883f05-self-reclaim.cfg=NoClaimOfLiveLease"
  "bug-no-expiry-guard.cfg=CheckPassesOnlyIfLiveSinceSample"
  "bug-3ed2b04c8-no-shutdown-release.cfg=StoppedWorkerHoldsNoLease"
  "finding-duplicate-push.cfg=AtMostOnePushPerCheckpoint"
  "finding-checkpoint-moves-back.cfg=CheckpointNeverMovesBack"
  "finding-stale-check.cfg=CheckOnlyUnderLiveLease"
)
# TLC exit statuses, from tlc2.output.EC$ExitStatus in the pinned jar.
TLC_SUCCESS=0
TLC_VIOLATION_SAFETY=12

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

covered=(
  server/src/mailbox_worker.rs
  server/src/db/mailbox_authorization_repo.rs
  server/migrations/0010_add_mailbox_authorizations.sql
  server/migrations/0011_add_mailbox_worker_state.sql
  server/migrations/0012_add_mailbox_worker_leases.sql
)

# Run only after you have confirmed MailboxLease.tla describes the covered code as it is now.
if [[ "${1:-}" == "--record" ]]; then
  {
    echo "# Files MailboxLease.tla describes, with their git blob hashes (git hash-object)."
    echo "# Rewrite with: formal/mailbox-lease/check.sh --record"
    echo "# synced-at $(git -C "$repo_root" rev-parse HEAD)"
    for path in "${covered[@]}"; do
      echo "$(git -C "$repo_root" hash-object "$path") $path"
    done
  } > coverage.txt
  echo "Recorded coverage.txt at $(git -C "$repo_root" rev-parse --short HEAD)."
  exit 0
fi

drift=0
for path in "${covered[@]}"; do
  if [[ -z "$(awk -v p="$path" '$2 == p' coverage.txt)" ]]; then
    echo "DRIFT: $path is not recorded in coverage.txt, so nothing checks it."
    drift=1
  fi
done
while read -r blob path; do
  [[ -z "$blob" || "$blob" == \#* ]] && continue
  actual="$(git -C "$repo_root" hash-object "$path")"
  if [[ "$actual" != "$blob" ]]; then
    echo "DRIFT: $path changed since the model was last confirmed (recorded $blob, now $actual)."
    drift=1
  fi
done < coverage.txt
if [[ ! -f "$jar" ]] || [[ "$(sha256 "$jar")" != "$TLA_SHA256" ]]; then
  mkdir -p "$tools_dir"
  curl -fsSL -o "$jar.tmp" "https://github.com/tlaplus/tlaplus/releases/download/v$TLA_VERSION/tla2tools.jar"
  if [[ "$(sha256 "$jar.tmp")" != "$TLA_SHA256" ]]; then
    echo "tla2tools.jar checksum mismatch; refusing to run it." >&2
    rm -f "$jar.tmp"
    exit 1
  fi
  mv "$jar.tmp" "$jar"
fi

out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT
failed=0
findings=()

for entry in "${expectations[@]}"; do
  cfg="${entry%%=*}"
  expected="${entry#*=}"
  log="$out_dir/$cfg.out"
  start=$SECONDS
  status=0
  java -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers auto \
    -metadir "$out_dir/states-$cfg" -config "$cfg" MailboxLease.tla >"$log" 2>&1 || status=$?
  took=$((SECONDS - start))
  counts="$(grep -E 'distinct states found' "$log" | tail -1 || true)"
  violated="$(sed -nE 's/^Error: Invariant ([A-Za-z]+) is violated\.$/\1/p' "$log" | head -1)"

  if [[ "$expected" == "pass" ]]; then
    if [[ "$status" == "$TLC_SUCCESS" ]] && grep -q "No error has been found" "$log"; then
      echo "PASS  $cfg: no invariant violated. $counts (${took}s)"
    else
      echo "FAIL  $cfg: expected no violation, got ${violated:-a TLC error} (exit $status)."
      cat "$log"
      failed=1
    fi
  elif [[ "$status" == "$TLC_VIOLATION_SAFETY" && "$violated" == "$expected" ]]; then
    echo "PASS  $cfg: $expected is violated, as expected. $counts (${took}s)"
    [[ "$cfg" == finding-* ]] && findings+=("$cfg")
  else
    echo "FAIL  $cfg: expected $expected to be violated, got ${violated:-no violation} (exit $status)."
    cat "$log"
    failed=1
  fi
done

# Known findings at master stay visible: print each counterexample.
for cfg in "${findings[@]}"; do
  echo
  echo "KNOWN FINDING ($cfg): counterexample from TLC. See README.md for it in the code's words."
  sed -n '/^Error: The behavior up to this point is:/,/^[0-9]* states generated/p' "$out_dir/$cfg.out"
done

if [[ "$drift" == 1 ]]; then
  echo
  echo "DRIFT: the mailbox lease model may no longer describe the covered code (files above)."
  echo "Follow .agents/skills/verify-mailbox-lease/SKILL.md: decide whether the change"
  echo "touches the lease, update MailboxLease.tla if it does, then run"
  echo "formal/mailbox-lease/check.sh --record and rerun this check."
  failed=1
fi

exit "$failed"
