import { execFileSync } from "node:child_process";

export const projectId = "8847a6a1-bf07-4321-a62b-9839e9954e46";
export const environmentId = "37880684-30ae-4242-af12-ddba1ba620ad";
export const services = {
  api: "2dac716c-bcd0-4c2c-ab77-6f3226548ca8",
  barkd: "0250c7b1-1916-44e0-81e7-afc947c4c5ad",
};

// Releases own the image pins; infrastructure applies must preserve their live values.
export function liveImages(): { api: string; barkd: string } {
  const query = `query {
    api: serviceInstance(environmentId: "${environmentId}", serviceId: "${services.api}") { source { image } }
    barkd: serviceInstance(environmentId: "${environmentId}", serviceId: "${services.barkd}") { source { image } }
  }`;
  const response = JSON.parse(execFileSync("railway", ["api", query, "--compact"], { encoding: "utf8" }));
  const api: unknown = response.data?.api?.source?.image;
  const barkd: unknown = response.data?.barkd?.source?.image;
  if (
    typeof api !== "string" || !/^niteshbalusu\/noah-server@sha256:[a-f0-9]{64}$/.test(api) ||
    typeof barkd !== "string" || !/^niteshbalusu\/noah-barkd@sha256:[a-f0-9]{64}$/.test(barkd)
  ) {
    throw new Error("Expected existing signet API and Barkd image digests; refusing to reset their sources.");
  }
  return { api, barkd };
}
