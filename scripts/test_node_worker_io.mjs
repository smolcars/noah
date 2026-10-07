import assert from "node:assert/strict";
import { once } from "node:events";
import { Worker } from "node:worker_threads";

// Metro uses worker threads; broken Nix Node builds corrupt file descriptors.
const worker = new Worker(
  `
    const fs = require('node:fs');
    const { parentPort } = require('node:worker_threads');
    let warnings = 0;
    process.on('warning', warning => {
      if (/File descriptor.*unmanaged mode/.test(warning.message)) warnings++;
    });
    for (let i = 0; i < 100; i++) {
      const fd = fs.openSync('/dev/null', 'r');
      fs.readSync(fd, Buffer.alloc(1), 0, 1, 0);
      fs.closeSync(fd);
    }
    setImmediate(() => parentPort.postMessage(warnings));
  `,
  { eval: true, stderr: true },
);
const exited = once(worker, "exit");
const [warnings] = await once(worker, "message");
assert.equal(warnings, 0, "Node worker file descriptor tracking is broken");
assert.equal((await exited)[0], 0);
console.log("Node worker file I/O regression passed");
