import { queryClient } from "~/queryClient";

type WalletQueryInvalidationOptions = {
  includePendingRounds?: boolean;
  includeTransactions?: boolean;
};

export const invalidateWalletDerivedQueries = async ({
  includePendingRounds = false,
  includeTransactions = true,
}: WalletQueryInvalidationOptions = {}) => {
  const invalidations = [
    queryClient.invalidateQueries({ queryKey: ["balance"] }),
    queryClient.invalidateQueries({ queryKey: ["vtxos"] }),
    queryClient.invalidateQueries({ queryKey: ["expiring-vtxos"] }),
  ];

  if (includeTransactions) {
    invalidations.push(queryClient.invalidateQueries({ queryKey: ["transactions"] }));
  }

  if (includePendingRounds) {
    invalidations.push(queryClient.invalidateQueries({ queryKey: ["pending-rounds"] }));
  }

  await Promise.all(invalidations);
};
