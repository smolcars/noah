#!/usr/bin/env python3
"""Pinned Fabrikt client; run from any directory (JDK 17+)."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
VERSION = "28.1.1"
SHA256 = "e0b834b0a2660047c5b09a46ecad12c0bcb4cc41867a35229b76db63255fae6b"
PACKAGE = "com.noahwallet.api.generated"
DEST = ROOT / "apps/android/app/src/main/java/com/noahwallet/api/generated"

with tempfile.TemporaryDirectory(prefix="noah-fabrikt-") as tmp:
    jar = Path(tmp) / "fabrikt.jar"
    urllib.request.urlretrieve(
        f"https://repo.maven.apache.org/maven2/io/fabrikt/fabrikt/{VERSION}/fabrikt-{VERSION}.jar", jar)
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == SHA256, "Fabrikt checksum mismatch"
    subprocess.run([
        "java", "-jar", str(jar), "--api-file", str(ROOT / "api/openapi.json"),
        "--base-package", PACKAGE, "--output-directory", tmp,
        "--targets", "HTTP_MODELS", "--targets", "CLIENT", "--http-client-target", "KTOR",
        "--http-client-opts", "DYNAMIC_BASE_URL", "--serialization-library", "KOTLINX_SERIALIZATION",
        "--validation-library", "NO_VALIDATION", "--type-overrides", "ANY_AS_JSONELEMENT",
        # Fabrikt otherwise narrows uint32 to Int (the server fixture contains 4294967295).
        "--custom-type-mapping", "integer:uint32=kotlin.Long",
    ], check=True)
    generated = Path(tmp) / "src/main/kotlin" / PACKAGE.replace(".", "/")
    # Opt in without requiring every user of the sealed notification type to opt in.
    notification = generated / "models/NotificationData.kt"
    notification.write_text(notification.read_text().replace(
        "@ExperimentalSerializationApi", "@OptIn(ExperimentalSerializationApi::class)"))
    spec = json.loads((ROOT / "api/openapi.json").read_text())
    routes = []
    for path, operations in spec["paths"].items():
        for method, operation in operations.items():
            if not isinstance(operation, dict) or "operationId" not in operation:
                continue
            security = operation.get("security", spec.get("security", []))
            if not security or {} in security:
                routes.append(f'        "{method.upper()} {path}",')
    (generated / "PublicOperations.kt").write_text(
        "// Generated from OpenAPI security by tools/generate-api.py.\n"
        f"package {PACKAGE}\n\nobject PublicOperations {{\n"
        "    val routes: Set<String> = setOf(\n" + "\n".join(sorted(routes)) + "\n    )\n}\n")
    for source in generated.rglob("*.kt"):
        source.write_text("\n".join(line.rstrip() for line in source.read_text().splitlines()) + "\n")
    if DEST.exists():
        shutil.rmtree(DEST)
    shutil.copytree(generated, DEST)
