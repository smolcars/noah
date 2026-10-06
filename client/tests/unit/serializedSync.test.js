import { describe, expect, test } from "bun:test";
import { err, ok } from "neverthrow";

import { createSerializedSync } from "../../src/lib/serializedSync";

/** A fake server that stores the last list it received and lets tests control timing. */
const createHarness = () => {
  let local = [];
  let server = [];
  const pendingFlags = [];
  const requests = [];

  const send = (payload) =>
    new Promise((resolve) => {
      requests.push({
        payload,
        resolve: (result = ok(undefined)) => {
          if (result.isOk()) server = payload;
          resolve(result);
        },
      });
    });

  const sync = createSerializedSync({
    getPayload: () => [...local],
    send,
    onSettled: (pending) => pendingFlags.push(pending),
  });

  return {
    sync,
    requests,
    pendingFlags,
    setLocal: (next) => {
      local = next;
    },
    server: () => server,
  };
};

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("serialized recurring payment sync", () => {
  test("an overlapping change is synced after the running request, never lost", async () => {
    const h = createHarness();

    // 1. A sync starts with just [Rent].
    h.setLocal(["rent"]);
    const first = h.sync();
    await flush();
    expect(h.requests).toHaveLength(1);

    // 2. Donation is added while that request is waiting.
    h.setLocal(["rent", "donation"]);
    const second = h.sync();
    await flush();
    // Only one request may be in flight at a time.
    expect(h.requests).toHaveLength(1);

    // 3. The first request finishes; the latest list is sent next.
    h.requests[0].resolve();
    await flush();
    expect(h.requests).toHaveLength(2);
    expect(h.requests[1].payload).toEqual(["rent", "donation"]);
    // Not marked as synced yet: the server doesn't have the latest list.
    expect(h.pendingFlags).toEqual([]);

    h.requests[1].resolve();
    expect((await first).isOk()).toBe(true);
    expect((await second).isOk()).toBe(true);

    expect(h.server()).toEqual(["rent", "donation"]);
    expect(h.pendingFlags).toEqual([false]);
  });

  test("a local change without a new sync call is still picked up", async () => {
    const h = createHarness();
    h.setLocal(["rent"]);
    const done = h.sync();
    await flush();

    h.setLocal([]);
    h.requests[0].resolve();
    await flush();
    expect(h.requests).toHaveLength(2);
    expect(h.requests[1].payload).toEqual([]);

    h.requests[1].resolve();
    await done;
    expect(h.server()).toEqual([]);
    expect(h.pendingFlags).toEqual([false]);
  });

  test("a failed request keeps the sync pending", async () => {
    const h = createHarness();
    h.setLocal(["rent"]);
    const done = h.sync();
    await flush();
    h.requests[0].resolve(err(new Error("offline")));

    expect((await done).isErr()).toBe(true);
    expect(h.pendingFlags).toEqual([true]);
  });

  test("a call after a finished sync starts a new request", async () => {
    const h = createHarness();
    h.setLocal(["rent"]);
    const first = h.sync();
    await flush();
    h.requests[0].resolve();
    await first;

    h.setLocal(["rent", "donation"]);
    const second = h.sync();
    await flush();
    expect(h.requests).toHaveLength(2);
    h.requests[1].resolve();
    await second;
    expect(h.server()).toEqual(["rent", "donation"]);
  });
});
