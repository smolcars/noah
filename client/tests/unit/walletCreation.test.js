import { expect, test } from "bun:test";

test.each([
  ["ios", "/documents"],
  ["ios", "/documents/files"],
  ["android", "/data/user/0/com.noahwallet/files"],
])("wallet creation and deletion on %s with %s", async (platform, documentPath) => {
  // Keep native module mocks isolated from the rest of the unit suite.
  const subprocess = Bun.spawn([Bun.argv[0], "test", "tests/integration/walletCreation.test.js"], {
    cwd: new URL("../..", import.meta.url).pathname,
    env: {
      ...process.env,
      NOAH_TEST_PLATFORM: platform,
      NOAH_TEST_DOCUMENT_PATH: documentPath,
    },
    stdout: "pipe",
    stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    subprocess.exited,
    new Response(subprocess.stdout).text(),
    new Response(subprocess.stderr).text(),
  ]);
  expect({ exitCode, output: exitCode === 0 ? "" : stdout + stderr }).toEqual({
    exitCode: 0,
    output: "",
  });
});
