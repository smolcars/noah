import { expect, test } from "bun:test";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import config from "../../gt.config.json";
import { loadTranslations } from "../../loadTranslations";

test("every configured target locale has bundled translations", async () => {
  for (const locale of config.locales) {
    const translations = await loadTranslations(locale);
    expect(Object.keys(translations).length).toBeGreaterThan(0);
  }
});

test("source and unsupported locales use the original copy", async () => {
  expect(await loadTranslations(config.defaultLocale)).toEqual({});
  expect(await loadTranslations("unsupported")).toEqual({});
});

// Extract the real source offline so new copy cannot silently ship without its catalog entry.
test("bundled translations cover every GT source entry", async () => {
  const clientRoot = fileURLToPath(new URL("../../", import.meta.url));
  const directory = await mkdtemp(resolve(tmpdir(), "noah-translation-coverage-"));
  try {
    const configPath = resolve(directory, "gt.config.json");
    await writeFile(
      configPath,
      JSON.stringify({
        ...config,
        src: config.src.map((path) => resolve(clientRoot, path)),
        files: { gt: { output: resolve(directory, "[locale].json") } },
      }),
    );
    const cli = fileURLToPath(new URL("../bin/main.js", import.meta.resolve("gt")));
    const proc = Bun.spawn(
      [process.execPath, cli, "generate", "--config", configPath, "--omit-config-ids"],
      {
        cwd: clientRoot,
        stdout: "ignore",
        stderr: "pipe",
      },
    );
    const [exitCode, stderr] = await Promise.all([proc.exited, new Response(proc.stderr).text()]);
    if (exitCode !== 0) throw new Error(stderr);
    const source = JSON.parse(
      await readFile(resolve(directory, `${config.defaultLocale}.json`), "utf8"),
    );
    expect(Object.keys(source).length).toBeGreaterThan(0);
    for (const locale of config.locales) {
      const translations = await loadTranslations(locale);
      expect(Object.keys(source).filter((key) => translations[key] == null)).toEqual([]);
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}, 30_000);

for (const [name, file] of [
  ["background notifications resolve the persisted locale without React", "backgroundTranslations"],
  ["locale resolution does not restart receive effects", "localizedReceive"],
  ["locale resolution does not restart auto-boarding estimates", "localizedAutoBoarding"],
]) {
test(name, async () => {
  const proc = Bun.spawn(
    [process.execPath, "test", `tests/integration/${file}.test.js`],
    {
      cwd: new URL("../../", import.meta.url).pathname,
      stdout: "ignore",
      stderr: "pipe",
    },
  );
  const [exitCode, stderr] = await Promise.all([proc.exited, new Response(proc.stderr).text()]);
  if (exitCode !== 0) throw new Error(stderr);
});
}

// App language must control display formatting independently of the device language.
test("Spanish Bitcoin amounts use Spanish number formatting", async () => {
  const { formatBitcoinAmount } = await import("../../src/lib/bitcoinAmount");
  expect(formatBitcoinAmount(1234567, "sats", "es")).toBe("1.234.567 sats");
  expect(formatBitcoinAmount(1234567, "bip177", "en")).toBe("₿\u00a01,234,567");
});
