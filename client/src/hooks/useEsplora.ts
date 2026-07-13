import { useMutation } from "@tanstack/react-query";
import { useBackgroundJobCoordination } from "~/hooks/useBackgroundJobCoordination";
import { switchEsploraEndpoint } from "~/lib/walletApi";
import { queryClient } from "~/queryClient";

export const useSwitchEsploraEndpoint = () => {
  const { safelyExecuteWhenReady } = useBackgroundJobCoordination();

  return useMutation({
    mutationFn: async (endpointOverride: string | null) => {
      return safelyExecuteWhenReady(async () => {
        const result = await switchEsploraEndpoint(endpointOverride);
        if (result.isErr()) {
          throw result.error;
        }
        return result.value;
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries();
    },
  });
};
