import { expect, test } from "bun:test";

// Native and React mocks must not leak into other unit tests.
test("auto-boarding deposit and submission regressions", async () => {
  const proc = Bun.spawn([process.execPath, "test", "tests/integration/autoBoarding.test.js"], {
    cwd: new URL("../..", import.meta.url).pathname,
    stdout: "pipe",
    stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    proc.exited,
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
  ]);
  expect({ exitCode, output: exitCode === 0 ? "" : stdout + stderr }).toEqual({
    exitCode: 0,
    output: "",
  });
});
