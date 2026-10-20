# Draft: Swift async notification polling can race cancellation in 0.5.0

Not filed upstream. Package: `smolcars/bark-bolt-ffi` tag `v0.5.0`,
Swift 6, Xcode 27.1, arm64 iOS Simulator. No funded wallet or server is required.

## Reproduction

Create an offline regtest wallet (`createWithoutServer: true`, `runDaemon: false`,
`skipRecovery: true`). Obtain `wallet.notifications()`, start a Swift task that
awaits `holder.nextNotification()`, then cancel that task while stopping the
holder with `cancelNextNotificationWait()`. Await the task before releasing the
holder/wallet. Repeat: the timing window is small.

In Noah, the original `WalletSession.stopObservers()` did `task.cancel()`,
`holder.cancelNextNotificationWait()`, then `await task.value`. The test is
`NoahTests/WalletSessionTests/closeStopsReceiveObserversAndWaitsForThem()`;
`repeatedReceiveObserverTeardown()` repeats it 50 times. Use signed arm64
simulator builds, no parallel testing, and the trailing `()` selector.

## Observed stack

Two existing host crash reports (2026-10-20 07:18:13 and 07:42:47) contain this
faulting notification-wait stack; the reported Rust panic is `called after complete`:

```text
NotificationHolder.next_notification async closure
boltffi_core::runtime::future::RustFuture<T>::poll
boltffi_async_method_class_bark_bolt_ffi_core_notification_notification_holder_next_notification_poll
@nonobjc ...next_notification_poll
BoltFFIAsyncPollDriver.start()
```

The crash happens while polling, rather than in Noah's stream continuation.
The matching 50-repetition samples had **0/50 failures before and 0/50 after**;
this sample did not reproduce the crash or establish a measured reduction.
Additional pending-wait, consumer-cancellation and registration-gap regressions
pass. Full results and limitations are recorded in `apps/ios/PlatformTests/README.md`.

## Likely cause (source inspection, not a proven runtime interleaving)

In `sdk/swift/Sources/BoltFFI/BarkBoltFfiBoltFFI.swift` at tag `v0.5.0`:

- `NotificationHolder.nextNotification()` (line 2856) and
  `StateChangeSubscription.nextChange()` (2914) use `boltffiAsyncCall`.
- `BoltFFIAsyncPollDriver.handle` (7375) checks `canPoll()`, schedules a new
  unstructured task, yields, then calls `start()`. `start()` (7367) polls the raw
  future handle without a fresh terminal-state check or lifetime lease.
- `boltffiAsyncCall` (7391) installs a cancellation handler (7438) which marks
  state finished, calls native cancel/free, and resumes the continuation.
  Normal readiness also marks finished and calls complete/free.

The continuation's atomic finished marker prevents a second Swift resume, but
it does not serialize raw-handle polling against cancel/complete/free. A repoll
already scheduled before cancellation may run after the future is terminal or
freed. A poll arriving at an already-completed Rust async body is consistent
with the panic above; this is not proof that the completion export ran twice.

Rust `src/core/notification.rs` installs its cancellation channel only when
`next_notification` is polled. `cancel_next_notification_wait` has no effect
before registration, so one native cancellation call alone cannot close that gap.
`src/core/state_changes.rs` uses the same per-wait cancellation mechanism.

## Suggested upstream fix

Make poll scheduling and terminal transitions share ownership of the future.
Prevent queued polls after termination, retain the native future until every
in-flight poll/callback is quiescent, and invoke complete/free once. Rechecking
`canPoll()` after yielding reduces the window but does not alone serialize a
concurrent check/poll against free. Add driver-level tests that pause a queued
repoll, cancel the foreign task, then resume it, including cancellation before
the initial poll, concurrent readiness/cancellation, and both notification APIs.

A holder-level latched stop (including waits not yet registered) would also make
application teardown simpler, but would not replace fixing future lifetime.

## Noah workaround and Android comparison

Noah stops the native wait and lets its detached Swift reader finish normally;
it never cancels that reader's Swift task. It retries native cancellation every
5 ms until the reader marks itself finished, covering registration races, then
awaits the task before wallet teardown. Consumer cancellation requests the same
stop. Both receive and state-change observers use this path.

Android also calls native stop/cancel-wait and cancels its observer coroutine,
then joins before closing the wallet. The shipped 0.5.0 AAR's `boltffiCallAsync`
was inspected with `javap -c -p`: polling is a sequential coroutine loop,
`BoltFfiAsync.resume` removes the continuation handle once, cancellation removes
that same handle before native cancel, and the coroutine frees in `finally`.
It has no independent queued `BoltFFIAsyncPollDriver` task that can repoll after
coroutine termination. Existing tests cover close, consumer cancellation,
pre-registration cancellation, state-change shutdown and immediate close.
Android is therefore retained unchanged for observer teardown; JVM fakes do not
prove all JNI/native cancellation races absent. No Android native stress run or
physical-device test was performed in this task.
