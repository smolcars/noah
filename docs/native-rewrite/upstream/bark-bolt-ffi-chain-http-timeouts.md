# Draft: bound chain HTTP requests and support cancelling pending wallet opens

Status: issue draft only; not filed.

Affected SDK: bark-bolt-ffi 0.5.0, Swift and Kotlin bindings.

## Problem

A configured Esplora endpoint can accept a TCP connection and never return its
response headers or finish its body. `OnchainWallet.openExisting` then waits on
chain-source initialization, including its genesis request. In 0.5.0,
`src/core/onchain.rs` constructs `ChainSource::new(chain_spec, network, None)`;
the FFI config exposes no chain HTTP deadlines. An application validation request
can succeed immediately before the SDK's separate request hangs. The review
reproduction observed a 12.35-second open; a server that never responds can hold
it indefinitely.

This affects normal application launch as well as changing an endpoint. A wallet
app must close its existing handles before reopening the same datadir. Cancelling
the Swift task or coroutine awaiting FFI does not establish that native open has
stopped or released its database handles. Starting a fallback open immediately
would risk two owners of the same datadir.

## Requested behavior

- Expose connect, read-idle, and total request deadlines in the Esplora/chain HTTP
  configuration, with finite defaults. A total deadline must include headers,
  streamed bodies, retries, and redirects: a slow drip must not reset it.
- Apply these bounds to chain-source initialization/genesis requests and all
  later chain HTTP work, on both bindings.
- Support cancellation of pending opens. A completed cancellation must mean all
  spawned work is stopped and partial wallet/onchain handles are disposed, so the
  application can safely reopen that datadir. Document this completion guarantee.
- Return typed timeout/cancellation errors that contain no complete endpoint URL.
  URLs can carry credentials in opaque path segments; SDK logs should report the
  origin, excluding userinfo, paths, queries, and fragments.
- Add offline regressions for accepted connections without headers, stalled and
  drip-fed bodies, retries, cancellation during initialization, and reopening the
  same datadir after cancellation completes.

## Local reproduction

Use a disposable, already-created signet wallet datadir. Do not use a real wallet
or any public Esplora server. Start the following loopback-only fake. The first
connection test's genesis and tip requests succeed; subsequent requests accept a
connection and deliberately never answer.

```python
import socketserver
import threading

GENESIS = b"00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
lock = threading.Lock()
requests = 0
hold = threading.Event()

class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        global requests
        request = self.request.recv(8192)
        with lock:
            requests += 1
            number = requests
        if number > 2:
            hold.wait()  # Deliberately no headers, body, or disconnect.
            return
        body = GENESIS if b"/block-height/0 " in request else b"123"
        self.request.sendall(
            b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "
            + str(len(body)).encode() + b"\r\n\r\n" + body)

class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True

with Server(("127.0.0.1", 0), Handler) as server:
    print("Local endpoint:", "http://127.0.0.1:" + str(server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        hold.set()
```

Run the two application validation requests against that endpoint, close the
wallet, and call `OnchainWallet.openExisting` with `esploraAddress` set to it and
Bitcoin Core disabled. Observe that SDK open does not finish after an application
12-second deadline. Cancelling only the language-level waiter cannot authorize a
second open of that datadir. Stop the fake to disconnect the pending request and
let native open unwind. For a launch reproduction, make the fake silent from its
first request instead.

Noah's local tests use accepted silent sockets, explicitly disconnect them after
asserting the UI deadline/reservation behavior, and wait for the worker to finish.
They do not leave a permanently hanging FFI call behind.

## Current application mitigation and limit

Noah bounds the UI wait to 12 seconds. On reload timeout it releases the lifecycle
and background gates but retains the closed-session reservation in an independent
worker. A late candidate can never commit; after open returns, successfully or
with an error, the worker tears down any candidate handles and automatically
reopens the previous endpoint. Pending settings are journaled before closing;
rollback reopening does not depend on journal cleanup writes succeeding.

Custom-server launch offers “Use default server”. This saves a default selection
(or retains it in foreground settings memory if storage is unavailable), without
changing wallet files, and queues the fallback behind the pending native call.
The reservation remains held until that call returns and fallback finishes.

The application cannot guarantee a usable reopened wallet while an uninterruptible
native call never returns. The UI remains responsive, competing opens are refused,
and a restart can use the persisted default choice, but upstream deadlines and
cancellation are required to finish recovery in the same process in that case.
