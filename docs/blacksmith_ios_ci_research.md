# Blacksmith iOS CI and Nix caching

Research date: 2026-10-07. Scope: hosted iOS CI feasibility and Nix environment reuse; the initial research did not change workflows or run hosted builds. The subsequent CI update removed Duo testing and its SDK 27.1 requirement. Sources below are current first-party documentation and source code. Most documentation pages do not publish an update date; Blacksmith's macOS launch article is dated 2026-04-16. Runner images and action `main` branches can change after this note.

## Recommendation

Keep Nix for an initial hosted build pilot. An ephemeral runner does **not** mean compiling the entire Nix environment on every run. Nix can download public binary substitutes and restore previously built custom outputs. Noah needs an explicit Nix installer and cache step; changing the runner label alone is insufficient. This follows Nix's documented local-store → binary-cache → build behavior. [Determinate Systems: Nix caching](https://zero-to-nix.com/concepts/caching/)

Start with the simulator build before migrating Maestro or signed device/release jobs. Verify the exact Xcode/iOS SDK and simulator runtime, then compare one cold run with an identical warm run. If setup remains expensive, trim the macOS CI shell before replacing Nix entirely. This recommendation is an inference from the repository dependencies and the caching options below, not a measured Blacksmith benchmark.

## Implemented simulator-build pilot

The simulator build now uses `blacksmith-6vcpu-macos-latest` and the runner's selected default Xcode. The Darwin-only `ios-ci` shell contains Bun, Node, and the same customized CocoaPods package used locally; it excludes Android tooling, Maestro, and the host-specific Xcode wrapper. The regular development shells are unchanged. [Build workflow](../.github/workflows/noah-build-release-ios.yml), [flake.nix](../flake.nix)

The workflow installs Nix using the pinned Cachix installer and restores/saves its store with the pinned `cache-nix-action`. Cache keys include OS/architecture, macOS and Xcode build versions, and both Nix files, with a compatible fallback when the Nix files change. A separate timed step realizes the environment before application dependencies/building so hosted cold/warm setup is observable. Simulator builds disable Sentry source-map uploads, avoiding a dependency on the old host's credentials.

Maestro execution and signed device/archive jobs still use the Mac mini until their Docker and signing setup is migrated. Hosted build success, actual cache reuse, and cache-backend throughput remain to be verified through the PR.

## Hosted macOS is available

Blacksmith's official runner catalog currently lists:

- Apple Silicon M4 / ARM64.
- `blacksmith-6vcpu-macos-15`, `blacksmith-6vcpu-macos-26`, and `blacksmith-6vcpu-macos-27`: 24 GB memory, 150 GB storage.
- Corresponding `12vcpu` runners: 48 GB memory, 250 GB storage.
- `macos-latest` currently points to macOS 26; macOS 27 is public beta.
- macOS 26 images include Xcode 26 by default and Xcode 27; macOS 27 images select Xcode 27. Exact point versions and iOS 27.1 availability are not promised by this catalog. [Blacksmith runner catalog](https://docs.blacksmith.sh/blacksmith-runners/overview)

Blacksmith runs macOS VMs through Apple's Virtualization.framework with isolated copy-on-write filesystems. This addresses jobs sharing a development host, but it is not a promise that a job's modified `/nix` survives into the next job. Persist dependencies through a configured cache. [Blacksmith macOS launch article, 2026-04-16](https://www.blacksmith.sh/blog/how-we-shipped-mac-runners-in-3-weeks)

The pricing page advertises macOS M4 at $0.08/min for its smallest tier. Recheck the selected size when estimating costs. [Blacksmith pricing](https://www.blacksmith.sh/pricing)

## What each cache does

### Public Nix binary cache

Nix's default substituter is `https://cache.nixos.org/`. A matching prebuilt store object is downloaded instead of built. Missing objects are built, so a fresh runner still pays download/extraction time and can compile packages that are unavailable for its exact derivation/platform. The public cache is not an automatic repository cache for Noah's modified derivations. [Nix configuration reference](https://nix.dev/manual/nix/2.34/command-ref/conf-file.html), [Determinate Systems: Nix caching](https://zero-to-nix.com/concepts/caching/)

### Blacksmith Actions cache

Blacksmith says official GitHub and popular third-party cache actions transparently use its colocated backend. Its macOS pricing card also advertises faster cache downloads. This provides a backend for configured cache actions; it does not automatically select and preserve the Nix store. [Blacksmith Actions cache](https://docs.blacksmith.sh/blacksmith-caching/dependencies-actions), [Blacksmith pricing](https://www.blacksmith.sh/pricing)

The Actions cache documentation currently states 25 GB free storage per repository per week, eviction of entries unused for more than seven days, and branch protections consistent with GitHub by default. These limits matter for a large Nix environment and multiple lock-file keys. Existing `useblacksmith/*` cache forks are archived; use upstream actions. Nix-specific third-party acceleration on Blacksmith macOS still needs a warm-hit pilot because the docs do not explicitly name a Nix action. [Blacksmith Actions cache](https://docs.blacksmith.sh/blacksmith-caching/dependencies-actions)

### Nix-aware store restore

`nix-community/cache-nix-action` explicitly supports Nix-store caching on Linux and macOS and is compatible with `cachix/install-nix-action`. It restores store files and merges the Nix store database, rather than blindly unpacking `/nix`. Requirements include Nix 2.24+ and SQLite 3.37+. It uses the standard Actions backend by default and warns that restore/save overhead can make a workflow slower. [cache-nix-action documentation](https://github.com/nix-community/cache-nix-action)

Its own CI tests macOS 14 and 15 with the Cachix installer. This supports macOS compatibility; it does not demonstrate Noah or Blacksmith macOS 27 compatibility. [cache-nix-action CI](https://github.com/nix-community/cache-nix-action/blob/main/.github/workflows/ci.yaml)

The implemented pilot is checkout → `cachix/install-nix-action` → `nix-community/cache-nix-action` → `nix develop .#ios-ci` build. Action revisions are pinned, and the cache is scoped to compatible OS/architecture and Xcode builds. The hosted shell uses the runner's default Xcode rather than caching a wrapper with a host-specific path. Hosted cache behavior is still unverified.

Noah's existing substituter/hook resets do **not** prevent this direct store restore: restored paths are already present locally, so they do not need an extra binary substituter.

Avoid assuming `nixbuild/nix-quick-install-action` is supported just because Blacksmith's Linux example uses it: its README explicitly says self-hosted runners are currently unsupported. The Cachix installer explicitly supports macOS and self-hosted runners; its README currently uses `v31` and estimates around 20 seconds for installation on macOS. That estimate is not a Noah/Blacksmith measurement. [Nix Quick Install Action](https://github.com/nixbuild/nix-quick-install-action), [Cachix Nix installer](https://github.com/cachix/install-nix-action)

### Magic Nix Cache or an external binary cache

Determinate's Magic Nix Cache also documents Linux/macOS support, and its own CI includes `aarch64-darwin`. It uses GitHub Actions cache semantics and avoids recaching outputs already available from its upstream cache. Exact Blacksmith backend compatibility and throughput remain unverified. [Magic Nix Cache](https://github.com/DeterminateSystems/magic-nix-cache-action), [Magic Nix Cache platform tests](https://github.com/DeterminateSystems/magic-nix-cache-action/blob/main/.github/workflows/ci.yml)

Its source appends a localhost substituter to user `nix.conf` and, without Determinate Nixd, installs a post-build hook. Noah's existing `NIX_CONFIG` explicitly clears `extra-substituters` and `post-build-hook` after file configuration is loaded. Therefore adding Magic Nix Cache while retaining those overrides would prevent its configured retrieval path and legacy upload hook from taking effect. Reconcile that configuration if choosing this option. [Magic Nix Cache source](https://github.com/DeterminateSystems/magic-nix-cache/blob/main/magic-nix-cache/src/main.rs), [Nix configuration precedence](https://nix.dev/manual/nix/2.34/command-ref/conf-file.html)

Cachix is an alternative for sharing custom macOS environment outputs between CI and development. Its documentation explicitly supports pushing a flake development shell with `nix develop --profile …` and pushing that profile. That requires cache credentials/configuration and relaxing Noah's public-cache-only substituter override. It is an additional service, so it need not be the first pilot. [Cachix: pushing shell environments](https://docs.cachix.org/pushing)

### Sticky Disks

Blacksmith's Sticky Disks README includes a `/nix` persistence example **on Ubuntu** and describes ext4-backed disks. It is useful evidence for Linux Nix support, not evidence of macOS Nix-store persistence. [Blacksmith Sticky Disks README](https://github.com/useblacksmith/stickydisk)

The published action implementation invokes Linux utilities including `blockdev`, `blkid`, `mkfs.ext4`, and `resize2fs`, and mounts an ext4 disk; there is no Darwin path in that implementation. Its fallback can merely create an empty directory with no persistence. Do not base the iOS migration on Sticky Disks without an explicit macOS implementation/support statement. This conclusion is based on the implementation, not solely on documentation silence. [Sticky Disks source](https://github.com/useblacksmith/stickydisk/blob/main/src/main.ts)

## Noah-specific migration requirements

The remaining workflows still assume a prepared host:

- [Maestro tests](../.github/workflows/noah-maestro-test-ios.yml), [device build](../.github/workflows/ios-device-build.yml), and [release workflow](../.github/workflows/release.yml) use the `macOS` runner label and `nix develop .#`, without explicit Nix installation/store restoration. The [simulator build](../.github/workflows/noah-build-release-ios.yml) now installs/caches the smaller `ios-ci` environment on Blacksmith.
- [flake.nix](../flake.nix) supports `aarch64-darwin`, which matches the M4 architecture. The default shell also brings Android SDK/NDKs and development-only tools into macOS iOS jobs. Its CocoaPods/ffi/libffi override and local wrappers create custom outputs that need local build or a repository-specific cache.
- The regular development shell's Xcode wrapper selects `/Applications/Xcode.app`, otherwise `/Applications/Xcode-16.4.0.app`, and sets `DEVELOPER_DIR`. Xcode itself is external to Nix. The hosted `ios-ci` shell omits this wrapper and respects the runner's selected default Xcode.
- Maestro CI now uses only iPhone 17 Pro and selects an installed runtime matching the runner's selected Xcode SDK major/minor version. The Duo-specific SDK **27.1 or newer** build assertion was removed; verify that the selected Blacksmith image supplies the matching runtime for iPhone 17 Pro. [Maestro workflow](../.github/workflows/noah-maestro-test-ios.yml)
- Maestro pulls/builds the local Docker Compose regtest stack and expects localhost service ports. Blacksmith's nested-virtualization FAQ only documents support on x64 Linux, not ARM/macOS. No official supported macOS local Linux-Docker setup was found in the inspected docs. Treat this as a separate capability to verify; Docker image caching does not supply a working Docker daemon. If unavailable, the regtest backend needs a reachable Linux host and appropriate simulator networking. [Maestro workflow](../.github/workflows/noah-maestro-test-ios.yml), [Blacksmith virtualization FAQ](https://docs.blacksmith.sh/blacksmith-runners/overview)
- Signed device/release builds use provisioning updates but do not bootstrap a signing certificate, provisioning profile, or temporary CI keychain. Those host assumptions must become explicit setup when moving to fresh runners. [Device build](../.github/workflows/ios-device-build.yml), [Release workflow](../.github/workflows/release.yml), [iOS build scripts](../client/package.json)

## Verification before choosing a non-Nix environment

A read-only local snapshot of the current Darwin default shell found 154 deduplicated runtime dependency paths totaling approximately **8.2 GiB uncompressed**. This excludes Xcode, application dependencies, Pods, and Docker and is not a compressed cache-size or hosted-timing measurement. The commands were `nix eval --offline --no-write-lock-file --json .#devShells.aarch64-darwin.default.buildInputs`, then `nix path-info --offline --json --recursive` on those input paths.

The Android SDK input alone has a **7.64 GiB** runtime closure; custom CocoaPods has a **0.84 GiB** closure, measured with `nix path-info --offline --json --closure-size`. These closures overlap and must not be added together. Android dominates the current iOS job's tool footprint, making an iOS-only CI shell a concrete simplification.

The implemented `ios-ci` shell was built and entered locally: Bun 1.3.13, Node 24.15.0, and CocoaPods 1.16.2 run successfully, while `xcodebuild` and `xcrun` resolve to `/usr/bin`. Its deduplicated runtime closure is **117 paths / 1.11 GiB uncompressed**, measured with the same input/closure queries. All four pre-existing Darwin/Linux development and server shell derivation paths match the original `master` definitions. These local checks do not establish hosted cache performance or application build success.

Public-cache `.narinfo` HEAD probes returned HTTP 200 for the current `bun-1.3.13` output and 404 for the composed Android SDK, custom CocoaPods, and Xcode wrapper outputs. This is a snapshot of four outputs, not an audit of all dependencies. A missing top-level custom output may require assembly/download of prebuilt inputs, not compiling its whole dependency tree. These observations favor caching the store or reducing the iOS shell's footprint; they do not establish a hosted run duration.

Run the same hosted simulator build twice with the implemented installer/store cache. Record Nix installation time, cache restore/save time and size, actual packages built versus downloaded, shell readiness, CocoaPods setup, and Xcode build duration. Then repeat after a source-only change and a Nix lock/config change. Confirm the intended cache backend from the cache logs; do not infer a cache hit solely from job success.

The pilot already uses an iOS-only CI shell containing the tools actually used. Replacing Nix with image tools/Bun/Ruby setup becomes reasonable if this smaller shell still costs too much or macOS compatibility fails. That does not remove the separate Xcode, Docker/localhost, and signing requirements. Nix-store caching preserves the CocoaPods executable but does not automatically cache installed Pods, Bun packages, or Xcode DerivedData; those should be measured and configured separately if needed.
