#!/usr/bin/env python3
"""Record/check the actual RN-placed SpringBoard widget instance on an owned sim."""
import json
import pathlib
import plistlib
import subprocess
import sys

mode, simulator, expected_file = sys.argv[1:]
devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', '-j']))['devices']
device = next(d for group in devices.values() for d in group if d['udid'] == simulator)
state = plistlib.loads((pathlib.Path(device['dataPath']) / 'Library/SpringBoard/IconState.plist').read_bytes())
placements = []
fields = ('widgetIdentifier', 'containerBundleIdentifier', 'bundleIdentifier', 'displayIdentifier', 'uniqueIdentifier', 'gridSize')
def walk(value):
    if isinstance(value, dict):
        if value.get('elementType') == 'widget' and value.get('containerBundleIdentifier') == 'com.noahwallet.regtest':
            assert value['widgetIdentifier'] == 'NoahWidgetRegtest'
            assert value['bundleIdentifier'] == 'com.noahwallet.regtest.NoahWidgetRegtest'
            placements.append({key: value[key] for key in fields})
        for child in value.values():
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)
walk(state)
if mode == 'record' and not placements:
    raise SystemExit(1)
assert placements, 'No RN-placed Noah widget found in SpringBoard'
placements.sort(key=lambda p: p['uniqueIdentifier'])
path = pathlib.Path(expected_file)
if mode == 'record':
    path.write_text(json.dumps(placements, indent=2) + '\n')
elif mode == 'check':
    assert json.loads(path.read_text()) == placements, 'The placed widget was removed or replaced by the upgrade'
else:
    raise ValueError('Expected record or check')
print('SpringBoard widget instances:', ', '.join(p['uniqueIdentifier'] for p in placements), mode)
