#!/usr/bin/env python3
"""Compare only this package's placed widget identities and launcher entries."""
import pathlib
import re
import sys

bundle, directory = sys.argv[1:]
root = pathlib.Path(directory)

def widgets(stage):
    text = (root / f"widgets-{stage}.txt").read_text()
    section = text.split("Widgets:", 1)[1].split("Hosts:", 1)[0]
    records = []
    for identity, block in re.findall(r"(?ms)^\s+\[\d+\] id=(\d+)\n(.*?)(?=^\s+\[\d+\] id=|\Z)", section):
        if bundle + "/com.noahwallet.widgets.NoahWidgetRegtestProvider" not in block:
            continue
        provider = re.search(r"provider=([^\n]+)", block)
        host = re.search(r"host=([^\n]+)", block)
        if provider and host:
            records.append((identity, provider.group(1), host.group(1)))
    return sorted(records)

before, after = widgets("before"), widgets("after")
if before != after:
    sys.exit("Placed widget ID/provider/host changed during upgrade")
if not before:
    sys.exit("No placed RN widget baseline; place one before upgrading")
print(f"Placed widgets preserved: {len(before)} (same IDs/provider/host)")
# RN publishes no dynamic shortcuts; the test harness pins this explicit MainActivity
# shortcut. The native instrumentation also asserts its ID and component after replacement.
for stage in ("before", "after"):
    if "noah-upgrade-check" not in (root / f"shortcuts-{stage}.txt").read_text():
        sys.exit("Pinned launcher shortcut missing " + stage + " upgrade")
print("Pinned launcher shortcut preserved (component checked by instrumentation)")
