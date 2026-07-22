import { describe, expect, test } from "bun:test";

import { isFailedOrCanceledMovement } from "../../src/lib/barkMovement";

describe("Bark movement status", () => {
  test("treats failed and canceled movements as terminal failures", () => {
    expect(isFailedOrCanceledMovement({ status: "failed" })).toBe(true);
    expect(isFailedOrCanceledMovement({ status: "canceled" })).toBe(true);
  });

  test("does not treat pending or successful movements as failures", () => {
    expect(isFailedOrCanceledMovement({ status: "pending" })).toBe(false);
    expect(isFailedOrCanceledMovement({ status: "successful" })).toBe(false);
  });
});
