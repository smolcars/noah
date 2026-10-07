"""Check the installed ExpoModulesJSI interface cleaner against the selected Swift toolchain output."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent.parent
script = (root / "node_modules/expo-modules-jsi/apple/scripts/build-xcframework.sh").read_text()
expression = re.search(r'''sed -E '([^']+)' "\$swiftinterface"''', script)[1]
fixture = """@available(*, unavailable)
extension Swift::Optional : Swift::Copyable where Wrapped : _ConstraintThatIsNotPartOfTheAPIOfThisLibrary {}
@available(*, unavailable)
extension Swift::Optional : where Wrapped : _ConstraintThatIsNotPartOfTheAPIOfThisLibrary {}
extension Swift::Bool : ExpoModulesJSI::JavaScriptRepresentable {}
@available(*, unavailable)
public func intentionallyUnavailable() {}
"""
result = subprocess.run(["sed", "-E", expression], input=fixture, text=True, capture_output=True, check=True)
assert result.stdout == """extension Swift::Bool : ExpoModulesJSI::JavaScriptRepresentable {}
@available(*, unavailable)
public func intentionallyUnavailable() {}
""", result.stdout
print("Expo Swift interface regression passed")
