#!/usr/bin/env bash
# Download the pinned BoltFFI CLI (same release and checksums as bark-bolt-ffi) into .tools/.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) archive=boltffi-darwin-aarch64.tar.gz; checksum=3837df80dae7d8d1d84f6b9c73890b9b6f79b3b74360d9b5a788524740b7bfdf ;;
    Darwin/x86_64) archive=boltffi-darwin-x86_64.tar.gz; checksum=a2d180c6eef4bdd40535e7535a5627032e9d0d92a63c6fed302ebfd17ddddbc7 ;;
    Linux/x86_64) archive=boltffi-linux-x86_64.tar.gz; checksum=ae02515544f6553e72b2fd5ea99ed604b047d163dfb7fa2e1534dda92756cc28 ;;
    *) echo 'No pinned CLI archive for this host' >&2; exit 1 ;;
esac
mkdir -p .tools
curl --fail --location --silent --show-error "https://github.com/boltffi/boltffi/releases/download/v0.31.1/$archive" --output ".tools/$archive"
echo "$checksum  .tools/$archive" | shasum -a 256 -c -
tar -xzf ".tools/$archive" -C .tools
.tools/boltffi --version
