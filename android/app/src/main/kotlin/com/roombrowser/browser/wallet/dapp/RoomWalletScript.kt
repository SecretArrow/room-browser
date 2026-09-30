package com.roombrowser.browser.wallet.dapp

/**
 * INJECTED half of the dApp wallet bridge — a SEPARATE document-start
 * script (installed by the integrator the same way ProfileEngine installs
 * [com.roombrowser.browser.RoomVaultScript]) that exposes the standard
 * wallet provider objects dApps detect, and routes every call through the
 * native [WalletBridge] (JS object name `window.RoomWallet`).
 *
 * Providers installed (MAIN FRAME ONLY, like the vault script — an iframe
 * must never see wallet state of the embedding page):
 *  - `window.ethereum` — EIP-1193 subset: `request({method, params})`,
 *    `enable()`, `isMetaMask`, `isRoomBrowser`, `on`/`removeListener` for
 *    `accountsChanged`/`chainChanged`, and `chainId`/`selectedAddress`
 *    getters over cached state (populated from responses and native
 *    `chainChanged`/`accountsChanged` pushes — a getter NEVER triggers a
 *    request).
 *  - `window.solana` — Phantom-compatible: `connect`, `disconnect`,
 *    `signMessage(message, encoding)`, `signAndSendTransaction(tx)`,
 *    cached `publicKey` getter, `isPhantom`, `isRoomBrowser`.
 *  - `window.aptos` — Petra-compatible: `connect`, `account`,
 *    `signMessage({message, ...})`, `signAndSubmitTransaction(tx, opts)`.
 *  - `window.suiWallet` — Sui standard: `connect`, `requestAccounts`,
 *    `signMessage({message})`, `signAndExecuteTransactionBlock({...})`.
 *  - `window.tronLink` and a minimal `window.tronWeb` shim —
 *    `{ request({method}), connect() }`; `tron_requestAccounts` maps to a
 *    TRON Connect.
 *
 * PROTOCOL (see [WalletBridgeProtocol] for the native codec):
 *  - Every provider call wraps `{id, kind: "request"|"rpc", chain, method,
 *    params}` into JSON and calls `window.RoomWallet.request(json)`; the
 *    promise resolver is parked in a pending map keyed by id.
 *  - Native answers asynchronously with
 *    `window.__roomWalletResponse(id, resultJson, errorCode, errorMessage)`
 *    (code 0 resolves the promise with `JSON.parse(resultJson)`, anything
 *    else rejects with an Error carrying `.code`).
 *  - Read-only EVM calls (`eth_chainId`, `net_version`, `eth_blockNumber`,
 *    `eth_getBalance`, `eth_call`, `eth_gasPrice`, `eth_estimateGas`) travel
 *    as kind "rpc" so the page always sees the wallet's ACTIVE network —
 *    no prompt is ever needed for reads.
 *  - Native pushes `window.__roomWalletEmit(event, payloadJson)` for
 *    `accountsChanged` (`["0x…"]`) and `chainChanged` (`"0x…"`); solana/sui
 *    listeners receive the mapped `accountChanged` event with the first
 *    address as payload.
 *
 * MESSAGE ENCODING (the base64 convention): `personal_sign` payloads that
 * are 0x-hex strings pass through verbatim; EVERY other message payload
 * (UTF-8 text, Uint8Array) is encoded to its raw bytes, base64-wrapped as
 * `{"__roomB64": "…"}` — arbitrary text (emoji, quotes, newlines) would
 * otherwise be mangled or ambiguous across the JSON boundary. The native
 * codec decodes the wrapper symmetrically.
 *
 * UNSUPPORTED methods (`eth_signTransaction`, `eth_sendRawTransaction`,
 * `eth_decrypt`, `eth_getEncryptionPublicKey`) settle client-side with
 * EIP-1193 code 4200 and never reach native.
 *
 * Robustness rules (mirroring RoomVaultScript): everything in try/catch —
 * a hostile or broken page must still load; nothing is logged; idempotent
 * under re-injection via an install guard; a same-method flood is shed
 * client-side (250ms gap, error -32005) before the native throttle
 * (300ms per host+method) ever sees it.
 */
object RoomWalletScript {

    // A Kotlin raw string: no "$" may appear anywhere in this script (a
    // dollar would be read as Kotlin interpolation), so plain string
    // concatenation is used throughout, and no JS template literals or
    // regex string-end anchors.
    const val SCRIPT = """
(function () {
  'use strict';
  try {
    if (window.top !== window.self) return;
    if (window.__roomWalletInstalled) return;
    window.__roomWalletInstalled = true;

    var CHAIN_EVM = 'EVM';
    var CHAIN_SOLANA = 'SOLANA';
    var CHAIN_APTOS = 'APTOS';
    var CHAIN_SUI = 'SUI';
    var CHAIN_TRON = 'TRON';

    var READONLY_METHODS = {
      eth_chainId: 1, net_version: 1, eth_blockNumber: 1,
      eth_getBalance: 1, eth_call: 1, eth_gasPrice: 1, eth_estimateGas: 1
    };
    var UNSUPPORTED_METHODS = {
      eth_signTransaction: 1, eth_sendRawTransaction: 1,
      eth_decrypt: 1, eth_getEncryptionPublicKey: 1
    };
    var SAME_METHOD_GAP_MS = 250;

    var state = {
      chainId: null,
      selectedAddress: null,
      solanaPublicKey: null,
      aptosAddress: null
    };

    var pending = {};
    var nextId = 1;
    var lastCallAt = {};
    var listeners = { evm: {}, solana: {}, aptos: {}, sui: {} };

    function bridgeError(code, message) {
      var e = new Error(message);
      e.code = code;
      return e;
    }

    function toBytes(value) {
      try {
        if (value instanceof Uint8Array) return value;
        if (typeof value === 'string') return new TextEncoder().encode(value);
        if (value && typeof value === 'object' && typeof value.length === 'number') {
          var arr = [];
          for (var i = 0; i < value.length; i++) arr.push(value[i] & 0xff);
          return new Uint8Array(arr);
        }
      } catch (e) {}
      return null;
    }

    function isHexMessage(value) {
      if (typeof value !== 'string') return false;
      if (value.length < 4 || value.length % 2 !== 0) return false;
      if (value.charCodeAt(0) !== 48 || value.charCodeAt(1) !== 120) return false;
      for (var i = 2; i < value.length; i++) {
        var c = value.charCodeAt(i);
        var ok = (c >= 48 && c <= 57) || (c >= 97 && c <= 102) || (c >= 65 && c <= 70);
        if (!ok) return false;
      }
      return true;
    }

    function toBase64(bytes) {
      try {
        var s = '';
        for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 0xff);
        return btoa(s);
      } catch (e) {
        return null;
      }
    }

    // 0x-hex strings pass through verbatim; every other payload becomes its
    // UTF-8 bytes, base64-wrapped as {"__roomB64": "..."} so arbitrary text
    // survives the JSON boundary unharmed (see WalletBridgeProtocol).
    function wrapMessage(value) {
      if (isHexMessage(value)) return value;
      var bytes = toBytes(value);
      if (bytes === null) return value;
      var encoded = toBase64(bytes);
      if (encoded === null) return value;
      return { __roomB64: encoded };
    }

    // personal_sign carries its message at params[0] (array shape) or
    // params.message (object shape) — wrap ONLY the message, in a copy.
    function wrapPersonalSign(params) {
      try {
        if (Array.isArray(params) && params.length) {
          var copy = params.slice();
          copy[0] = wrapMessage(copy[0]);
          return copy;
        }
        if (params && typeof params === 'object' && typeof params.message !== 'undefined') {
          var out = {};
          for (var key in params) {
            if (Object.prototype.hasOwnProperty.call(params, key)) out[key] = params[key];
          }
          out.message = wrapMessage(params.message);
          return out;
        }
      } catch (e) {}
      return params;
    }

    function send(chain, kind, method, params) {
      return new Promise(function (resolve, reject) {
        try {
          var now = Date.now();
          var last = lastCallAt[method] || 0;
          if (now - last < SAME_METHOD_GAP_MS) {
            reject(bridgeError(-32005, 'Too many requests, retry shortly'));
            return;
          }
          lastCallAt[method] = now;
          if (!window.RoomWallet || typeof window.RoomWallet.request !== 'function') {
            reject(bridgeError(4900, 'Wallet bridge is unavailable'));
            return;
          }
          var id = 'w' + (nextId++) + '-' + Math.floor(Math.random() * 2147483647).toString(36);
          pending[id] = { resolve: resolve, reject: reject, method: method, chain: chain };
          var envelope = JSON.stringify({
            id: id,
            kind: kind,
            chain: chain,
            method: method,
            params: params === undefined ? null : params
          });
          window.RoomWallet.request(envelope);
        } catch (e) {
          reject(bridgeError(-32603, 'Wallet request failed'));
        }
      });
    }

    function cacheFromResult(entry, value) {
      try {
        if (entry.method === 'eth_chainId' && typeof value === 'string') {
          state.chainId = value;
        } else if (entry.method === 'net_version' && typeof value === 'string') {
          var parsed = parseInt(value, 10);
          if (!isNaN(parsed)) state.chainId = '0x' + parsed.toString(16);
        } else if ((entry.method === 'eth_accounts' || entry.method === 'eth_requestAccounts') && Array.isArray(value)) {
          state.selectedAddress = value.length ? String(value[0]) : null;
        } else if (entry.method === 'connect' || entry.method === 'requestAccounts') {
          if (entry.chain === CHAIN_SOLANA && value && value.publicKey) {
            state.solanaPublicKey = String(value.publicKey);
          } else if (entry.chain === CHAIN_APTOS && value && value.address) {
            state.aptosAddress = String(value.address);
          }
        }
      } catch (e) {}
    }

    window.__roomWalletResponse = function (id, resultJson, errorCode, errorMessage) {
      try {
        var entry = pending[id];
        if (!entry) return;
        delete pending[id];
        var code = typeof errorCode === 'number' ? errorCode : 0;
        if (code !== 0) {
          var msg = typeof errorMessage === 'string' && errorMessage
            ? errorMessage : 'Wallet request rejected';
          entry.reject(bridgeError(code, msg));
          return;
        }
        var value = null;
        try {
          value = JSON.parse(resultJson);
        } catch (e) {
          entry.reject(bridgeError(-32603, 'Malformed wallet response'));
          return;
        }
        cacheFromResult(entry, value);
        entry.resolve(value);
      } catch (e) {}
    };

    function addListener(group, event, cb) {
      try {
        if (typeof event !== 'string' || typeof cb !== 'function') return;
        var map = listeners[group];
        if (!map) return;
        if (!map[event]) map[event] = [];
        map[event].push(cb);
      } catch (e) {}
    }

    function removeListener(group, event, cb) {
      try {
        var map = listeners[group];
        if (!map || !map[event]) return;
        var cbs = map[event];
        for (var i = 0; i < cbs.length; i++) {
          if (cbs[i] === cb) { cbs.splice(i, 1); return; }
        }
      } catch (e) {}
    }

    function dispatch(group, event, payload) {
      try {
        var map = listeners[group];
        if (!map || !map[event]) return;
        var cbs = map[event].slice();
        for (var i = 0; i < cbs.length; i++) {
          try { cbs[i](payload); } catch (e) {}
        }
      } catch (e) {}
    }

    window.__roomWalletEmit = function (event, payloadJson) {
      try {
        var payload = null;
        try { payload = JSON.parse(payloadJson); } catch (e) { return; }
        if (event === 'accountsChanged') {
          if (Array.isArray(payload)) {
            state.selectedAddress = payload.length ? String(payload[0]) : null;
          }
          dispatch('evm', 'accountsChanged', payload);
          var first = Array.isArray(payload) && payload.length ? String(payload[0]) : null;
          dispatch('solana', 'accountChanged', first);
          dispatch('sui', 'accountChanged', first);
        } else if (event === 'chainChanged') {
          if (typeof payload === 'string') state.chainId = payload;
          dispatch('evm', 'chainChanged', payload);
        }
      } catch (e) {}
    };

    function eventsFor(group) {
      return {
        on: function (event, cb) { addListener(group, event, cb); },
        removeListener: function (event, cb) { removeListener(group, event, cb); }
      };
    }

    var evmEvents = eventsFor('evm');

    window.ethereum = {
      isMetaMask: true,
      isRoomBrowser: true,
      request: function (args) {
        var method = args && typeof args.method === 'string' ? args.method : '';
        var params = args && args.params !== undefined ? args.params : [];
        if (UNSUPPORTED_METHODS[method]) {
          return Promise.reject(bridgeError(4200, 'The requested method is not supported: ' + method));
        }
        if (method === 'personal_sign') {
          params = wrapPersonalSign(params);
        }
        var kind = READONLY_METHODS[method] ? 'rpc' : 'request';
        return send(CHAIN_EVM, kind, method, params);
      },
      enable: function () {
        return send(CHAIN_EVM, 'request', 'eth_requestAccounts', []);
      },
      on: evmEvents.on,
      removeListener: evmEvents.removeListener,
      get chainId() { return state.chainId; },
      get selectedAddress() { return state.selectedAddress; }
    };

    var solanaEvents = eventsFor('solana');

    window.solana = {
      isPhantom: true,
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_SOLANA, 'request', 'connect', []);
      },
      disconnect: function () {
        state.solanaPublicKey = null;
        return Promise.resolve({});
      },
      signMessage: function (message, encoding) {
        var params = { message: wrapMessage(message) };
        if (typeof encoding === 'string') params.encoding = encoding;
        return send(CHAIN_SOLANA, 'request', 'signMessage', params);
      },
      signAndSendTransaction: function (tx) {
        var serialized = null;
        try {
          if (tx && typeof tx.serialize === 'function') serialized = toBase64(tx.serialize());
          else if (typeof tx === 'string') serialized = tx;
        } catch (e) {}
        if (!serialized) {
          return Promise.reject(bridgeError(-32602, 'Unsupported transaction payload'));
        }
        return send(CHAIN_SOLANA, 'request', 'signAndSendTransaction', { transaction: serialized });
      },
      get publicKey() { return state.solanaPublicKey; },
      on: solanaEvents.on,
      removeListener: solanaEvents.removeListener
    };

    var aptosEvents = eventsFor('aptos');

    window.aptos = {
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_APTOS, 'request', 'connect', []);
      },
      account: function () {
        return Promise.resolve({
          address: state.aptosAddress,
          publicKey: state.aptosAddress
        });
      },
      signMessage: function (input) {
        var src = input && typeof input === 'object' ? input : {};
        var params = {};
        try { params.message = wrapMessage(src.message); } catch (e) {}
        if (typeof src.nonce !== 'undefined') params.nonce = String(src.nonce);
        if (typeof src.application === 'string') params.application = src.application;
        if (typeof src.chainId !== 'undefined') params.chainId = String(src.chainId);
        return send(CHAIN_APTOS, 'request', 'signMessage', params);
      },
      signAndSubmitTransaction: function (tx, options) {
        var params = { transaction: tx === undefined ? null : tx };
        if (options !== undefined && options !== null) params.options = options;
        return send(CHAIN_APTOS, 'request', 'signAndSubmitTransaction', params);
      },
      on: aptosEvents.on,
      removeListener: aptosEvents.removeListener
    };

    var suiEvents = eventsFor('sui');

    window.suiWallet = {
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_SUI, 'request', 'connect', []);
      },
      requestAccounts: function () {
        return send(CHAIN_SUI, 'request', 'requestAccounts', []);
      },
      signMessage: function (input) {
        var src = input && typeof input === 'object' ? input : {};
        var params = {};
        try { params.message = wrapMessage(src.message); } catch (e) {}
        return send(CHAIN_SUI, 'request', 'signMessage', params);
      },
      signAndExecuteTransactionBlock: function (input) {
        var src = input && typeof input === 'object' ? input : {};
        var params = {};
        if (typeof src.transactionBlock !== 'undefined') params.transactionBlock = src.transactionBlock;
        if (typeof src.options !== 'undefined') params.options = src.options;
        return send(CHAIN_SUI, 'request', 'signAndExecuteTransactionBlock', params);
      },
      on: suiEvents.on,
      removeListener: suiEvents.removeListener
    };

    function tronProvider() {
      return {
        isRoomBrowser: true,
        connect: function () {
          return send(CHAIN_TRON, 'request', 'connect', []);
        },
        request: function (args) {
          var method = args && typeof args.method === 'string' ? args.method : '';
          if (method === 'tron_requestAccounts') {
            return send(CHAIN_TRON, 'request', 'connect', []);
          }
          var params = args && typeof args.params !== 'undefined' ? args.params : [];
          return send(CHAIN_TRON, 'request', method, params);
        }
      };
    }

    window.tronLink = tronProvider();
    window.tronWeb = tronProvider();
  } catch (e) {
    // A page must still load even when the wallet bridge cannot install.
  }
})();
"""
}
