const { withInfoPlist, withXcodeProject, withDangerousMod } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

// Expo prebuild does not preserve this repo's iOS setup on its own. This plugin
// keeps the native project aligned with app config by:
// - restoring placeholder-driven Info.plist values and Xcode build settings
// - preserving mainnet naming/icon/url-scheme behavior across targets
// - repairing the Sentry build phases for the hoisted workspace layout and
//   limiting debug-symbol upload to archive-only builds
const MAINNET_BUNDLE_ID = "com.noahwallet.mainnet";
const APP_SCHEME_PLACEHOLDER = "$(APP_SCHEME)";
const MARKETING_VERSION_PLACEHOLDER = "$(MARKETING_VERSION)";
const CURRENT_PROJECT_VERSION_PLACEHOLDER = "$(CURRENT_PROJECT_VERSION)";
const PRODUCT_NAME_PLACEHOLDER = "$(PRODUCT_NAME)";
const PRODUCT_MODULE_NAME_PLACEHOLDER = "$(PRODUCT_MODULE_NAME)";
const TARGET_NAME_PLACEHOLDER = '"$(TARGET_NAME)"';
const ICON_NAME = "noah";
const SENTRY_BUNDLE_SCRIPT = `if [[ -f "$PODS_ROOT/../.xcode.env" ]]; then
  source "$PODS_ROOT/../.xcode.env"
fi
if [[ -f "$PODS_ROOT/../.xcode.env.local" ]]; then
  source "$PODS_ROOT/../.xcode.env.local"
fi

# The project root by default is one level up from the ios directory
export PROJECT_ROOT="$PROJECT_DIR"/..

if [[ "$CONFIGURATION" = *Debug* ]]; then
  export SKIP_BUNDLING=1
fi
if [[ -z "$ENTRY_FILE" ]]; then
  # Set the entry JS file using the bundler's entry resolution.
  export ENTRY_FILE="$("$NODE_BINARY" -e "require('expo/scripts/resolveAppEntry')" "$PROJECT_ROOT" ios absolute | tail -n 1)"
fi

if [[ -z "$CLI_PATH" ]]; then
  # Use Expo CLI
  export CLI_PATH="$("$NODE_BINARY" --print "require.resolve('@expo/cli', { paths: [require.resolve('expo/package.json')] })")"
fi
if [[ -z "$BUNDLE_COMMAND" ]]; then
  # Default Expo CLI command for bundling
  export BUNDLE_COMMAND="export:embed"
fi

# Source .xcode.env.updates if it exists to allow
# SKIP_BUNDLING to be unset if needed
if [[ -f "$PODS_ROOT/../.xcode.env.updates" ]]; then
  source "$PODS_ROOT/../.xcode.env.updates"
fi
# Source local changes to allow overrides
# if needed
if [[ -f "$PODS_ROOT/../.xcode.env.local" ]]; then
  source "$PODS_ROOT/../.xcode.env.local"
fi

/bin/sh \`"$NODE_BINARY" --print "require('path').dirname(require.resolve('@sentry/react-native/package.json')) + '/scripts/sentry-xcode.sh'"\` \`"$NODE_BINARY" --print "require('path').dirname(require.resolve('react-native/package.json')) + '/scripts/react-native-xcode.sh'"\`

`;
const SENTRY_DEBUG_FILES_SCRIPT = `if [[ -f "$PODS_ROOT/../.xcode.env" ]]; then
  source "$PODS_ROOT/../.xcode.env"
fi
if [[ -f "$PODS_ROOT/../.xcode.env.local" ]]; then
  source "$PODS_ROOT/../.xcode.env.local"
fi

if [[ "$CONFIGURATION" = *Debug* ]]; then
  echo "Skipping Sentry debug files upload for Debug configuration"
  exit 0
fi

if [[ "$PLATFORM_NAME" == *simulator* ]]; then
  echo "Skipping Sentry debug files upload for simulator builds"
  exit 0
fi

if [[ -z "$ARCHIVE_PATH" ]]; then
  echo "Skipping Sentry debug files upload outside archive builds"
  exit 0
fi

if [[ -z "$NODE_BINARY" ]]; then
  export NODE_BINARY=$(command -v node)
fi

/bin/sh \`"$NODE_BINARY" --print "require('path').dirname(require.resolve('@sentry/react-native/package.json')) + '/scripts/sentry-xcode-debug-files.sh'"\`

`;

function isRecord(value) {
  return typeof value === "object" && value !== null;
}

function stripQuotes(value) {
  if (typeof value !== "string") return value;
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
    return value.slice(1, -1);
  }
  return value;
}

function getBuildSettings(configEntry) {
  if (!isRecord(configEntry)) return undefined;
  const buildSettings = configEntry.buildSettings;
  return isRecord(buildSettings) ? buildSettings : undefined;
}

function enforceDisplayNamePlaceholders(infoPlist) {
  infoPlist.CFBundleDisplayName = PRODUCT_NAME_PLACEHOLDER;
  // Work around https://github.com/expo/expo/pull/46424 when Xcode leaves a
  // hyphenated executable name unsanitized. Restore PRODUCT_NAME once fixed upstream.
  infoPlist.CFBundleName = PRODUCT_MODULE_NAME_PLACEHOLDER;
}

function enforceVersionPlaceholders(infoPlist) {
  infoPlist.CFBundleShortVersionString = MARKETING_VERSION_PLACEHOLDER;
  infoPlist.CFBundleVersion = CURRENT_PROJECT_VERSION_PLACEHOLDER;
}

function sanitizeUrlSchemes(infoPlist, { iosScheme }) {
  const existingUrlTypes = Array.isArray(infoPlist.CFBundleURLTypes)
    ? infoPlist.CFBundleURLTypes
    : [];
  const extraSchemes = new Set();
  for (const entry of existingUrlTypes) {
    if (!isRecord(entry)) continue;
    const schemes = entry.CFBundleURLSchemes;
    if (!Array.isArray(schemes)) continue;
    for (const scheme of schemes) {
      if (scheme === APP_SCHEME_PLACEHOLDER) continue;
      if (iosScheme && scheme === iosScheme) continue;
      if (scheme === MAINNET_BUNDLE_ID) continue;
      extraSchemes.add(scheme);
    }
  }
  const urlTypes = [
    {
      CFBundleURLSchemes: [APP_SCHEME_PLACEHOLDER],
    },
  ];
  if (extraSchemes.size > 0) {
    urlTypes.push({
      CFBundleURLSchemes: Array.from(extraSchemes),
    });
  }
  infoPlist.CFBundleURLTypes = urlTypes;
}

function fixMainnetProductName(project, { appName }) {
  const section = project.pbxXCBuildConfigurationSection();
  for (const [, entry] of Object.entries(section)) {
    const buildSettings = getBuildSettings(entry);
    if (!buildSettings) continue;

    const bundleId = stripQuotes(buildSettings.PRODUCT_BUNDLE_IDENTIFIER);
    const productName = stripQuotes(buildSettings.PRODUCT_NAME);

    if (appName && bundleId === MAINNET_BUNDLE_ID && productName === appName) {
      buildSettings.PRODUCT_NAME = TARGET_NAME_PLACEHOLDER;
    }
  }
}

function fixAppIconName(project) {
  const section = project.pbxXCBuildConfigurationSection();
  for (const [, entry] of Object.entries(section)) {
    const buildSettings = getBuildSettings(entry);
    if (!buildSettings) continue;
    if (buildSettings.ASSETCATALOG_COMPILER_APPICON_NAME !== undefined) {
      buildSettings.ASSETCATALOG_COMPILER_APPICON_NAME = ICON_NAME;
    }
  }
}

function syncVersionBuildSettings(project, { version, buildNumber }) {
  if (!version || !buildNumber) return;

  const section = project.pbxXCBuildConfigurationSection();
  for (const [, entry] of Object.entries(section)) {
    const buildSettings = getBuildSettings(entry);
    if (!buildSettings) continue;

    if (buildSettings.MARKETING_VERSION !== undefined) {
      buildSettings.MARKETING_VERSION = version;
    }

    if (buildSettings.CURRENT_PROJECT_VERSION !== undefined) {
      buildSettings.CURRENT_PROJECT_VERSION = buildNumber;
    }
  }
}

function fixSentryBuildPhaseScripts(project) {
  const section = project.hash.project.objects.PBXShellScriptBuildPhase;
  if (!isRecord(section)) return;

  for (const [, entry] of Object.entries(section)) {
    if (!isRecord(entry)) continue;

    const name = stripQuotes(entry.name);
    const shellScript = stripQuotes(entry.shellScript);

    if (
      name === "Bundle React Native code and images" &&
      typeof shellScript === "string" &&
      (shellScript.includes("@sentry/react-native") || shellScript.includes("sentry-xcode.sh"))
    ) {
      entry.shellScript = JSON.stringify(SENTRY_BUNDLE_SCRIPT);
      continue;
    }

    if (name === "Upload Debug Symbols to Sentry") {
      entry.shellScript = JSON.stringify(SENTRY_DEBUG_FILES_SCRIPT);
    }
  }
}

function addIconToAllTargets(project) {
  const iconFileName = `${ICON_NAME}.icon`;

  // Find the noah.icon PBXFileReference UUID
  const fileRefSection = project.pbxFileReferenceSection();
  let iconFileRefId = null;
  for (const [id, entry] of Object.entries(fileRefSection)) {
    if (typeof entry === "string") continue;
    if (!isRecord(entry)) continue;
    const name = stripQuotes(entry.name);
    if (name === iconFileName) {
      iconFileRefId = id;
      break;
    }
  }
  if (!iconFileRefId) return;

  // Collect existing PBXBuildFile UUIDs that reference noah.icon
  const buildFileSection = project.pbxBuildFileSection();
  const existingBuildFileIds = new Set();
  for (const [id, entry] of Object.entries(buildFileSection)) {
    if (typeof entry === "string") continue;
    if (isRecord(entry) && entry.fileRef === iconFileRefId) {
      existingBuildFileIds.add(id);
    }
  }

  // Find all Resources build phase IDs used by app targets
  const nativeTargets = project.pbxNativeTargetSection();
  const appResourcesPhaseIds = new Set();
  for (const [, target] of Object.entries(nativeTargets)) {
    if (typeof target === "string") continue;
    if (!isRecord(target)) continue;
    if (stripQuotes(target.productType) !== "com.apple.product-type.application") continue;
    for (const phase of target.buildPhases || []) {
      const phaseId = phase.value || phase;
      appResourcesPhaseIds.add(phaseId);
    }
  }

  // Add noah.icon to each app target's Resources phase that doesn't have it
  const resourcesSection = project.hash.project.objects["PBXResourcesBuildPhase"];
  if (!resourcesSection) return;

  for (const [phaseId, phase] of Object.entries(resourcesSection)) {
    if (typeof phase === "string") continue;
    if (!isRecord(phase)) continue;
    if (!appResourcesPhaseIds.has(phaseId)) continue;

    const files = phase.files || [];
    const alreadyHasIcon = files.some((f) => existingBuildFileIds.has(f.value || f));
    if (alreadyHasIcon) continue;

    const newId = project.generateUuid();
    buildFileSection[newId] = {
      isa: "PBXBuildFile",
      fileRef: iconFileRefId,
      fileRef_comment: iconFileName,
    };
    buildFileSection[`${newId}_comment`] = `${iconFileName} in Resources`;
    existingBuildFileIds.add(newId);

    phase.files.push({
      value: newId,
      comment: `${iconFileName} in Resources`,
    });
  }
}

function findSourceDir(iosDir) {
  for (const name of ["Noah", "noah"]) {
    const dir = path.join(iosDir, name);
    if (fs.existsSync(dir)) return dir;
  }
  return null;
}

function ensureAppiconset(iosDir) {
  const sourceDir = findSourceDir(iosDir);
  if (!sourceDir) return;

  const assetCatalog = path.join(sourceDir, "Images.xcassets");
  if (!fs.existsSync(assetCatalog)) return;

  // Ensure root Contents.json
  const rootContents = path.join(assetCatalog, "Contents.json");
  if (!fs.existsSync(rootContents)) {
    fs.writeFileSync(
      rootContents,
      JSON.stringify({ info: { author: "xcode", version: 1 } }, null, 2) + "\n",
    );
  }

  // Remove .icon from asset catalog if a previous plugin version put it there
  const staleIcon = path.join(assetCatalog, `${ICON_NAME}.icon`);
  if (fs.existsSync(staleIcon)) {
    fs.rmSync(staleIcon, { recursive: true });
  }

  // Rename AppIcon.appiconset → noah.appiconset so it matches ASSETCATALOG_COMPILER_APPICON_NAME
  const defaultAppiconset = path.join(assetCatalog, "AppIcon.appiconset");
  const targetAppiconset = path.join(assetCatalog, `${ICON_NAME}.appiconset`);

  if (fs.existsSync(defaultAppiconset) && !fs.existsSync(targetAppiconset)) {
    fs.renameSync(defaultAppiconset, targetAppiconset);
  }

  // Ensure the appiconset directory exists with a valid Contents.json
  if (!fs.existsSync(targetAppiconset)) {
    fs.mkdirSync(targetAppiconset, { recursive: true });
  }

  const contentsPath = path.join(targetAppiconset, "Contents.json");
  if (!fs.existsSync(contentsPath)) {
    fs.writeFileSync(
      contentsPath,
      JSON.stringify(
        {
          images: [{ idiom: "universal", platform: "ios", size: "1024x1024" }],
          info: { author: "xcode", version: 1 },
        },
        null,
        2,
      ) + "\n",
    );
  }
}

function withNoahIosPrebuildFix(config) {
  const appName = config.name;
  const iosScheme = Array.isArray(config.ios?.scheme) ? config.ios.scheme[0] : config.ios?.scheme;
  const appVersion = config.version;
  const iosBuildNumber = config.ios?.buildNumber;

  config = withInfoPlist(config, (modConfig) => {
    const infoPlist = modConfig.modResults;
    enforceDisplayNamePlaceholders(infoPlist);
    enforceVersionPlaceholders(infoPlist);
    sanitizeUrlSchemes(infoPlist, { iosScheme });
    return modConfig;
  });

  config = withXcodeProject(config, (modConfig) => {
    const project = modConfig.modResults;
    fixMainnetProductName(project, { appName });
    fixAppIconName(project);
    syncVersionBuildSettings(project, {
      version: appVersion,
      buildNumber: iosBuildNumber,
    });
    fixSentryBuildPhaseScripts(project);
    addIconToAllTargets(project);
    return modConfig;
  });

  config = withDangerousMod(config, [
    "ios",
    async (modConfig) => {
      const iosDir = modConfig.modRequest.platformProjectRoot;
      ensureAppiconset(iosDir);
      return modConfig;
    },
  ]);

  return config;
}

module.exports = withNoahIosPrebuildFix;
