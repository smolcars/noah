import { expect, mock, test } from "bun:test";

let locale = "es";
let cursor = 0;
const slots = [];
const focusCallbacks = [];
const onReceiveComplete = () => {};
const generateAddresses = async () => ({ arkAddress: "ark", onchainAddress: "onchain" });
const generateInvoice = async () => ({});
const showAlert = () => {};

// Exercise the hook's real dependency chain with React's callback identity rules.
mock.module("react", () => ({
  useState: (initial) => [initial, () => {}],
  useRef: (initial) => {
    const index = cursor++;
    return (slots[index] ??= { current: initial });
  },
  useCallback: (callback, dependencies) => {
    const index = cursor++;
    const previous = slots[index];
    if (previous && dependencies.every((value, i) => Object.is(value, previous.dependencies[i]))) {
      return previous.callback;
    }
    slots[index] = { callback, dependencies };
    return callback;
  },
  useEffect: () => {},
}));
mock.module("gt-react-native", () => ({
  // The SDK's development resolver currently returns a fresh callback on each render.
  useGT: () => (text) => `${locale}:${text}`,
}));
mock.module("@react-navigation/native", () => ({
  useFocusEffect: (callback) => focusCallbacks.push(callback),
}));
mock.module("../../src/contexts/AlertProvider", () => ({ useAlert: () => ({ showAlert }) }));
mock.module("../../src/hooks/usePayments", () => ({
  useGenerateReceiveAddresses: () => ({ mutateAsync: generateAddresses, isPending: false }),
  useGenerateLightningInvoice: () => ({ mutateAsync: generateInvoice, isPending: false }),
}));
mock.module("../../src/lib/log", () => ({ default: () => ({ d() {}, w() {}, e() {} }) }));
mock.module("../../src/lib/paymentsApi", () => ({
  subscribeArkoorAddressMovements: () => {},
  subscribeLightningPaymentMovements: () => {},
}));
mock.module("../../src/queryClient", () => ({ queryClient: {} }));
const { useReceiveRequest } = await import("../../src/hooks/useReceiveRequest");

test("translation callback changes do not restart the receive focus effect", () => {
  const render = () => {
    cursor = 0;
    return useReceiveRequest(onReceiveComplete);
  };
  render();
  render();
  expect(focusCallbacks[1]).toBe(focusCallbacks[0]);
  locale = "en";
  render();
  render();
  expect(focusCallbacks[3]).toBe(focusCallbacks[2]);
});
