import { expect, mock, test } from "bun:test";
import * as React from "react";
import { ok } from "neverthrow";

let cursor = 0;
let needsRender = false;
let rendered;
const slots = [];
const effects = [];
const plans = [];
const balance = { onchain: { confirmed: 20_000 } };
const arkInfo = { min_board_amount: 20_000 };
const showAlert = mock(() => {});

// Model state updates, effect dependencies and cleanup while rendering the real component.
mock.module("react", () => ({
  ...React,
  memo: (component) => component,
  useState: (initial) => {
    const index = cursor++;
    slots[index] ??= {
      value: initial,
      set: (value) => {
        const next = typeof value === "function" ? value(slots[index].value) : value;
        if (!Object.is(next, slots[index].value)) {
          slots[index].value = next;
          needsRender = true;
        }
      },
    };
    return [slots[index].value, slots[index].set];
  },
  useRef: (initial) => {
    const index = cursor++;
    return (slots[index] ??= { current: initial });
  },
  useEffect: (effect, dependencies) => {
    const index = cursor++;
    const previous = slots[index];
    if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i]))) {
      effects.push(() => {
        previous?.cleanup?.();
        slots[index] = { dependencies, cleanup: effect() };
      });
    }
  },
}));
mock.module("gt-react-native", () => ({
  // Match the installed SDK's fresh development callback on every render.
  useGT: () => (text) => `es:${text}`,
  T: () => null,
  Var: () => null,
}));
mock.module("react-native", () => ({ View: () => null }));
mock.module("../../src/components/ConfirmationDialog", () => ({
  ConfirmationDialog: () => null,
}));
mock.module("../../src/components/ui/text", () => ({ Text: () => null }));
mock.module("../../src/contexts/AlertProvider", () => ({ useAlert: () => ({ showAlert }) }));
mock.module("../../src/hooks/usePayments", () => ({
  useBoardArk: () => ({ mutate: () => {}, isPending: false }),
}));
mock.module("../../src/hooks/useWallet", () => ({
  useBalance: () => ({ data: balance }),
  useArkInfo: () => ({ data: arkInfo, isError: false }),
}));
mock.module("../../src/lib/autoBoarding", () => ({
  AUTO_BOARD_FLOOR_AMOUNT: 20_000,
  buildAutoBoardPlan: (input) => new Promise((resolve) => plans.push({ input, resolve })),
}));
mock.module("../../src/lib/log", () => ({ default: () => ({ d() {}, e() {} }) }));
mock.module("../../src/lib/utils", () => ({ cn: (...values) => values.join(" ") }));
mock.module("../../src/hooks/useBitcoinAmountFormatter", () => ({
  useBitcoinAmountFormatter: () => (amount) => `${amount} sats`,
}));
const store = {
  isAutoBoardingEnabled: true,
  hasAttemptedAutoBoarding: false,
  setAutoBoardingEnabled() {},
  setHasAttemptedAutoBoarding() {},
  setAutoBoardSuccessBanner() {},
};
mock.module("../../src/store/transactionStore", () => ({ useTransactionStore: () => store }));
const { AutoBoardingService } = await import("../../src/components/AutoBoardingService");

function render() {
  let count = 0;
  do {
    needsRender = false;
    cursor = 0;
    rendered = AutoBoardingService({ isReady: true });
    while (effects.length) effects.shift()();
    if (++count > 20) throw new Error("Auto-boarding render loop");
  } while (needsRender);
}

const completePlan = async (index, plan) => {
  plans[index].resolve(ok(plan));
  await new Promise((resolve) => setTimeout(resolve, 0));
  render();
};

test("loading renders and ineligible estimates do not restart auto-boarding", async () => {
  render();
  expect(plans).toHaveLength(1);
  await completePlan(0, null);
  for (let i = 0; i < 3; i++) render();
  expect(plans).toHaveLength(1);
  expect(rendered.props.open).toBe(false);
  expect(showAlert).not.toHaveBeenCalled();

  // A real balance change must still trigger an estimate and open its confirmation.
  balance.onchain.confirmed = 30_000;
  render();
  expect(plans).toHaveLength(2);
  expect(plans[1].input.confirmedOnchainBalanceSat).toBe(30_000);
  await completePlan(1, {
    confirmedOnchainBalanceSat: 30_000,
    minimumNetBoardAmountSat: 20_000,
    minimumRequiredBalanceSat: 26_000,
    grossBoardAmountSat: 24_000,
    arkFeeSat: 100,
    netBoardAmountSat: 23_900,
    estimatedOnchainFeeSat: 1_000,
    onchainBufferSat: 5_000,
    estimatedRemainingOnchainSat: 5_000,
    feeRateSatVb: 1,
    estimatedVbytes: 1_000,
  });
  expect(plans).toHaveLength(2);
  expect(rendered.props.open).toBe(true);
});
