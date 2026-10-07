# Blacksmith iOS CI and Nix caching

Research date: 2026-10-07. Scope: hosted iOS CI feasibility and Nix environment reuse; the initial research did not change workflows or run hosted builds. The subsequent CI update removed Duo testing and its SDK 27.1 requirement. Sources below are current first-party documentation and source code. Most documentation pages do not publish an update date; Blacksmith's macOS launch article is dated 2026-04-16. Runner images and action `main` branches can change after this note.

## Recommendation

Keep Nix for an initial hosted build pilot. An ephemeral runner does **not** mean compiling the entire Nix environment on every run. Nix can download public binary substitutes and restore previously built custom outputs. Noah needs an explicit Nix installer and cache step; changing the runner label alone is insufficient. This follows Nix's documented local-store → binary-cache → build behavior. [Determinate Systems: Nix caching](https://zero-to-nix.com/concepts/caching/)

Use separate slim Nix environments for the simulator build and Maestro. Verify the exact Xcode/iOS SDK and simulator runtime, then compare cold and warm runs. Keep signed device/release jobs separate until their signing setup is migrated.

## Implemented simulator pipeline

The simulator build now uses `blacksmith-6vcpu-macos-latest` and the runner's selected default Xcode. The Darwin-only `ios-ci` shell contains Bun, Node 22, and the same customized CocoaPods package used locally; it excludes Android tooling, Maestro, and the host-specific Xcode wrapper. The regular development shells are unchanged. [Build workflow](../.github/workflows/noah-build-release-ios.yml), [flake.nix](../flake.nix)

The workflow installs Nix using the pinned Cachix installer and restores/saves its store with the pinned `cache-nix-action`. Cache keys include OS/architecture, macOS and Xcode build versions, and both Nix files, with a compatible fallback when the Nix files change. A separate timed step realizes the environment before application dependencies/building so hosted cold/warm setup is observable. Simulator builds disable Sentry source-map uploads, avoiding a dependency on the old host's credentials.

Maestro now targets the same hosted macOS runner family, with a separate `ios-test` shell containing Bun, Node 22, Just, jq, and Maestro (including its Java runtime). A parallel native Blacksmith Linux ARM64 job builds the existing server Dockerfile with a persistent Docker layer cache and transfers the image as an artifact. The Mac starts Colima/QEMU, tags the prebuilt image with Compose's generated project/service name, pulls the other images, and runs the existing regtest setup and Maestro flows. Each job owns its Docker VM, Compose project, and SDK-compatible iPhone 17 Pro simulator. The gate also fails when a prerequisite build prevents Maestro from running. Signed device/archive jobs still use the Mac mini. Full hosted Maestro execution and warm Nix cache reuse remain to be verified through the PR. [Maestro workflow](../.github/workflows/noah-maestro-test-ios.yml)

The first hosted run selected Xcode 26.6 and installed Nix in 28 seconds. On its cold cache, the timed environment realization took 37.28 seconds. Native compilation progressed to Expo bundling, which failed with `EBADF` while writing the bundle. The same command reproduced locally with Nix Node 24.15.0. A minimal worker file-I/O check reproduced the known Darwin Node build defect; both checks passed after selecting the existing Node 22.22.3 package, which satisfies the installed Expo/React Native engine requirements. [First hosted run](https://github.com/smolcars/noah/actions/runs/37650157516), [Nixpkgs Node defect](https://github.com/NixOS/nixpkgs/issues/536039)

The corrected hosted build passed on commit `8acc825`: Xcode 26.6/macOS 26.3, 36.48 seconds for the cold Nix environment, approximately six minutes for Pods/native build/bundling, and a 39.8 MB app artifact. The Nix action reported “Saved the new cache.” Maestro subsequently failed during regtest setup on the Mac mini because another local container owned port 18443; this is the reason the rest of the pipeline is now being migrated. [Corrected build and local regtest failure](https://github.com/smolcars/noah/actions/runs/37653074349)

The identical build rerun restored a 445 MiB compressed Nix cache in approximately 24 seconds; the timed environment realization then took **2.87 seconds**. This confirms reuse rather than a full environment rebuild on each runner. [Warm build, attempt 2](https://github.com/smolcars/noah/actions/runs/37653074349/attempts/2)

The first fully hosted run on `aed1c54` passed both the iOS build and native ARM64 server-image build. Docker VM startup failed: the runner's Homebrew snapshot supplied Lima **2.1.2**, whose native-Darwin accelerator always selects HVF, and the guest has no `kern.hv_support`. Lima then panicked after QEMU exited. The workflow now installs the checksum-pinned official Lima **2.2.1** archive ahead of Homebrew's binary; its implementation selects TCG when that sysctl is unavailable. VM logs are retained with failure artifacts. [Hosted run](https://github.com/smolcars/noah/actions/runs/37656195590), [Lima 2.1.2 accelerator](https://github.com/lima-vm/lima/blob/v2.1.2/pkg/driver/qemu/qemu.go), [Lima 2.2.1 accelerator](https://github.com/lima-vm/lima/blob/v2.2.1/pkg/driver/qemu/qemu.go)

The next hosted run on `ce02f3b` successfully started Docker under ARM64 TCG in approximately 4.5 minutes and completed the full regtest bootstrap in approximately four minutes. It selected the available iOS 26.5 runtime for iPhone 17 Pro. Maestro 2.5.1 passed the receive flow but aborted three flows while first locating “Create Wallet”: the driver reported the app was not running, while its failure screenshot showed the app's splash screen. This matches Maestro's fixed launch-state race: an app can appear in the active process list before XCTest reports it as foreground. The `ios-test` shell now pins Maestro **2.11.0**, whose driver includes that fix, without changing the ordinary development shells or skipping any flows. Full hosted Maestro validation is still pending. [Hosted Docker/regtest success and test failure](https://github.com/smolcars/noah/actions/runs/37658597898), [Maestro launch-state fix #3398](https://github.com/mobile-dev-inc/Maestro/pull/3398), [Maestro release](https://github.com/mobile-dev-inc/Maestro/releases/tag/cli-2.11.0)

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

The implemented `ios-ci` shell was built and entered locally: Bun 1.3.13, Node 22.22.3, and CocoaPods 1.16.2 run successfully, while `xcodebuild` and `xcrun` resolve to `/usr/bin`. Its deduplicated runtime closure is **117 paths / 1.10 GiB uncompressed**, measured with the same input/closure queries. All four pre-existing Darwin/Linux development and server shell derivation paths match the original `master` definitions. These local checks do not establish hosted cache performance or application build success.

Public-cache `.narinfo` HEAD probes returned HTTP 200 for the current `bun-1.3.13` output and 404 for the composed Android SDK, custom CocoaPods, and Xcode wrapper outputs. This is a snapshot of four outputs, not an audit of all dependencies. A missing top-level custom output may require assembly/download of prebuilt inputs, not compiling its whole dependency tree. These observations favor caching the store or reducing the iOS shell's footprint; they do not establish a hosted run duration.

Run the same hosted simulator build twice with the implemented installer/store cache. Record Nix installation time, cache restore/save time and size, actual packages built versus downloaded, shell readiness, CocoaPods setup, and Xcode build duration. Then repeat after a source-only change and a Nix lock/config change. Confirm the intended cache backend from the cache logs; do not infer a cache hit solely from job success.

The pilot already uses an iOS-only CI shell containing the tools actually used. Replacing Nix with image tools/Bun/Ruby setup becomes reasonable if this smaller shell still costs too much or macOS compatibility fails. That does not remove the separate Xcode, Docker/localhost, and signing requirements. Nix-store caching preserves the CocoaPods executable but does not automatically cache installed Pods, Bun packages, or Xcode DerivedData; those should be measured and configured separately if needed.

## Hosted Maestro and regtest feasibility

Checked 2026-10-07. **Recommended first implementation: run Maestro and the complete Compose stack on the hosted macOS runner using Colima's QEMU software fallback; build the Noah server image separately on native Blacksmith Linux ARM64 and transfer it as an Actions artifact.** This preserves the existing simulator's localhost endpoints and test scripts. It is an inference from the implementation below, pending an actual hosted Maestro run; Blacksmith does not explicitly advertise this macOS Docker configuration.

Blacksmith's macOS workers are M4 Virtualization.framework guests. Its FAQ supports nested virtualization only on x64 Linux and excludes ARM. Therefore M4 hardware support for nesting does not establish that a hosted guest can start another hardware-accelerated Linux VM. Avoid assuming Docker Desktop, VZ Colima, Rosetta-in-VZ, or a macOS Docker cache solves this. [Blacksmith runner capabilities](https://docs.blacksmith.sh/blacksmith-runners/overview), [Blacksmith macOS architecture](https://www.blacksmith.sh/blog/how-we-shipped-mac-runners-in-3-weeks)

Lima **2.2.1** implements an alternative: its QEMU `Accel()` selects `tcg` on Darwin when `kern.hv_support` is unavailable or is not `1`. A non-native architecture also selects TCG. ARM64 TCG uses an emulated CPU and does not need nested virtualization; expect slower execution. The default CPU becomes `max` under TCG. There is no ordinary Colima `--accel` flag. Lima accepts extra arguments in `QEMU_SYSTEM_AARCH64`, but explicitly labels this debugging-only; prefer its automatic fallback. [Lima 2.2.1 QEMU source](https://github.com/lima-vm/lima/blob/v2.2.1/pkg/driver/qemu/qemu.go), [QEMU emulation and accelerators](https://www.qemu.org/docs/master/system/introduction.html)

Current Homebrew bottles support macOS Tahoe ARM64: Colima **0.10.3**, Lima **2.2.1**, QEMU **11.1.2**, and Compose **5.6.0**. The hosted runner used an older formula snapshot, so the workflow installs Lima 2.2.1's official archive explicitly. Install QEMU explicitly: Colima's formula depends on Lima, not QEMU. The VM start command is:

```sh
brew install colima qemu docker docker-compose
sysctl kern.hv_support || true
colima start --vm-type qemu --arch aarch64 --cpu-type max \
  --cpus 4 --memory 8 --disk 60 --mount-type 9p
docker info
docker-compose version
```

The resource sizes are initial choices, not measured requirements. Log versions and confirm QEMU's actual command line uses `accel=tcg`. If the image reports hypervisor support but cannot use it, the supported cross-architecture alternative is `--arch x86_64`, with `brew install lima-additional-guestagents`; it always selects TCG on M4 and needs an AMD64 server artifact. [Colima formula](https://formulae.brew.sh/formula/colima), [Lima formula](https://formulae.brew.sh/formula/lima), [QEMU formula](https://formulae.brew.sh/formula/qemu), [Compose formula](https://formulae.brew.sh/formula/docker-compose), [additional guest agents](https://formulae.brew.sh/formula/lima-additional-guestagents), [Colima CLI](https://colima.run/docs/commands/)

Lima forwards guest TCP listeners to localhost; Colima sets up the Docker socket/context and its forwarding configuration. Existing Compose bind mounts can stay under the checked-out user's home directory, using `9p` for the QEMU VM. No bridge, public regtest endpoint, or app networking change is needed for this arrangement. Verify host access to the published Bitcoin, Ark, Noah server, and Barkd ports before running the existing funding/payment flows. [Lima port forwarding](https://lima-vm.io/docs/config/port/), [Colima generated configuration](https://github.com/abiosoft/colima/blob/v0.10.3/environment/vm/lima/yaml.go), [Noah Compose stack](../scripts/docker-compose.yml), [existing regtest setup](../scripts/ark-dev.sh)

Anonymous registry manifest checks found **both `linux/arm64` and `linux/amd64` for every required published image and both Dockerfile base images**. These are availability checks, not container startup tests; tags such as `latest` can change. Checked with `docker buildx imagetools inspect --raw IMAGE`, plus an anonymous GHCR manifest request for Dragonfly (its custom registry redirects to GHCR).

| Exact image/tag                                       | First-party registry source                                                                              |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `bitcoin/bitcoin:30`                                  | [Bitcoin tag](https://hub.docker.com/r/bitcoin/bitcoin/tags?name=30)                                     |
| `postgres:16-alpine`                                  | [Postgres tag](https://hub.docker.com/_/postgres/tags?name=16-alpine)                                    |
| `docker.dragonflydb.io/dragonflydb/dragonfly:latest`  | [Dragonfly GHCR package](https://github.com/dragonflydb/dragonfly/pkgs/container/dragonfly)              |
| `niteshbalusu/bark-cln:latest`                        | [CLN tag](https://hub.docker.com/r/niteshbalusu/bark-cln/tags?name=latest)                               |
| `niteshbalusu/captaind:nightly-2026-09-04`            | [Captaind tag](https://hub.docker.com/r/niteshbalusu/captaind/tags?name=nightly-2026-09-04)              |
| `niteshbalusu/bark:nightly-2026-09-04`                | [Bark tag](https://hub.docker.com/r/niteshbalusu/bark/tags?name=nightly-2026-09-04)                      |
| `lightninglabs/lnd:v0.20.0-beta`                      | [LND tag](https://hub.docker.com/r/lightninglabs/lnd/tags?name=v0.20.0-beta)                             |
| `mempool/electrs:latest`                              | [Electrs tag](https://hub.docker.com/r/mempool/electrs/tags?name=latest)                                 |
| `niteshbalusu/noah-barkd:0.7.1`                       | [Barkd tag](https://hub.docker.com/r/niteshbalusu/noah-barkd/tags?name=0.7.1)                            |
| `lukemathwalker/cargo-chef:latest-rust-1.95-bookworm` | [Cargo-chef tag](https://hub.docker.com/r/lukemathwalker/cargo-chef/tags?name=latest-rust-1.95-bookworm) |
| `debian:bookworm-slim`                                | [Debian tag](https://hub.docker.com/_/debian/tags?name=bookworm-slim)                                    |

Avoid fresh Rust compilation under TCG. Use `blacksmith-4vcpu-ubuntu-2404-arm` with the existing Dockerfile, `useblacksmith/setup-docker-builder@v2` and `useblacksmith/build-push-action@v2`, a Dockerfile-scoped cache, and a single-platform Docker tar output. Upload using the repository's artifact action, download on macOS, and `docker load`; configure/tag the loaded image for the Compose `noah-server` service and skip its local build. Docker documents this artifact transfer pattern; it avoids registry credentials and works for a single architecture. The repository already uses Blacksmith's native ARM server builders. [Blacksmith Docker caching](https://docs.blacksmith.sh/blacksmith-caching/docker-builds), [Docker image artifacts between jobs](https://docs.docker.com/build/ci/github-actions/share-image-jobs/), [existing server builder](../.github/workflows/server-push.yml), [server Dockerfile](../Dockerfile)

The corresponding GitHub macOS 26 image lists Homebrew, `jq`, Xcode, simulator runtimes, and Java 17/21; it does not list Docker, Colima, QEMU, or Maestro. Blacksmith follows these images, but observed runner versions can differ. The existing slim Nix shell supplies Bun/Node/CocoaPods; install Maestro explicitly or include it in the test environment. Maestro requires Java 17+; its current release is **2.11.0**, and `MAESTRO_VERSION=2.11.0` pins the official installer. Keep selecting the simulator against the actual installed Xcode/runtime. [GitHub macOS 26 image inventory](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md), [Maestro installation](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli), [Maestro version pinning](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli/update-the-maestro-cli), [Maestro 2.11.0](https://github.com/mobile-dev-inc/maestro/releases/tag/cli-2.11.0)

If TCG startup or regtest throughput proves too slow, run the stack in a concurrent Blacksmith Linux job and forward its service ports to macOS over authenticated SSH or a private overlay. A remote Docker context alone is insufficient: published ports live on the remote daemon host, and Compose bind mounts must also exist there. The jobs need explicit discovery, readiness, completion, and teardown coordination; `needs:` cannot connect to a backend job after that job has already ended. No documented Blacksmith cross-job private-network feature was found: static IPs are egress NAT, and built-in SSH is an opt-in debugging facility using the triggering user's GitHub keys. Thus this is a larger fallback, not a one-line supported remote-runtime switch. [Docker bind-mount host semantics](https://docs.docker.com/engine/storage/bind-mounts/), [Blacksmith static IP](https://docs.blacksmith.sh/blacksmith-runners/static-ip), [Blacksmith SSH access](https://docs.blacksmith.sh/blacksmith-observability/ssh-access)
