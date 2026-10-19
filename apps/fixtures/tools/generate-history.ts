// bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-history.ts
// Original cases: client/tests/unit/{transactionHistory,barkMovement}.test.js.
import { mock } from "bun:test";
import { readFileSync, writeFileSync } from "node:fs";
mock.module("noah-tools", () => ({ getAppVariant: () => "regtest" }));
mock.module("../../../client/src/lib/utils", () => ({ formatNumber: () => { throw new Error("Unused formatting dependency"); } }));
const history = await import("../../../client/src/lib/transactionHistory");
const repeat = await import("../../../client/src/lib/repeatPayment");
const bark = await import("../../../client/src/lib/barkMovement");
const { INCOMING_MOVEMENT_KINDS } = await import("../../../client/src/types/movement");
const { validateBitcoinAddress } = await import("bip-321");

// Execute the hook's actual pure transforms without React, native IO, or market requests.
// No copy of its mapping/sorting implementation lives in this generator.
const source = readFileSync(new URL("../../../client/src/hooks/useTransactions.ts", import.meta.url), "utf8")
  .replace(/^import[\s\S]*?;\n/gm, "")
  .split("export const useTransactions =")[0];
const dependencies = { ...history, ...repeat, ...bark, INCOMING_MOVEMENT_KINDS, validateBitcoinAddress,
  logger: () => ({ w() {}, e() {} }), getEsploraApiBaseUrl: () => undefined,
  getHistoricalBtcToFiatRate: async () => ({ isOk: () => false }),
};
const transforms = new Function(...Object.keys(dependencies), new Bun.Transpiler({ loader: "ts" }).transformSync(source) +
  "\nreturn { transformMovementToTransaction, transformOnchainTransaction, compareTransactions, shouldIncludeMovement, shouldIncludeOnchainTransaction };")(...Object.values(dependencies));
const cases: { name: string; input: unknown; expected: unknown }[] = [];
const add = (name: string, operation: string, input: object, expected: unknown) => cases.push({ name, input: { operation, ...input }, expected });
const tx = (fields: object) => ({ id: "movement-7", type: "Onchain", amount: 50000,
  date: "2026-07-15T12:00:00.000Z", direction: "incoming", ...fields });
const movement = (fields: object = {}) => ({ id: 7, status: "successful", subsystem: { name: "bark.board", kind: "board" },
  intended_balance_sat: 50000, effective_balance_sat: 49500, offchain_fee_sat: 500,
  metadata_json: "{}", sent_to: [], received_on: [], input_vtxos: [], output_vtxos: [], exited_vtxos: [],
  created_at: "2026-07-15T12:00:00.000Z", ...fields });
const fiat = { destination: "merchant@example.com", comment: "Order 307", amountMode: "FIAT" as const,
  amountInput: "12.50", amountSat: 10000, fiatCurrency: "USD" as const };
for (const [name, value] of [
  ["extracts chain transaction and fees", { offboard_txid: "offboard-txid", onchain_fee_sat: 123, chain_anchor: "board-txid:1" }],
  ["round trips fiat lightning address", JSON.parse(repeat.buildRepeatPaymentMetadataPatch(fiat))],
  ["ignores array metadata", []], ["ignores mistyped fields", { offboard_txid: 1, onchain_fee_sat: true }],
  ["ignores future repeat version", { noah: { repeat_payment: { version: 2 } } }],
] as const) {
  const metadataJson = JSON.stringify(value);
  add(name, "metadata", { metadataJson }, history.parseMovementMetadata(metadataJson));
}
add("invalid JSON", "metadata", { metadataJson: "{" }, history.parseMovementMetadata("{"));
for (const [name, metadata, outgoing, fields] of [
  ["board anchor outpoint", { chainAnchor: "funding-txid:1" }, false, {}],
  ["offboard takes precedence", { offboardTxid: "offboard", chainAnchor: "board:1" }, true, {}],
  ["outgoing chooses input", {}, true, { input_vtxos: ["in"], output_vtxos: ["out"] }],
  ["incoming chooses output", {}, false, { input_vtxos: ["in"], output_vtxos: ["out"] }],
  ["empty IDs fall back to movement", {}, false, { output_vtxos: [""] }],
] as const) {
  const m = movement(fields);
  add(name, "transactionID", { movement: m, metadata, outgoing }, history.getMovementTransactionId(m, metadata, outgoing));
}
for (const [name, fields] of [
  ["prefers intended boarding amount", { sent_to: [{ amount_sat: 49000 }] }],
  ["uses effective boarding amount", { intended_balance_sat: 0 }],
  ["uses sent boarding amount", { intended_balance_sat: 0, effective_balance_sat: 0, sent_to: [{ amount_sat: 2100 }] }],
  ["uses received boarding amount", { intended_balance_sat: 0, effective_balance_sat: 0, received_on: [{ amount_sat: 2200 }] }],
] as const) {
  const m = movement(fields);
  add(name, "boardAmount", { movement: m }, history.getBoardingMovementAmount(m));
}
for (const [name, input] of [
  ["fiat metadata resolves", { metadata: history.parseMovementMetadata(repeat.buildRepeatPaymentMetadataPatch(fiat)).repeatPayment, amountSat: 10000 }],
  ["legacy lightning address", { destination: "LEGACY@EXAMPLE.COM", paymentMethod: "lightning-address", amountSat: 2100 }],
  ["trim Ark destination", { destination: "  tark1recipient  ", paymentMethod: "ark", amountSat: 5000 }],
  ["cannot repeat invoice", { destination: "lnbc1invoice", paymentMethod: "bolt11", amountSat: 5000 }],
  ["cannot repeat zero", { destination: "tark1recipient", paymentMethod: "ark", amountSat: 0 }],
] as const) add(name, "resolveRepeat", input, repeat.resolveRepeatPaymentDetails(input) ?? null);
for (const currency of ["USD", "EUR"] as const)
  add(`prefill in ${currency}`, "prefill", { details: fiat, currency }, repeat.getRepeatPaymentPrefill(fiat, currency));
for (const direction of ["incoming", "outgoing"] as const) for (const status of ["successful", "pending", "failed", "canceled"]) {
  const transaction = tx({ direction, movementStatus: status, repeatPayment: fiat });
  add(`repeat ${direction} ${status}`, "canRepeat", { transaction }, repeat.canRepeatPayment(transaction));
}
for (const transaction of [
  tx({ movementKind: "onboard" }), tx({ movementKind: "offboard", direction: "outgoing" }),
  tx({ movementKind: "exit", movementStatus: "canceled", direction: "outgoing", amount: 92590 }),
  tx({ type: "Bolt11", movementStatus: "canceled" }), tx({ type: "Arkoor", direction: "outgoing", amount: 21000 }),
  tx({ type: "Lnurl" }), tx({ type: "Onchain" }),
]) add(`accounting ${transaction.type} ${transaction.movementKind ?? ""} ${transaction.movementStatus ?? ""}`, "presentation", { transaction }, {
  label: history.getTransactionDisplayLabel(transaction), canceled: history.isCanceledTransaction(transaction),
  internalTransfer: history.isInternalBoardingTransfer(transaction), accounting: history.getTransactionAccountingValues(transaction),
});
const board = tx({ source: "ark", txid: "funding-txid", movementKind: "onboard", btcPrice: 100000 });
const chain = tx({ id: "onchain-wallet-funding-txid", source: "onchain-wallet", amount: 50500, direction: "outgoing",
  txid: "funding-txid", txHex: "deadbeef", balanceChangeSat: -50500, hasOnchainFee: true, onchainFeeSat: 500, hasConfirmation: false });
for (const kind of ["onboard", "offboard", "send-onchain"]) {
  const movements = [{ ...board, movementKind: kind }];
  const onchain = [chain, { ...chain, id: "onchain-wallet-other-txid", txid: "other-txid" }];
  add(`merge ${kind}`, "merge", { movements, onchain }, history.mergeBoardingWithOnchainTransactions(movements, onchain));
}
const subsystemCases = Object.values(bark.BARK_SUBSYSTEM);
for (const subsystem of [...subsystemCases, { name: "BARK.ARKOOR", kind: "RECEIVE" }, { name: "unknown", kind: "test" }]) {
  for (const status of ["successful", "failed", "canceled", "pending"]) {
    const incoming = subsystem.kind.toLowerCase() === "receive" || subsystem.kind === "board";
    const m = movement({ subsystem, status, intended_balance_sat: incoming ? 50000 : -50000, effective_balance_sat: incoming ? 49500 : -50500,
      [incoming ? "received_on" : "sent_to"]: [{ destination: "tark1recipient", amount_sat: 49000, payment_method: "ark" }] });
    const mapped = await transforms.transformMovementToTransaction(m, "USD");
    delete mapped.description; // Always empty in RN; Swift has no unused description field.
    add(`${subsystem.name}:${subsystem.kind} ${status}`, "movement", { movement: m }, { included: transforms.shouldIncludeMovement(m), transaction: mapped });
  }
}
for (const [name, fields] of [
  ["bitcoin destination overrides Ark subsystem", { subsystem: { name: "bark.arkoor", kind: "send" }, sent_to: [{ destination: "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", amount_sat: 1000, payment_method: "bitcoin" }] }],
  ["lightning address metadata overrides Ark route", { subsystem: { name: "bark.arkoor", kind: "send" }, metadata_json: repeat.buildRepeatPaymentMetadataPatch(fiat), sent_to: [{ destination: "tark1recipient", amount_sat: 10000, payment_method: "ark" }] }],
  ["zero change maintenance excluded", { subsystem: { name: "bark.round", kind: "refresh" }, intended_balance_sat: 0, effective_balance_sat: 0 }],
] as const) {
  const m = movement(fields);
  const mapped = await transforms.transformMovementToTransaction(m, "USD"); delete mapped.description;
  add(name, "movement", { movement: m }, { included: transforms.shouldIncludeMovement(m), transaction: mapped });
}
for (const has_confirmation of [true, false]) for (const balance_change_sat of [21000, -21000, 0]) {
  const onchain = { txid: "chain-tx", tx_hex: "deadbeef", balance_change_sat, has_onchain_fee: true, onchain_fee_sat: 500,
    has_confirmation, confirmation_height: 42, confirmation_hash: "block-hash" };
  const mapped = await transforms.transformOnchainTransaction(onchain, "USD"); delete mapped.description;
  add(`onchain ${has_confirmation} ${balance_change_sat}`, "onchain", { onchain }, { included: transforms.shouldIncludeOnchainTransaction(onchain), transaction: mapped });
}
const sorted = [board, chain, tx({ id: "confirmed-no-time", source: "onchain-wallet", hasConfirmation: true, sortHeight: 4, date: "1970-01-01T00:00:00.000Z" }),
  tx({ id: "new", sortTimestamp: Date.parse("2026-07-16T12:00:00.000Z") })];
add("pending onchain first then dated then height", "sort", { transactions: sorted }, [...sorted].sort(transforms.compareTransactions).map(t => t.id));
for (const [routeMethod, comment] of [["ark", null], ["ark", "Order 307"], ["lightning", null]] as const)
  add(`comment routing ${routeMethod} ${comment}`, "repeatRoute", { routeMethod, comment }, repeat.shouldUseArkDirectLightningAddressRoute(routeMethod, comment));
for (const existingMovementIds of [[8], [9]]) {
  const movements = [movement({ id: 9, subsystem: { name: "bark.arkoor", kind: "send" }, sent_to: [{ destination: "tark-destination", payment_method: "ark", amount_sat: 3000 }] })];
  const input = { existingMovementIds, movements, destination: "tark-destination", amountSat: 3000 };
  add(`find new matching Ark movement ${existingMovementIds}`, "newMovement", { ...input, rawMovements: movements, movements: undefined },
    repeat.findNewArkoorMovementId({ ...input, existingMovementIds: new Set(existingMovementIds) }) ?? null);
}
const barkCases = ["failed", "canceled", "pending", "successful"].map(status => ({
  name: `${status} terminal failure`, input: { status }, expected: bark.isFailedOrCanceledMovement({ status }),
}));
for (const [name, values] of [["transaction-history", cases], ["bark-movement", barkCases]] as const)
  writeFileSync(new URL(`../${name}.json`, import.meta.url), JSON.stringify({
    description: "Generated by tools/generate-history.ts by executing the RN TypeScript. Includes the history/barkMovement unit cases and SDK mapping parity cases.", cases: values,
  }, null, 2) + "\n");
