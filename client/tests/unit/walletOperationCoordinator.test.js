import { beforeEach, describe, expect, test } from "bun:test";

let nativeLeaseOwner = null;
let nativeAcquireCount = 0;
let nativeReleaseCount = 0;
const { createForegroundWalletOperationRunner } = await import(
  "../../src/lib/walletOperationCoordinatorCore"
);

let ownerSequence = 0;
const runForegroundWalletOperation = createForegroundWalletOperationRunner({
  tryAcquire: (owner) => {
    nativeAcquireCount += 1;
    if (nativeLeaseOwner !== null) {
      return false;
    }
    nativeLeaseOwner = owner;
    return true;
  },
  release: (owner) => {
    nativeReleaseCount += 1;
    if (nativeLeaseOwner === owner) {
      nativeLeaseOwner = null;
    }
  },
  waitForBackgroundJob: async () => {},
  waitBeforeRetry: async () => {
    nativeLeaseOwner = null;
  },
  createOwner: () => {
    ownerSequence += 1;
    return `foreground:${ownerSequence}`;
  },
});

beforeEach(() => {
  nativeLeaseOwner = null;
  nativeAcquireCount = 0;
  nativeReleaseCount = 0;
  ownerSequence = 0;
});

describe("foreground wallet coordination", () => {
  test("waits until a native background lease is released", async () => {
    nativeLeaseOwner = "periodic-sync";
    let operationStarted = false;

    const operation = runForegroundWalletOperation(async () => {
      operationStarted = true;
    });

    await operation;

    expect(operationStarted).toBe(true);
    expect(nativeAcquireCount).toBe(2);
    expect(nativeReleaseCount).toBe(1);
  });

  test("shares one native lease across concurrent foreground operations", async () => {
    let finishFirst;
    const first = runForegroundWalletOperation(
      () =>
        new Promise((resolve) => {
          finishFirst = resolve;
        }),
    );

    await new Promise((resolve) => setTimeout(resolve, 0));
    const second = runForegroundWalletOperation(async () => "second");
    expect(await second).toBe("second");
    expect(nativeAcquireCount).toBe(1);
    expect(nativeReleaseCount).toBe(0);

    finishFirst();
    await first;
    expect(nativeReleaseCount).toBe(1);
    expect(nativeLeaseOwner).toBeNull();
  });
});
