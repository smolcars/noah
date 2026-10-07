import { expect, test } from "bun:test";
import { canShowCompanionPane } from "../../src/lib/adaptiveLayout";

test("companion panes follow usable space and text size across resize transitions", () => {
  expect([0, 360, 543, 544, 900, 360].map((width) => canShowCompanionPane(width, 1))).toEqual([
    false, false, false, true, true, false,
  ]);
  expect(canShowCompanionPane(700, 1.5)).toBe(false);
  expect(canShowCompanionPane(804, 1.5)).toBe(true);
  expect(canShowCompanionPane(500, 0.8)).toBe(false);
});
