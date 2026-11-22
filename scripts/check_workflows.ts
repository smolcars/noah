// Fails on GitHub Actions mistakes that once made a job skip while the workflow stayed green.
// Each rule names the commit that fixed the mistake it catches; scripts/check_workflows.test.ts replays it.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

export type Problem = { file: string; line: number; rule: string; message: string };
// Top-level tracked path -> workflow files (basenames) whose path filters must match a change to it.
export type Triggers = Record<string, string[]>;

export const TRIGGERS_FILE = ".github/workflow-triggers.json";
const FILTERED_EVENTS = ["pull_request", "pull_request_target", "push"];
// No head_ref: it is empty outside pull requests, so it is per ref only with a fallback such as `|| github.ref`.
const PER_REF = /github\.(ref|ref_name|run_id)\b|pull_request\.number/;

type Filter = { label: string; patterns: string[]; ignore: boolean; line: number };

const lineOf = (lines: string[], re: RegExp, from = 0) => {
  const i = lines.findIndex((l, n) => n >= from && re.test(l));
  return i < 0 ? from + 1 : i + 1;
};
const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// GitHub's own pattern rules, which differ from a shell glob: "**" crosses "/" even inside a
// segment ("**.yml"), and "?" and "+" repeat the character before them.
function githubPattern(pattern: string) {
  let re = "";
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    const close = c === "[" ? pattern.indexOf("]", i) : -1;
    if (pattern.startsWith("**/", i)) { re += "(?:.*/)?"; i += 2; }
    else if (pattern.startsWith("**", i)) { re += ".*"; i += 1; }
    else if (c === "*") re += "[^/]*";
    else if (c === "?" || c === "+") re += c;
    else if (close > i) { re += pattern.slice(i, close + 1); i = close; }
    else if (c === "\\") re += escape(pattern[++i] ?? "");
    else re += escape(c);
  }
  return new RegExp(`^${re}$`);
}

// dorny/paths-filter matches with picomatch, whose globs Bun.Glob follows; on: filters use GitHub's rules.
const patternMatches = (filter: Filter, pattern: string, file: string) =>
  filter.label.startsWith("on.") ? githubPattern(pattern).test(file) : new Bun.Glob(pattern).match(file);

// GitHub's path filter: patterns apply in order and the last one that matches decides.
export function filterMatches(filter: Filter, file: string) {
  let hit = false;
  for (const p of filter.patterns) {
    const negated = p.startsWith("!");
    if (patternMatches(filter, negated ? p.slice(1) : p, file)) hit = !negated;
  }
  return filter.ignore ? !hit : hit;
}

function filtersOf(doc: any, lines: string[]): Filter[] {
  const filters: Filter[] = [];
  const on = typeof doc.on === "object" && !Array.isArray(doc.on) ? doc.on : {};
  const onLine = lineOf(lines, /^on:/) - 1;
  for (const event of FILTERED_EVENTS) {
    for (const key of ["paths", "paths-ignore"]) {
      const patterns = on[event]?.[key];
      if (!Array.isArray(patterns)) continue;
      const eventLine = lineOf(lines, new RegExp(`^\\s+${event}:`), onLine) - 1;
      filters.push({
        label: `on.${event}.${key}`, patterns, ignore: key === "paths-ignore",
        line: lineOf(lines, new RegExp(`^\\s+${key}:`), eventLine),
      });
    }
  }
  for (const job of Object.values<any>(doc.jobs ?? {})) {
    for (const step of job.steps ?? []) {
      if (!String(step.uses ?? "").startsWith("dorny/paths-filter") || typeof step.with?.filters !== "string") continue;
      const stepLine = lineOf(lines, /dorny\/paths-filter/) - 1;
      const parsed = Bun.YAML.parse(step.with.filters);
      // shortcut: a filters file path is skipped, not read; read it if a workflow ever uses one.
      if (typeof parsed !== "object" || !parsed) continue;
      for (const [name, value] of Object.entries<any>(parsed)) {
        // An item may be a change-type object such as { "added|modified": "server/**" }.
        const patterns = [value].flat().flatMap(v => typeof v === "object" && v ? Object.values(v).flat() : [v]);
        filters.push({
          label: `dorny/paths-filter "${name}"`, patterns: patterns.map(String), ignore: false,
          line: lineOf(lines, new RegExp(`^\\s+${escape(name)}:\\s*$`), stepLine),
        });
      }
    }
  }
  return filters;
}

function checkWorkflow(file: string, text: string, files: string[]): Problem[] {
  const problems: Problem[] = [];
  const lines = text.split("\n");
  const doc: any = Bun.YAML.parse(text) ?? {};
  const add = (line: number, rule: string, message: string) => problems.push({ file, line, rule, message });

  // 0eb5a07c7: release signing steps tested this, were always skipped, and the build still passed.
  // Parsed conditions, so a comment does not fire it and a folded if: spread over lines does.
  const jobsLine = lineOf(lines, /^jobs:/);
  for (const [name, job] of Object.entries<any>(doc.jobs ?? {})) {
    let from = lineOf(lines, new RegExp(`^\\s+${escape(name)}:`), jobsLine) - 1;
    for (const condition of [job.if, ...(job.steps ?? []).map((s: any) => s.if)]) {
      if (!/github\.event_name\s*[!=]=\s*['"]workflow_call['"]|['"]workflow_call['"]\s*[!=]=\s*github\.event_name/.test(String(condition ?? ""))) continue;
      from = lineOf(lines, /^[^#]*workflow_call/, from);
      add(from, "event-name-workflow-call",
        "github.event_name is never 'workflow_call': a called workflow sees its caller's event. " +
        "Test the event you mean instead, e.g. replace github.event_name == 'workflow_call' with github.event_name != 'pull_request'.");
    }
  }

  const filters = filtersOf(doc, lines);
  for (const f of filters) {
    // 0badadef7, 6cb6db2e4, 0b6dfe311: editing the workflow did not run it.
    if (!filterMatches(f, file)) {
      add(f.line, "filter-misses-own-workflow",
        `${f.label} does not match ${file}, so a change to this workflow does not run it. ` +
        `Add - "${file}" to the list, and remove any "!" pattern that excludes it.`);
    }
    // 7f63705da: a filter kept naming client/gt.config.json after the file was gone.
    for (const p of f.patterns.filter(p => !p.startsWith("!"))) {
      if (!files.some(t => patternMatches(f, p, t))) {
        add(lineOf(lines, new RegExp(escape(p)), f.line - 1), "filter-matches-no-file",
          `${f.label} pattern "${p}" matches no tracked file. Delete the line, or change it to where the file moved.`);
      }
    }
  }

  // d2ef12862: a workflow skipped by its paths never ran its gate job, so the required check never reported.
  const prPaths = filters.find(f => /^on\.pull_request(_target)?\./.test(f.label));
  const gate = Object.entries<any>(doc.jobs ?? {}).find(([, j]) => String(j.if ?? "").includes("always()") && j.needs);
  if (prPaths && gate) {
    add(prPaths.line, "gate-behind-workflow-paths",
      `${prPaths.label} skips the whole workflow, so the gate job "${gate[0]}" never reports and a required check waits forever. ` +
      `Remove ${prPaths.label} and filter in a job with dorny/paths-filter that the gate needs, as server.yml's "changes" job does.`);
  }

  // 4a1c032f3 (squashed into 3a4dea331): a dispatch on another branch replaced master's pending run, then skipped its job.
  const group = String(doc.concurrency?.group ?? doc.concurrency ?? "");
  const refJob = Object.entries<any>(doc.jobs ?? {}).find(([, j]) => /github\.ref(_name|_type)?\b/.test(String(j.if ?? "")));
  if (group && !PER_REF.test(group) && refJob) {
    const at = lineOf(lines, /^concurrency:/);
    add(typeof doc.concurrency === "string" ? at : lineOf(lines, /^\s+group:/, at), "concurrency-group-without-ref",
      `concurrency group "${group}" is shared by every ref, and GitHub replaces a group's pending run with any newer one; ` +
      `a run on another ref skips job "${refJob[0]}" yet drops the queued run that needed it. ` +
      `Add the ref, e.g. group: ${group}-\${{ github.ref }}`);
  }
  return problems;
}

// f0b1546f8: the image build did not run when only the Dockerfile changed.
function checkTriggers(workflows: Record<string, string>, files: string[], table: string): Problem[] {
  const problems: Problem[] = [];
  const triggers: Triggers = JSON.parse(table);
  const add = (file: string, line: number, rule: string, message: string) => problems.push({ file, line, rule, message });
  const tableLines = table.split("\n");
  const tops = [...new Set(files.map(f => f.split("/")[0]))].sort();
  for (const top of tops) {
    if (!(top in triggers)) {
      add(TRIGGERS_FILE, 1, "trigger-table",
        `top-level path "${top}" is not listed. Add "${top}": [] if no workflow reads it, or the workflows whose filters must match it.`);
    }
  }
  for (const [top, names] of Object.entries(triggers)) {
    const line = lineOf(tableLines, new RegExp(`^\\s*"${escape(top)}":`));
    if (!tops.includes(top)) {
      add(TRIGGERS_FILE, line, "trigger-table", `"${top}" is not a tracked top-level path. Delete its entry.`);
      continue;
    }
    const under = files.filter(f => f === top || f.startsWith(`${top}/`));
    for (const name of names) {
      const file = `.github/workflows/${name}`;
      if (!(file in workflows)) {
        add(TRIGGERS_FILE, line, "trigger-table", `"${top}" names ${name}, which does not exist. Fix or delete the name.`);
        continue;
      }
      const lines = workflows[file].split("\n");
      const filters = filtersOf(Bun.YAML.parse(workflows[file]) ?? {}, lines);
      const events = filters.filter(f => f.label.startsWith("on."));
      const dorny = filters.filter(f => !f.label.startsWith("on."));
      const missed = events.filter(f => !under.some(u => filterMatches(f, u)));
      if (dorny.length && !dorny.some(f => under.some(u => filterMatches(f, u)))) missed.push(...dorny);
      for (const f of missed) {
        add(file, f.line, "filter-misses-input",
          `${f.label} matches nothing under "${top}", but ${TRIGGERS_FILE} says ${name} reads it, so a change there alone skips this workflow. ` +
          `Add - "${files.includes(top) ? top : `${top}/**`}" (or a narrower pattern for the files it reads).`);
      }
    }
  }
  return problems;
}

export function checkWorkflows(input: { workflows: Record<string, string>; files: string[]; triggers?: string }) {
  const files = [...new Set([...input.files, ...Object.keys(input.workflows)])];
  return [
    ...Object.entries(input.workflows).flatMap(([file, text]) => checkWorkflow(file, text, files)),
    ...(input.triggers ? checkTriggers(input.workflows, files, input.triggers) : []),
  ];
}

export const format = (p: Problem) => `${p.file}:${p.line}: [${p.rule}] ${p.message}`;

if (import.meta.main) {
  // Optional root lets a reviewer lint an old checkout against today's rules and table.
  const root = path.resolve(process.argv[2] ?? path.join(import.meta.dir, ".."));
  const dir = path.join(root, ".github/workflows");
  const workflows = Object.fromEntries(fs.readdirSync(dir).filter(f => /\.ya?ml$/.test(f))
    .map(f => [`.github/workflows/${f}`, fs.readFileSync(path.join(dir, f), "utf8")]));
  const files = execFileSync("git", ["ls-files"], { cwd: root, encoding: "utf8" }).split("\n").filter(Boolean);
  const triggers = fs.readFileSync(path.join(import.meta.dir, "..", TRIGGERS_FILE), "utf8");
  const problems = checkWorkflows({ workflows, files, triggers });
  for (const p of problems) console.error(format(p));
  if (problems.length) process.exit(1);
  console.log(`Checked ${Object.keys(workflows).length} workflows.`);
}
