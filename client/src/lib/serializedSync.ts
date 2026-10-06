/**
 * Runs a "replace the whole list on the server" sync one request at a time.
 *
 * Each request overwrites the server's entire list, so two overlapping requests
 * can finish out of order and leave the server with an older list. This helper
 * keeps at most one request in flight. Calls made while a request is running
 * share it, and once it finishes the latest local payload is sent again if it
 * changed in the meantime. `onSettled(false)` (i.e. nothing pending) is only
 * reported once the server has the latest payload.
 *
 * Kept free of React Native imports so it can be unit tested with `bun test`.
 */
import { err, ok, type Result } from "neverthrow";

/** Guards against a payload that keeps changing on every request. */
const MAX_ROUNDS = 5;

export type SerializedSyncOptions<T> = {
  getPayload: () => T;
  send: (payload: T) => Promise<Result<void, Error>>;
  /** Called with `true` when the server may be out of date, `false` when it is in sync. */
  onSettled: (pending: boolean) => void;
  isEqual?: (a: T, b: T) => boolean;
};

const jsonEqual = <T>(a: T, b: T) => JSON.stringify(a) === JSON.stringify(b);

export function createSerializedSync<T>({
  getPayload,
  send,
  onSettled,
  isEqual = jsonEqual,
}: SerializedSyncOptions<T>): () => Promise<Result<void, Error>> {
  let running: Promise<Result<void, Error>> | null = null;
  let rerunRequested = false;

  // `running` is cleared synchronously together with the final decision, so a
  // call that arrives later always starts a new request instead of joining one
  // that has already decided it is done.
  const finish = (result: Result<void, Error>, pending: boolean) => {
    running = null;
    onSettled(pending);
    return result;
  };

  const loop = async (): Promise<Result<void, Error>> => {
    // Yield once so `running` is assigned before any request (or `finish`) runs.
    await Promise.resolve();
    for (let round = 0; round < MAX_ROUNDS; round += 1) {
      rerunRequested = false;
      const payload = getPayload();
      let result: Result<void, Error>;
      try {
        result = await send(payload);
      } catch (e) {
        result = err(e instanceof Error ? e : new Error(String(e)));
      }
      if (result.isErr()) return finish(err(result.error), true);
      if (!rerunRequested && isEqual(payload, getPayload())) return finish(ok(undefined), false);
    }
    return finish(err(new Error("Sync payload kept changing; will retry later")), true);
  };

  return () => {
    if (running) {
      rerunRequested = true;
      return running;
    }
    const current = loop();
    running = current;
    return current;
  };
}
