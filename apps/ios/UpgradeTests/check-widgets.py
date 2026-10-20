#!/usr/bin/env python3
"""Check RN/native widget identity for all variants and both installed products."""
import pathlib
import plistlib
import re
import sys

repo = pathlib.Path(__file__).resolve().parents[3]
project = (repo / "client/ios/Noah.xcodeproj/project.pbxproj").read_text()
for name in ("Mainnet", "Signet", "Regtest"):
    variant = name.lower()
    kind = "NoahWidget" + name
    bundle = f"com.noahwallet.{variant}.{kind}"
    source = (repo / f"client/ios/{kind}/{kind}.swift").read_text()
    assert re.search(r'let kind: String = "' + kind + '"', source), name
    assert f'PRODUCT_BUNDLE_IDENTIFIER = {bundle};' in project, name
    config = (repo / f"apps/ios/Config/{name}.xcconfig").read_text()
    assert f"NOAH_WIDGET_KIND = {kind}" in config, name
    assert f"NOAH_WIDGET_BUNDLE_ID = {bundle}" in config, name
    assert f'group.com.noahwallet.{variant}' in source, name
    for file in (repo / f"client/ios/{kind}Extension.entitlements",
                 repo / f"apps/ios/NoahWidget/Entitlements/{name}.entitlements"):
        assert f"group.com.noahwallet.{variant}" in plistlib.loads(file.read_bytes())["com.apple.security.application-groups"]

if len(sys.argv) == 3:
    rn, native = map(pathlib.Path, sys.argv[1:])
    def info(path):
        return plistlib.loads((path / "Info.plist").read_bytes())
    assert info(rn)["CFBundleIdentifier"] == info(native)["CFBundleIdentifier"] == "com.noahwallet.regtest"
    assert int(info(native)["CFBundleVersion"]) > int(info(rn)["CFBundleVersion"])
    old = next(path for path in (rn / "PlugIns").glob("*.appex")
               if info(path)["CFBundleIdentifier"].endswith(".NoahWidgetRegtest"))
    new = next((native / "PlugIns").glob("*.appex"))
    assert info(old)["CFBundleIdentifier"] == info(new)["CFBundleIdentifier"] == "com.noahwallet.regtest.NoahWidgetRegtest"
    assert info(new)["NoahWidgetKind"] == "NoahWidgetRegtest"
    assert info(new)["NoahAppGroup"] == "group.com.noahwallet.regtest"
    assert info(old)["NSExtension"]["NSExtensionPointIdentifier"] == info(new)["NSExtension"]["NSExtensionPointIdentifier"] == "com.apple.widgetkit-extension"
    print("Built RN/native regtest app and widget identities match; native build number increases.")
print("All three widget kinds, bundle IDs and app groups match RN.")
