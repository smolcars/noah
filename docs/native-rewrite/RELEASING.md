# Releasing the native apps

GitHub Actions builds the Android releases from `apps/android` and archives the
iOS releases from `apps/ios`. Google Play and App Store uploads are manual;
Zapstore publishing is automatic. One `vX.Y.Z` tag releases both Signet and
Mainnet. Variant-specific tags such as `v1.0.0.signet` are not supported by
the native release workflow.

## Prepare the release

On a release branch, run one of:

```sh
just bump-native          # patch version; increment both build numbers
just bump-native minor
just bump-native major
just bump-native 1.0.0    # explicit version; increment both build numbers
```

The command updates Android's `versionName` / `versionCode` in
`apps/android/app/build.gradle.kts` and iOS's `MARKETING_VERSION` /
`CURRENT_PROJECT_VERSION` in `apps/ios/project.yml`. Both version names must
agree before it writes. Explicit versions can stay the same to rebuild a
release, but cannot go backwards. Each platform's build number increases by one.
It does not modify React Native versions or commit, push, or tag anything.

The first native release is `1.0.0`, build 101 on both platforms.
Check that the Android version code exceeds every code already uploaded to the
corresponding Google Play apps, including testing tracks.

Open a PR containing the version changes. After checks pass and it is merged,
tag the merged commit (substitute the actual version and commit):

```sh
git tag -a v1.0.0 <merged-commit> -m 'Noah 1.0.0'
git push origin v1.0.0
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

## Zapstore

After the GitHub Release exists, `Native Zapstore` publishes `noah-mainnet.apk`
from it to [Zapstore](https://zapstore.dev) with `zsp`, using the root
`zapstore.yaml`. That file also holds the listing's summary, description,
website and tags; the icon and name come from the APK. Signet is not published
there. It signs with the `ZAPSTORE_SIGN_WITH` secret, a NIP-46 `bunker://` URL
(or an `nsec`).

One-time setup, done by hand: run the `zsp` wizard with the release keystore to
link the signing certificate to Noah's Nostr key (the C1 proof). If the CI key
is not that Nostr key, name it as a delegate under CI/CD publishing. Then add
the `ZAPSTORE_SIGN_WITH` secret. Until then the Zapstore job fails; the GitHub
Release is unaffected.

To publish an existing release, such as one made before setup, run `Native
Zapstore` manually with its `vX.Y.Z` tag. A version code Zapstore already has
is reported and skipped.

## iOS and migration setup

`Native Release` also archives `Noah` (Release-Mainnet) and `Noah-Signet`
(Release-Signet) on the self-hosted `macOS` runner, with the signing identity and
Xcode account the native Telegram workflow uses. The archive uploads its dSYMs to
Sentry. Download `noah-ios-mainnet` and `noah-ios-signet` from the run (kept 90
days), unpack each `.xcarchive.tar.gz`, open the archive in Xcode's Organizer and
choose Distribute App → App Store Connect to upload it to the matching app. The
build number is the repository's `CURRENT_PROJECT_VERSION`.

The GitHub Release does not wait for the iOS archives.
The existing server workflow still reacts to `v*` tags as before.

The React Native workflows are preserved as `.yml.bak` files and do not run.
Remove the old required `client_gate` check from branch protection before
merging this migration. Native CI remains active; choose replacement required
checks separately. React Native source and shared assets remain in the repo.

## Local release-tool checks

```sh
bun test scripts/native_release.test.ts
bun scripts/check_native_release.ts v1.0.0 # use the committed native version
actionlint .github/workflows/native-release.yml .github/workflows/native-android-artifacts.yml .github/workflows/native-release-checks.yml .github/workflows/native-zapstore.yml
```

`Native Release Checks` runs these version tests and workflow validation on PRs.
