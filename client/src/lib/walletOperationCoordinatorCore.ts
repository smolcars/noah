export type ForegroundWalletLeaseAdapter = {
  tryAcquire: (owner: string) => boolean;
  release: (owner: string) => void;
  waitForBackgroundJob: () => Promise<void>;
  waitBeforeRetry: () => Promise<void>;
  createOwner: () => string;
};

export const createForegroundWalletOperationRunner = (adapter: ForegroundWalletLeaseAdapter) => {
  let leaseOwner: string | null = null;
  let leaseUsers = 0;
  let leasePromise: Promise<void> | null = null;

  const acquireLease = async () => {
    leaseUsers += 1;

    if (!leasePromise) {
      const owner = adapter.createOwner();
      leasePromise = (async () => {
        while (!adapter.tryAcquire(owner)) {
          await adapter.waitBeforeRetry();
        }
        leaseOwner = owner;
      })();
    }

    try {
      await leasePromise;
    } catch (error) {
      leaseUsers -= 1;
      if (leaseUsers === 0) {
        leasePromise = null;
        leaseOwner = null;
      }
      throw error;
    }
  };

  const releaseLease = () => {
    leaseUsers -= 1;
    if (leaseUsers > 0) {
      return;
    }

    if (leaseOwner) {
      adapter.release(leaseOwner);
    }
    leaseOwner = null;
    leasePromise = null;
    leaseUsers = 0;
  };

  return async <T>(operation: () => Promise<T>): Promise<T> => {
    // Nested and concurrent foreground work shares one lease. This is required
    // for flows that invalidate and refetch queries before the outer operation
    // completes.
    if (leaseUsers === 0) {
      await adapter.waitForBackgroundJob();
    }

    await acquireLease();
    try {
      return await operation();
    } finally {
      releaseLease();
    }
  };
};
