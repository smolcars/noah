// bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-exits.ts
// RN sources are imported, never copied. Covers the exitFeeEstimate, exitTimeline,
// exitOverviewCoordination unit/integration cases plus boarding threshold boundaries.
import { mock } from "bun:test";
import { writeFileSync } from "node:fs";
globalThis.__DEV__ = false;
mock.module("noah-tools", () => ({ getAppVariant: () => "mainnet", isGooglePlayServicesAvailable: () => true, nativeLog: () => {} }));
mock.module("react-native", () => ({ Platform: { OS: "ios" }, AppState: {} }));
mock.module("react-native-fs-turbo", () => ({ default: { CachesDirectoryPath: "/tmp", DocumentDirectoryPath: "/tmp" } }));
mock.module("expo-device", () => ({ isDevice: true }));
const fees = await import("../../../client/src/lib/exitFeeEstimate");
const timeline = await import("../../../client/src/lib/exitTimeline");
const save = (name: string, cases: unknown[]) => writeFileSync(new URL(`../${name}.json`, import.meta.url), JSON.stringify({ source: "client/src/lib + client/tests/unit/exit*.test.js", cases }, null, 2) + "\n");
const cases: unknown[] = [];
const add = (operation: string, input: object, expected: unknown) => cases.push({ name: `${operation} ${cases.length}`, input: { operation, ...input }, expected });
for (const state of ["Spendable", "Locked", "Spent", "Exited", "unknown"]) for (const amount of [0, 1, 329, 330, 331, 100000])
 add("eligible", { state, amount }, fees.isExitEligible({ state, amount } as never));
const estimate = { exit_broadcast_fee_sat: 12000, claim_fee_sat: 800, total_fee_sat: 12800, fee_rate_sat_per_vb: 5, txs_to_broadcast: 2, fundable: false };
for (const amount of [100000, 800, 500, 12800]) for (const claimOnly of [false, true]) {
 add("receive", { amount, claimFee: 800 }, fees.exitReceiveAmount(amount, 800));
 add("warning", { amount, claimFee: 800, totalFee: 12800, claimOnly }, fees.exitFeeWarning(amount, estimate, claimOnly) ?? null);
}
const inputs = { walletId: "wallet-a", vtxoIds: ["a", "b"], amountSat: 100000, scope: "wallet", revision: "1" } as const;
const review = { inputs, contextKey: fees.exitEstimateKey(inputs as never), estimate, reviewedAt: 1000 };
for (const changed of [{}, {vtxoIds:["b","a"]}, {vtxoIds:["a"]}, {vtxoIds:["a","b","new-wallet-funds"]}, {scope:"selected"}, {amountSat:200000}, {walletId:"wallet-b"}, {destinationAddress:"destination-b"}, {revision:"2"}]) {
 for (const now of [1001, 30999, 31000, 31001]) {
  const current = {...inputs,...changed};
  add("current", { reviewedAt: 1000, now, inputs, current }, fees.isExitReviewCurrent(review as never, fees.exitEstimateKey(current as never), now));
 }
}
save("exit-fee-estimate", cases);
const cancellation = [];
for (const state of timeline.EXIT_STATE_ORDER) for (const kind of [null, "verify-inputs", "awaiting-input-confirmation", "awaiting-cpfp-broadcast", "awaiting-confirmation", "confirmed", "unknown"]) {
 const details = {kind:"processing", tip_height:100, transactions: kind ? [{txid:"ancestor",status:{kind:"confirmed"}}, {txid:"final",status:{kind}}] : []};
 cancellation.push({name:`${state} ${kind}`, input:{state,details}, expected:timeline.isCancelableExit(state,details as never)});
}
save("exit-cancellation", cancellation);
const timelines = [];
for (const history of [["Start","Canceled"], ["Start","Processing","Processing","AwaitingDelta","Claimable","ClaimInProgress","Claimed"], [], ["Processing","Processing"]]) {
 for (const currentState of ["Canceled","Claimed","Processing"] as const) {
  const historyDetails=history.map((state,i)=>({kind:state.toLowerCase(),tip_height:100+i}));
  const input={history,historyDetails,currentState,currentDetails:{kind:currentState.toLowerCase(),tip_height:110},currentBlockHeight:110};
  const expected=timeline.buildExitTimelineItems(input as never).map(({state,label,count,startHeight,endHeight,isCurrent})=>({state,label,count,startHeight,endHeight,isCurrent}));
  timelines.push({name:`timeline ${timelines.length}`, input, expected});
 }
}
save("exit-timeline", timelines);
// Capture the REAL RN query function to verify every availability combination and
// its read-only call boundary. Any mutation throws during fixture generation.
let wallet: Record<string, unknown>;
let reads: string[]=[];
mock.module("@tanstack/react-query",()=>({useQuery:(options:unknown)=>options,useMutation:(options:unknown)=>options}));
mock.module("gt-react-native",()=>({useGT:()=> (text:string)=>text}));
mock.module("@react-navigation/native",()=>({useIsFocused:()=>true}));
const useWalletStore = Object.assign(()=>wallet,{getState:()=>wallet});
mock.module("../../../client/src/store/walletStore",()=>({useWalletStore}));
mock.module("../../../client/src/contexts/AlertProvider",()=>({useAlert:()=>({})}));
mock.module("../../../client/src/lib/log",()=>({default:()=>({d(){},i(){}})}));
mock.module("../../../client/src/queryClient",()=>({queryClient:{}}));
const ok=(value:unknown)=>({isErr:()=>false,value});
const read=(name:string,value:unknown)=>async()=>{reads.push(name);return ok(value)};
mock.module("../../../client/src/hooks/useMarketData",()=>({getBlockHeight:read("blockHeight",100)}));
mock.module("../../../client/src/lib/walletApi",()=>({getVtxos:read("vtxos",[])}));
const action=()=>{throw Error("Overview must never mutate")};
mock.module("../../../client/src/lib/exitApi",()=>({cancelExit:action,claimExits:action,estimateEmergencyExitFee:action,progressExits:action,startExitForVtxos:action,syncExit:action,getExitVtxos:read("exits",[{vtxo_id:"exit-a"}]),getExitStatus:read("status",{state:"Processing"}),listClaimable:read("claimable",[]),hasPendingExits:read("pending",true),pendingExitTotal:read("total",1000),allClaimableAtHeight:read("height",110)}));
const {useExitOverview}=await import("../../../client/src/hooks/useUnilateralExit");
const coordination=[];
for(let flags=0;flags<16;flags++) {
 wallet={isInitialized:!!(flags&1),isWalletLoaded:!!(flags&2),isWalletSuspended:!!(flags&4),isBackgroundJobRunning:!!(flags&8),staticVtxoPubkey:"wallet-a"};
 reads=[];const options=useExitOverview() as never as {enabled:boolean;queryFn:()=>Promise<unknown>};
 let succeeds=true;try{await options.queryFn()}catch{succeeds=false}
 coordination.push({name:`availability ${flags}`,input:wallet,expected:{enabled:options.enabled,succeeds,reads:[...reads].sort()}});
}
save("exit-overview-coordination",coordination);
// Fees are test doubles; all threshold/iteration/reserve decisions run RN buildAutoBoardPlan.
let arkFee=100;let chainFee=208;
mock.module("../../../client/src/lib/paymentsApi",()=>({estimateBoardOffchainFee:async(gross:number)=>({isErr:()=>false,value:{gross_amount_sat:gross,fee_sat:arkFee,net_amount_sat:gross-arkFee}}),estimateStandardOnchainTxFee:async()=>({isErr:()=>false,value:{fee_sat:chainFee,fee_rate_sat_vb:1,estimated_vbytes:208}})}));
const {buildAutoBoardPlan}=await import("../../../client/src/lib/autoBoarding");
const boards=[];
for(const minimum of [10000,20000,25000]) for(const balance of [19999,25000,25307,25308,25309,30308,100000]) {
 const result=await buildAutoBoardPlan({arkInfo:{min_board_amount:minimum},confirmedOnchainBalanceSat:balance});
 if(result.isErr()) throw result.error;
 boards.push({name:`board ${minimum} ${balance}`,input:{minimum,balance,arkFee,chainFee},expected:result.value?.grossBoardAmountSat??null});
}
save("auto-boarding",boards);
