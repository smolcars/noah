import { expect, test } from "bun:test";

// Keep native module mocks isolated from the other wallet unit tests.
test("exit overview queries respect wallet availability", async () => {
  const proc = Bun.spawn([process.execPath, "test", "tests/integration/exitOverview.test.js"], {
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
