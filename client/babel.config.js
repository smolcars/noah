const path = require("path");
const { plugin: gtPlugin } = require("gt-react-native/plugin");
const gtConfig = require("./gt.config.json");

module.exports = function (api) {
  api.cache(true);
  return {
    presets: ["babel-preset-expo"],
    plugins: [
      [
        gtPlugin,
        {
          locales: [gtConfig.defaultLocale, ...gtConfig.locales],
          entryPointFilePath: path.resolve(__dirname, "index.ts"),
        },
      ],
      [
        "babel-plugin-react-compiler",
        {
          logger: {
            logEvent: (filename, event) => {
              if (event.kind === "CompileSuccess") {
                console.log("✨ React Compiler successfully compiled: ", filename);
              }

              if (event.kind === "CompileError") {
                console.warn("⚠️ React Compiler failed to compile: " + filename);
                console.warn(filename, JSON.stringify(event, null, 2));
              }
            },
          },
        },
      ],
      "react-native-worklets/plugin",
    ],
  };
};
