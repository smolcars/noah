const path = require("path");
const { withUniwindConfig } = require("uniwind/metro");
const { getSentryExpoConfig } = require("@sentry/react-native/metro");

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, "..");

const config = getSentryExpoConfig(__dirname);

config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, "node_modules"),
  path.resolve(workspaceRoot, "node_modules"),
];

config.resolver.extraNodeModules = {
  "~": projectRoot,
};

// Prevent duplicate Nitro linkage and keep unit tests out of production bundles
config.resolver.blockList = [
  /node_modules\/.*\/node_modules\/react-native-nitro-modules\/.*/,
  /[/\\]tests[/\\].*/,
];

module.exports = withUniwindConfig(config, {
  cssEntryFile: "./global.css",
  dtsFile: "./uniwind-env.d.ts",
  polyfills: {
    rem: 14, // Match NativeWind's native default
  },
});
