# Releasing the native apps

Android releases are built by GitHub Actions from `apps/android`. iOS releases
are archived and distributed by Xcode Cloud from `apps/ios`. One `vX.Y.Z` tag
releases both Signet and Mainnet. Variant-specific tags such as `v0.2.0.signet`
are not supported by the native release workflow.

## Prepare the release

On a release branch, run one of:

```sh
just bump-native          # patch version; increment both build numbers
just bump-native minor
just bump-native major
just bump-native 0.2.0    # explicit version; increment both build numbers
```

The command updates Android's `versionName` / `versionCode` in
`apps/android/app/build.gradle.kts` and iOS's `MARKETING_VERSION` /
`CURRENT_PROJECT_VERSION` in `apps/ios/project.yml`. Both version names must
agree before it writes. Explicit versions can stay the same to rebuild a
release, but cannot go backwards. Each platform's build number increases by one.
It does not modify React Native versions or commit, push, or tag anything.

For the first native release, the committed version is already `0.2.0`, build
100. Use `just bump-native 0.2.0` to keep that version and advance both builds.
Check that the Android version code exceeds every code already uploaded to the
corresponding Google Play apps, including testing tracks.

Open a PR containing the version changes. After checks pass and it is merged,
tag the merged commit (substitute the actual version and commit):

```sh
git tag -a v0.2.0 <merged-commit> -m 'Noah 0.2.0'
git push origin v0.2.0
```

Android validates that the tag exactly matches both committed native versions
before loading signing credentials. CI reads committed versions rather than
changing them during a build.

## Android outputs

`Native Release` builds signed Signet and Mainnet APKs and AABs. It uses the
existing `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, and
`SENTRY_AUTH_TOKEN` secrets, preserving the React Native signing identity.
Builds target arm64 phones, matching the previous release workflow.

The GitHub Release contains `noah-signet.apk` and `noah-mainnet.apk`. The same
Actions run retains artifacts named `native-android-signet` and
`native-android-mainnet` for 90 days. Each includes its APK and AAB. Download the
appropriate AAB and upload it manually to the corresponding app in Google Play;
the workflow does not publish to Google Play. Archive the AAB separately if it
needs to be retained longer than 90 days.

For a build without publishing a GitHub Release, manually run `Native Android
Artifacts` against `master` or an existing `vX.Y.Z` tag and select the variant.
Before the first native release, verify the signed artifacts' versions,
package IDs and certificate against the released app, and test an in-place
upgrade without uninstalling or clearing app data.

## iOS and migration setup

Configure both Xcode Cloud release workflows to watch the same `v*` tags, using
schemes `Noah-Signet` and `Noah`. Confirm the builds use the tagged commit.
The post-clone script generates the Xcode project before package resolution.
Xcode Cloud manages uploaded build numbers separately from the repository's
build number; set its next build number above previous uploads if necessary.
Verify this for both apps before the first release.

GitHub does not wait for Xcode Cloud before publishing Android APKs. Check
Xcode Cloud separately for archive success and TestFlight distribution.
The existing server workflow still reacts to `v*` tags as before.

The React Native workflows are preserved as `.yml.bak` files and do not run.
Remove the old required `client_gate` check from branch protection before
merging this migration. Native CI remains active; choose replacement required
checks separately. React Native source and shared assets remain in the repo.

## Local release-tool checks

```sh
bun test scripts/native_release.test.ts
bun scripts/check_native_release.ts v0.2.0 # use the committed native version
actionlint .github/workflows/native-release.yml .github/workflows/native-android-artifacts.yml .github/workflows/native-release-checks.yml
```

`Native Release Checks` runs these version tests and workflow validation on PRs.
