import { expect, test } from "bun:test";

test("wallet creation preserves existing wallets and safely retries fresh failures", async () => {
  // Keep native module mocks isolated from the rest of the unit suite.
  const process = Bun.spawn([Bun.argv[0], "test", "tests/integration/walletCreation.test.js"], {
    cwd: new URL("../..", import.meta.url).pathname,
    stdout: "pipe",
    stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    process.exited,
    new Response(process.stdout).text(),
    new Response(process.stderr).text(),
  ]);
  expect({ exitCode, output: exitCode === 0 ? "" : stdout + stderr }).toEqual({
    exitCode: 0,
    output: "",
  });
});
