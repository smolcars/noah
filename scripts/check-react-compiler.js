import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { transformSync } from "@babel/core";

const clientRoot = fileURLToPath(new URL("../client/", import.meta.url));
const files = [
  "App.tsx",
  "index.ts",
  ...new Bun.Glob("src/**/*.{js,jsx,ts,tsx}").scanSync({ cwd: clientRoot }),
].filter((file) => !file.endsWith(".d.ts"));
let failures = 0;

// The build compiler reports bailouts that the ESLint plugin filters out.
for (const file of files) {
  transformSync(readFileSync(`${clientRoot}${file}`, "utf8"), {
    filename: `${clientRoot}${file}`,
    configFile: false,
    babelrc: false,
    code: false,
    ast: false,
    parserOpts: { sourceType: "module", plugins: ["typescript", "jsx"] },
    plugins: [
      [
        "babel-plugin-react-compiler",
        {
          noEmit: true,
          logger: {
            logEvent(_filename, event) {
              if (
                event.kind === "CompileError" ||
                event.kind === "CompileDiagnostic"
              ) {
                failures++;
                const location = event.detail.loc?.start ?? event.fnLoc?.start;
                console.error(
                  `${file}:${location?.line ?? 1}:${(location?.column ?? 0) + 1}: ${event.detail.reason}`,
                );
              } else if (event.kind === "PipelineError") {
                failures++;
                console.error(`${file}: ${event.data}`);
              }
            },
          },
        },
      ],
    ],
  });
}

console.log(
  `React Compiler: checked ${files.length} files, ${failures} diagnostics.`,
);
process.exitCode = failures > 0 ? 1 : 0;
