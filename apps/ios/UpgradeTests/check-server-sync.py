#!/usr/bin/env python3
"""Read only: require this wallet's accepted empty sync in the existing server log."""
import json
import re
import subprocess
import sys
import time

since, public_key, native_user_agent = sys.argv[1:]
assert re.fullmatch(r"0[23][0-9a-f]{64}", public_key), "Expected login public key required"
assert native_user_agent, "Expected native User-Agent required"
for attempt in range(30):
    output = subprocess.run(
        ["docker", "logs", "--since", since, "scripts-noah-server-1"],
        capture_output=True, check=True, text=True,
    )
    for line in (output.stdout + output.stderr).splitlines():
        start = line.find('{"request_id"')
        if start < 0:
            continue
        try:
            event = json.JSONDecoder().raw_decode(line[start:])[0]
        except ValueError:
            continue
        if (event.get("public_key") == public_key
                and (event.get("user_agent") or "").startswith(native_user_agent)
                and event.get("path") == "/v0/recurring_payments/sync"
                and event.get("status_code") == 200
                and event.get("context", {}).get("recurring_schedule_count") == 0):
            count = subprocess.run(
                ["docker", "exec", "scripts-postgres-1", "sh", "-c",
                 'psql -U "$POSTGRES_USER" -d noah -Atc "$1"', "sh",
                 "SELECT count(*) FROM recurring_payment_schedules WHERE pubkey = '" + public_key + "'"],
                capture_output=True, check=True, text=True,
            )
            assert count.stdout.strip() == "0", "Server retained recurring wake entries"
            print("Server accepted native schedules:[] for", public_key, "request", event["request_id"])
            print("Server recurring wake entries: 0 (read-only query)")
            sys.exit(0)
    time.sleep(1)
sys.exit("No accepted schedules:[] found for this wallet in the local server log")
