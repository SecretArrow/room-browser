'use strict';

// The runtime's entry script: one line of JSON in, one line of JSON out.
//
// It also installs the user-agent rule, which is why it is not just a
// dispatcher. Every request this runtime makes has to carry the profile's own
// identity: a package or script reaching a server as `node` would be the one
// thing on this device that is not the profile, and the profile is what the
// whole browser exists to present. The rule wraps the request APIs rather than
// passing a header at each call site, so a package that never heard of it
// obeys it too. A script that builds a request on a raw `tls`/`net` socket, or
// speaks HTTP by hand, is outside what these wrappers can see.

const net = require('net');
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2);
const socketPath = argv[0];
const token = argv[1];

// ---- the runtime's own account of itself ---------------------------------
//
// One line of JSON per stage, written beside this script, from the first
// statement on. The failure it exists for is a caller waiting for a socket that
// never appears: without it, that side can only say the runtime did not start,
// and a runtime that never ran, one that stopped before listening and one that
// bound something else all read exactly the same. A write that fails costs the
// diagnosis, never the run.

const STATUS_FILE = path.join(__dirname, 'status.json');

function report(stage, extra) {
  try {
    fs.writeFileSync(
      STATUS_FILE,
      JSON.stringify(Object.assign({ stage: stage, at: Date.now(), pid: process.pid }, extra))
    );
  } catch (err) {
    // Deliberately silent: this is a diagnostic, not part of the protocol.
  }
}

process.on('uncaughtException', (err) => {
  report('threw', { error: err && err.message ? err.message : String(err) });
  process.exit(1);
});

let cwd;
try {
  cwd = process.cwd();
} catch (err) {
  cwd = 'unreadable: ' + (err && err.message ? err.message : String(err));
}
report('loading', { argv: process.argv.slice(1), node: process.version, cwd: cwd });

if (!socketPath || !token) {
  report('no-arguments', {});
  process.stderr.write('room-node: no socket name or token was given\n');
  process.exit(2);
}

let userAgent = null;
let enforcementInstalled = false;

function currentUserAgent() {
  if (!userAgent) {
    throw new Error(
      'no user agent for this profile: every request Node makes has to carry the ' +
        "profile's own identity, so nothing is sent until one is supplied"
    );
  }
  return userAgent;
}

// ---- the user-agent rule -------------------------------------------------

function withUserAgent(headers, ua) {
  if (headers == null) {
    return { 'User-Agent': ua };
  }
  if (Array.isArray(headers)) {
    const flat = typeof headers[0] === 'string';
    const names = flat
      ? headers.filter((_, index) => index % 2 === 0)
      : headers.map((pair) => pair && pair[0]);
    if (names.some((name) => String(name).toLowerCase() === 'user-agent')) {
      return headers;
    }
    return flat ? headers.concat(['User-Agent', ua]) : headers.concat([['User-Agent', ua]]);
  }
  if (typeof headers.set === 'function' && typeof headers.has === 'function') {
    headers.set('User-Agent', ua);
    return headers;
  }
  if (typeof headers === 'object') {
    for (const key of Object.keys(headers)) {
      if (key.toLowerCase() === 'user-agent') {
        return headers;
      }
    }
    headers['User-Agent'] = ua;
    return headers;
  }
  return { 'User-Agent': ua };
}

function injectUserAgent(args) {
  const ua = currentUserAgent();
  const first = args[0];
  if (typeof first === 'string' || first instanceof URL) {
    const options = args[1];
    if (options && typeof options === 'object') {
      options.headers = withUserAgent(options.headers, ua);
    } else {
      // (url, callback) becomes (url, options, callback), which is the shape
      // Node's own signature already accepts.
      args.splice(1, 0, { headers: { 'User-Agent': ua } });
    }
    return;
  }
  if (first && typeof first === 'object') {
    first.headers = withUserAgent(first.headers, ua);
  }
}

// A request whose header is set after it was created would otherwise undo the
// injection above, so the setter is pinned to the profile's identity too.
function pinHeaders(request) {
  if (!request || typeof request.setHeader !== 'function') {
    return request;
  }
  const originalSetHeader = request.setHeader;
  request.setHeader = function (name, value) {
    if (String(name).toLowerCase() === 'user-agent') {
      return originalSetHeader.call(this, 'User-Agent', currentUserAgent());
    }
    return originalSetHeader.call(this, name, value);
  };
  return request;
}

function patchRequestFamily(name) {
  const mod = require(name);
  const originalRequest = mod.request;
  const originalGet = mod.get;
  mod.request = function (...args) {
    injectUserAgent(args);
    return pinHeaders(originalRequest.apply(this, args));
  };
  mod.get = function (...args) {
    injectUserAgent(args);
    return pinHeaders(originalGet.apply(this, args));
  };
}

function patchFetch() {
  const original = globalThis.fetch;
  if (typeof original !== 'function') {
    return;
  }
  globalThis.fetch = function (input, init) {
    const ua = currentUserAgent();
    const merged = Object.assign({}, init);
    const headers = new Headers((init && init.headers) || (input && input.headers) || undefined);
    headers.set('user-agent', ua);
    merged.headers = headers;
    return original.call(this, input, merged);
  };
}

function patchHttp2() {
  let http2;
  try {
    http2 = require('http2');
  } catch (err) {
    return;
  }
  const proto = http2.Http2Session && http2.Http2Session.prototype;
  if (!proto || typeof proto.request !== 'function') {
    return;
  }
  const original = proto.request;
  proto.request = function (headers, options) {
    const ua = currentUserAgent();
    const merged = Object.assign({}, headers && typeof headers === 'object' ? headers : {});
    for (const key of Object.keys(merged)) {
      if (key.toLowerCase() === 'user-agent') {
        delete merged[key];
      }
    }
    merged['user-agent'] = ua;
    return original.call(this, merged, options);
  };
}

function installUserAgentRule() {
  patchRequestFamily('http');
  patchRequestFamily('https');
  patchFetch();
  patchHttp2();
  enforcementInstalled = true;
}

installUserAgentRule();

// ---- the wire ------------------------------------------------------------

function reply(connection, id, ok, payload) {
  const message = ok ? { id: id, ok: true, result: payload } : { id: id, ok: false, error: payload };
  try {
    connection.write(JSON.stringify(message) + '\n');
  } catch (err) {
    // A reply nobody can receive costs one answer; throwing here would take the
    // runtime down for it.
  }
}

function status() {
  return {
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    pid: process.pid,
    workDir: process.cwd(),
    userAgent: userAgent,
    userAgentEnforced: enforcementInstalled,
  };
}

function handle(connection, line) {
  let command;
  try {
    command = JSON.parse(line);
  } catch (err) {
    return;
  }
  if (command.token !== token) {
    reply(connection, command.id, false, "the command did not carry this run's token");
    return;
  }
  try {
    // Absent or blank means no identity was supplied, which is a refusal on
    // every request rather than a fallback to Node's own.
    userAgent =
      typeof command.userAgent === 'string' && command.userAgent.trim()
        ? command.userAgent.trim()
        : null;
    switch (command.cmd) {
      case 'status':
        reply(connection, command.id, true, status());
        break;
      default:
        reply(connection, command.id, false, 'unknown command: ' + String(command.cmd));
    }
  } catch (err) {
    reply(connection, command.id, false, err && err.message ? err.message : String(err));
  }
}

// ONE CLIENT, AND THE RUNTIME'S LIFETIME IS THAT CLIENT'S. The app owns the
// connection; when the app process goes away the socket closes and this process
// exits, so a runtime is never left behind serving a profile nobody is on.
let connected = false;

const server = net.createServer((connection) => {
  if (connected) {
    connection.destroy();
    return;
  }
  connected = true;

  let buffer = '';
  connection.setEncoding('utf8');
  connection.on('data', (chunk) => {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.trim()) {
        handle(connection, line);
      }
      index = buffer.indexOf('\n');
    }
  });
  connection.on('error', () => {});
  connection.on('close', () => {
    process.exit(0);
  });
});

server.on('error', (err) => {
  report('listen-failed', { socket: socketPath, error: err.message });
  process.stderr.write('room-node: listen failed: ' + err.message + '\n');
  process.exit(1);
});

// A socket file left behind by a runtime that died is not ours to reuse: the
// bind would fail with EADDRINUSE and the caller's connect would reach a socket
// nobody is listening on. ENOENT is the ordinary case.
try {
  fs.unlinkSync(socketPath);
} catch (err) {
  // Nothing to remove.
}

server.listen({ path: socketPath }, () => {
  // The bound address is reported as well as the path it was asked for: if the
  // two ever disagree, the caller's connect is not the thing at fault.
  report('listening', { socket: socketPath, address: server.address() });
  process.stderr.write('room-node: ready on ' + process.version + '\n');
});
