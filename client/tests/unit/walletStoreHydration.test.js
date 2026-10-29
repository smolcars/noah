import { expect, test } from "bun:test";

test("wallet readiness is local to the running process", async () => {
  // Keep native storage mocks isolated from the other unit tests.
  const proc = Bun.spawn([process.execPath, "test", "tests/integration/walletStoreHydration.test.js"], {
    cwd: new URL("../../", import.meta.url).pathname,
    stdout: "pipe",
    stderr: "pipe",
  });
  const [stdout, stderr, exitCode] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  expect(exitCode, `${stdout}\n${stderr}`).toBe(0);
});
