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
 *    `enable()`, `isMetaMask`, `isRoomBrowser`, `isConnected()`,
 *    `on`/`removeListener` for `accountsChanged`/`chainChanged`/`connect`/
 *    `disconnect`/`message`, and `chainId`/`selectedAddress` getters over
 *    cached state (populated from responses and native
 *    `chainChanged`/`accountsChanged` pushes — a getter NEVER triggers a
 *    request). Also announces itself over EIP-6963
 *    (`eip6963:announceProvider`, re-announcing on `eip6963:requestProvider`).
 *  - `window.solana` — Phantom-compatible: `connect`, `disconnect` (revokes
 *    the host's Solana permission natively), `signMessage(message, encoding)`,
 *    `signAndSendTransaction(tx)`, cached `publicKey` getter, `isPhantom`,
 *    `isRoomBrowser`.
 *  - `window.aptos` — Petra-compatible: `connect`, `account`,
 *    `signMessage({message, ...})`, `signAndSubmitTransaction(tx, opts)`.
 *  - `window.suiWallet` — Sui standard: `connect`, `requestAccounts`,
 *    `signMessage`/`signPersonalMessage({message})`,
 *    `signAndExecuteTransactionBlock`/`signAndExecuteTransaction({...})`.
 *  - `window.keplr` — Keplr-compatible Cosmos provider: `enable(chainId)`,
 *    `getKey(chainId)`, `getOfflineSigner`/`getOfflineSignerOnlyAmino`/
 *    `getOfflineSignerAuto`, `signAmino`, `signDirect`, `signArbitrary`, and
 *    the `keplr_keystorechange` event (fired whenever the accounts change).
 *    The offline signers are ALSO on the global (`window.getOfflineSigner`
 *    and friends), which is the name CosmJS documents and most dApps call.
 *  - `window.BitcoinProvider` — `connect`, `getAccounts`, `signMessage`.
 *    `signTransaction` is deliberately absent-in-effect: it rejects with
 *    4200 (see the comment at its definition).
 *  - `window.tronLink` and a MINIMAL `window.tronWeb` shim —
 *    `request({method})`, `connect()`, `signMessage`, `signTransaction`;
 *    `tron_requestAccounts`/`tron_signMessage`/`tron_signTransaction` map to
 *    their native counterparts. The tronWeb shim is deliberately NOT the
 *    TronWeb SDK — see the comment at its definition for the exact limit.
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
 *    `accountsChanged` (`["0x…"]`) and `chainChanged` (`"0x…"`). Only the EVM
 *    provider is told the EVM address list; the other families get the same
 *    event with a null payload — the "whatever you cached is stale" signal —
 *    because an EVM address is not an account on their chain. Cosmos
 *    listeners get `keplr_keystorechange`, and an
 *    unhandled event name surfaces on `window.ethereum`'s EIP-1193 `message`
 *    listeners as `{type, data}`.
 *  - A rejected call carrying 4900/4901 means native lost the wallet or the
 *    chain, which is exactly EIP-1193's `disconnect`: it is dispatched to
 *    `window.ethereum` listeners.
 *
 * MESSAGE ENCODING (the base64 convention): `personal_sign` payloads that
 * are 0x-hex strings pass through verbatim; EVERY other message payload
 * (UTF-8 text, Uint8Array) is encoded to its raw bytes, base64-wrapped as
 * `{"__roomB64": "…"}` — arbitrary text (emoji, quotes, newlines) would
 * otherwise be mangled or ambiguous across the JSON boundary. The native
 * codec decodes the wrapper symmetrically. Cosmos sign-doc bytes
 * (signDirect's `bodyBytes`/`authInfoBytes`) go the other way: a Uint8Array
 * is base64-encoded before it is sent.
 *
 * UNSUPPORTED methods (`eth_signTransaction`, `eth_sendRawTransaction`,
 * `eth_decrypt`, `eth_getEncryptionPublicKey`) settle client-side with
 * EIP-1193 code 4200 and never reach native — none of them is in the
 * granted method set either, so the wallet never advertises them.
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
    var CHAIN_COSMOS = 'COSMOS';
    var CHAIN_BITCOIN = 'BITCOIN';
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
      evmConnected: false,
      solanaPublicKey: null,
      aptosAddress: null,
      cosmosAddress: null,
      bitcoinAddress: null,
      tronAddress: null
    };

    var pending = {};
    var nextId = 1;
    var lastCallAt = {};
    var listeners = { evm: {}, solana: {}, aptos: {}, sui: {}, cosmos: {}, bitcoin: {} };

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

    // The opposite direction, for Cosmos sign docs: the dApp hands us a
    // Uint8Array, JSON can only carry text, so it becomes base64.
    function bytesToBase64(value) {
      if (typeof value === 'string') return value;
      var bytes = toBytes(value);
      if (bytes === null) return null;
      return toBase64(bytes);
    }

    // Every provider family answers with its own address shape (EVM array,
    // Solana {publicKey}, Aptos/Tron/Bitcoin {address}, Keplr a bare string
    // or a Key list). One reader so caching never disagrees with a provider.
    function addressOf(value) {
      try {
        if (typeof value === 'string') return value || null;
        if (Array.isArray(value)) {
          if (!value.length) return null;
          var first = value[0];
          if (typeof first === 'string') return first || null;
          if (first && typeof first === 'object') {
            if (typeof first.address === 'string' && first.address) return first.address;
            if (typeof first.bech32Address === 'string' && first.bech32Address) return first.bech32Address;
            if (typeof first.publicKey === 'string' && first.publicKey) return first.publicKey;
          }
          return null;
        }
        if (value && typeof value === 'object') {
          if (typeof value.address === 'string' && value.address) return value.address;
          if (typeof value.bech32Address === 'string' && value.bech32Address) return value.bech32Address;
          if (typeof value.publicKey === 'string' && value.publicKey) return value.publicKey;
        }
      } catch (e) {}
      return null;
    }

    // Base58 (Bitcoin alphabet) decode, for PublicKey.toBytes(). Returns
    // null rather than a wrong answer for anything that is not valid base58.
    var B58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
    function base58ToBytes(text) {
      try {
        var bytes = [0];
        for (var i = 0; i < text.length; i++) {
          var value = B58_ALPHABET.indexOf(text.charAt(i));
          if (value < 0) return null;
          var carry = value;
          for (var j = 0; j < bytes.length; j++) {
            carry += bytes[j] * 58;
            bytes[j] = carry & 0xff;
            carry >>= 8;
          }
          while (carry > 0) { bytes.push(carry & 0xff); carry >>= 8; }
        }
        for (var k = 0; k < text.length && text.charAt(k) === '1'; k++) bytes.push(0);
        return new Uint8Array(bytes.reverse());
      } catch (e) {
        return null;
      }
    }

    // Phantom hands dApps a PublicKey OBJECT, never a string:
    // `(await window.solana.connect()).publicKey.toBase58()` is the first
    // thing nearly every Solana dApp does, and against a bare string that
    // is a TypeError which the dApp reports as a failed connection. This is
    // the subset of the PublicKey surface dApps actually call.
    function solanaPublicKeyOf(base58) {
      if (typeof base58 !== 'string' || !base58) return null;
      var decoded = null;
      return {
        toBase58: function () { return base58; },
        toString: function () { return base58; },
        toJSON: function () { return base58; },
        equals: function (other) {
          if (other === null || other === undefined) return false;
          if (typeof other === 'string') return other === base58;
          try { return String(other.toBase58()) === base58; } catch (e) { return false; }
        },
        toBytes: function () {
          if (decoded === null) decoded = base58ToBytes(base58);
          return decoded;
        }
      };
    }

    // EIP-1193: the provider announces `connect` the first time it knows a
    // chain id (from a read or a native chainChanged push).
    function noteChainId(value) {
      try {
        if (typeof value !== 'string' || !value) return;
        state.chainId = value;
        if (!state.evmConnected) {
          state.evmConnected = true;
          dispatch('evm', 'connect', { chainId: value });
        }
      } catch (e) {}
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
          noteChainId(value);
        } else if (entry.method === 'net_version' && typeof value === 'string') {
          var parsed = parseInt(value, 10);
          if (!isNaN(parsed)) noteChainId('0x' + parsed.toString(16));
        } else if ((entry.method === 'eth_accounts' || entry.method === 'eth_requestAccounts') && Array.isArray(value)) {
          state.selectedAddress = value.length ? String(value[0]) : null;
        } else if (entry.method === 'connect' || entry.method === 'requestAccounts' || entry.method === 'enable') {
          var address = addressOf(value);
          if (address === null) return;
          if (entry.chain === CHAIN_SOLANA) {
            state.solanaPublicKey = solanaPublicKeyOf(address);
          } else if (entry.chain === CHAIN_APTOS) {
            state.aptosAddress = address;
          } else if (entry.chain === CHAIN_COSMOS) {
            state.cosmosAddress = address;
          } else if (entry.chain === CHAIN_BITCOIN) {
            state.bitcoinAddress = address;
          } else if (entry.chain === CHAIN_TRON) {
            state.tronAddress = address;
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
          // 4900/4901 = the wallet or its chain went away: that is exactly
          // what EIP-1193's `disconnect` means, so tell the page.
          if (code === 4900 || code === 4901) {
            state.evmConnected = false;
            dispatch('evm', 'disconnect', bridgeError(code, msg));
          }
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
        entry.resolve(solanaShaped(entry, value));
      } catch (e) {}
    };

    // The Solana connect result carries its key in the same shape the
    // provider getter does. Rewritten in place, so the cached state and the
    // resolved value are the same object and `provider.publicKey === (await
    // connect()).publicKey` stays true — which is what it is on Phantom.
    function solanaShaped(entry, value) {
      try {
        if (entry.chain !== CHAIN_SOLANA) return value;
        if (value && typeof value === 'object' && typeof value.publicKey === 'string') {
          var key = solanaPublicKeyOf(value.publicKey);
          if (key !== null) value.publicKey = key;
        }
      } catch (e) {}
      return value;
    }

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
          // The payload is the EVM address list, and the EVM provider is the
          // only one that can be told it. Broadcasting it to the other
          // families handed a Solana dApp a hex string where Phantom gives
          // it a PublicKey — an account that cannot exist on its chain, in
          // place of the account it actually holds. They get the null
          // "re-read" signal instead, which is the one thing this push does
          // prove about them: whatever they cached is no longer current.
          state.solanaPublicKey = null;
          state.aptosAddress = null;
          state.cosmosAddress = null;
          state.bitcoinAddress = null;
          state.tronAddress = null;
          dispatch('solana', 'accountChanged', null);
          dispatch('sui', 'accountChanged', null);
          dispatch('bitcoin', 'accountsChanged', null);
          // Keplr's re-read signal; it carries no payload by design.
          dispatch('cosmos', 'keplr_keystorechange', null);
        } else if (event === 'chainChanged') {
          if (typeof payload === 'string') noteChainId(payload);
          dispatch('evm', 'chainChanged', payload);
        } else {
          // Any other native push is surfaced through the one EIP-1193
          // channel for it: the provider `message` event.
          dispatch('evm', 'message', { type: String(event), data: payload });
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
      isConnected: function () {
        return state.chainId !== null;
      },
      on: evmEvents.on,
      removeListener: evmEvents.removeListener,
      get chainId() { return state.chainId; },
      get selectedAddress() { return state.selectedAddress; }
    };

    // EIP-6963 (wallet discovery): dApps announce a requestProvider event and
    // every wallet answers with its own info + provider. Without this a 2025+
    // dApp's multi-wallet picker never lists us. The icon is an inline SVG
    // data URI (no remote asset, no fingerprintable request).
    try {
      var eip6963Info = {
        uuid: (window.crypto && typeof window.crypto.randomUUID === 'function')
          ? window.crypto.randomUUID()
          : '3f2b1c8e-9d4a-4c1f-8b6e-5a0d7c2e9f31',
        name: 'Room Browser',
        icon: 'data:image/svg+xml,<svg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 32 32\'>' +
          '<rect width=\'32\' height=\'32\' rx=\'8\' fill=\'black\'/>' +
          '<circle cx=\'16\' cy=\'16\' r=\'7\' fill=\'white\'/></svg>',
        rdns: 'com.roombrowser.wallet'
      };
      var announceProvider = function () {
        try {
          window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
            detail: Object.freeze({ info: eip6963Info, provider: window.ethereum })
          }));
        } catch (e) {}
      };
      window.addEventListener('eip6963:requestProvider', announceProvider);
      announceProvider();
    } catch (e) {}

    var solanaEvents = eventsFor('solana');

    window.solana = {
      isPhantom: true,
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_SOLANA, 'request', 'connect', []);
      },
      disconnect: function () {
        // Not cosmetic: native revokes this host's Solana permission, so the
        // next connect has to be approved again.
        state.solanaPublicKey = null;
        return send(CHAIN_SOLANA, 'request', 'disconnect', []);
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

    function suiSignMessage(input) {
      var src = input && typeof input === 'object' ? input : {};
      var params = {};
      try { params.message = wrapMessage(src.message); } catch (e) {}
      return send(CHAIN_SUI, 'request', 'signMessage', params);
    }

    function suiExecuteTransaction(input) {
      var src = input && typeof input === 'object' ? input : {};
      // The wallet standard hands over a Transaction OBJECT, which is not
      // JSON-serializable across the bridge (it would arrive as {}). The
      // engine signs and submits serialized bytes, so ask the object for them
      // the way a wallet adapter does — Transaction.serialize(). A caller that
      // already holds serialized bytes (a string transactionBlock, or txBytes)
      // is passed straight through.
      var tx = typeof src.transactionBlock !== 'undefined' ? src.transactionBlock : src.transaction;
      if (typeof tx === 'string' && tx) {
        return suiSend({ txBytes: tx }, src.options);
      }
      if (tx && typeof tx.serialize === 'function') {
        // Sui's Transaction.serialize() returns base64 in some SDK versions
        // and raw BCS bytes in others — accept either, like the Solana path.
        var result = null;
        try { result = tx.serialize(); } catch (e) { result = null; }
        var serialized = typeof result === 'string' ? result : toBase64(result);
        if (typeof serialized === 'string' && serialized) {
          return suiSend({ txBytes: serialized }, src.options);
        }
        return Promise.reject(bridgeError(-32602, 'Could not serialize the Sui transaction'));
      }
      if (typeof src.txBytes === 'string' && src.txBytes) {
        return suiSend({ txBytes: src.txBytes }, src.options);
      }
      // Nothing here can be signed: say so up front instead of prompting the
      // user for a transaction the engine would then have to refuse.
      return Promise.reject(bridgeError(
        -32602, 'Sui signAndExecuteTransaction needs a Transaction object with serialize(), or base64 txBytes'
      ));
    }

    function suiSend(params, options) {
      if (typeof options !== 'undefined') params.options = options;
      return send(CHAIN_SUI, 'request', 'signAndExecuteTransactionBlock', params);
    }

    window.suiWallet = {
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_SUI, 'request', 'connect', []);
      },
      requestAccounts: function () {
        return send(CHAIN_SUI, 'request', 'requestAccounts', []);
      },
      signMessage: suiSignMessage,
      // The wallet-standard name for the same call.
      signPersonalMessage: suiSignMessage,
      signAndExecuteTransactionBlock: suiExecuteTransaction,
      // The wallet standard's newer name for the same call.
      signAndExecuteTransaction: suiExecuteTransaction,
      on: suiEvents.on,
      removeListener: suiEvents.removeListener
    };

    var cosmosEvents = eventsFor('cosmos');

    function keplrKey(address) {
      return {
        name: 'Room Browser',
        algo: 'secp256k1',
        // The engine hands the bridge no public key, so this wallet cannot
        // publish one (see WalletBridgeProtocol.keplrKeyResult). dApps that
        // need the compressed pubkey must read it from the chain or sign
        // through signAmino/signDirect.
        pubKey: null,
        address: address,
        bech32Address: address,
        isNanoLedger: false,
        isKeystone: false
      };
    }

    // Keplr's signAmino/signDirect resolve with {signed, signature}: the doc
    // the dApp handed us (unchanged) plus the base64 signature over it.
    function keplrSignResult(signDoc, signature) {
      return { signed: signDoc, signature: String(signature) };
    }

    // CosmJS's entry point, and the FIRST call nearly every Cosmos dApp
    // makes: `window.getOfflineSigner(chainId)`, then `getAccounts()` on the
    // result, then `SigningStargateClient.connectWithSigner(rpc, signer)`.
    // Without it the dApp dies on `window.getOfflineSigner is not a function`
    // before it can even ask to connect — the whole family, not one site.
    //
    // getAccounts goes through the native getKey rather than the page's own
    // cached address, because that is the path the engine permission-checks:
    // a dApp that never called enable() is refused (4100) instead of being
    // handed an address it was never granted. The signing half delegates
    // straight back to window.keplr, so a CosmJS client ends up signing
    // through the same reviewed signAmino/signDirect the dApp would call.
    function keplrOfflineSigner(chainId) {
      var id = typeof chainId === 'string' ? chainId : null;
      return {
        getAccounts: function () {
          return window.keplr.getKey(id).then(function (key) {
            var address = key && typeof key.bech32Address === 'string' ? key.bech32Address : null;
            if (address === null) throw bridgeError(-32603, 'Cosmos key returned no address');
            // pubKey is null for the reason keplrKey documents: the engine
            // does not publish public keys. Address-only flows work; a
            // CosmJS client that needs the pubkey to BUILD a sign doc cannot
            // be served by this wallet yet.
            return [{
              address: address,
              algo: key && typeof key.algo === 'string' ? key.algo : 'secp256k1',
              pubkey: key ? key.pubKey : null
            }];
          });
        },
        signAmino: function (signer, signDoc) {
          return window.keplr.signAmino(id, signer, signDoc);
        },
        signDirect: function (signer, signDoc) {
          return window.keplr.signDirect(id, signer, signDoc);
        }
      };
    }

    window.keplr = {
      isRoomBrowser: true,
      version: '0.1.0-roombrowser',
      mode: 'extension',
      getOfflineSigner: keplrOfflineSigner,
      getOfflineSignerOnlyAmino: keplrOfflineSigner,
      getOfflineSignerAuto: function (chainId) {
        return Promise.resolve(keplrOfflineSigner(chainId));
      },
      enable: function (chainId) {
        var params = { chainId: typeof chainId === 'string' ? chainId : null };
        return send(CHAIN_COSMOS, 'request', 'enable', params).then(function (value) {
          var address = addressOf(value);
          if (address === null) throw bridgeError(-32603, 'Cosmos connect returned no address');
          state.cosmosAddress = address;
          return [keplrKey(address)];
        });
      },
      getKey: function (chainId) {
        var params = { chainId: typeof chainId === 'string' ? chainId : null };
        return send(CHAIN_COSMOS, 'request', 'getKey', params).then(function (key) {
          if (key && typeof key.bech32Address === 'string') state.cosmosAddress = key.bech32Address;
          return key;
        });
      },
      signAmino: function (chainId, signer, signDoc) {
        if (!signDoc || typeof signDoc !== 'object') {
          return Promise.reject(bridgeError(-32602, 'signAmino needs a sign doc object'));
        }
        return send(CHAIN_COSMOS, 'request', 'signAmino', {
          chainId: typeof chainId === 'string' ? chainId : null,
          signer: typeof signer === 'string' ? signer : null,
          signDoc: signDoc
        }).then(function (signature) {
          return keplrSignResult(signDoc, signature);
        });
      },
      signDirect: function (chainId, signer, signDoc) {
        var src = signDoc && typeof signDoc === 'object' ? signDoc : {};
        var body = bytesToBase64(src.bodyBytes);
        var authInfo = bytesToBase64(src.authInfoBytes);
        if (!body || !authInfo) {
          return Promise.reject(bridgeError(-32602, 'signDirect needs bodyBytes and authInfoBytes'));
        }
        var docChainId = typeof src.chainId === 'string' && src.chainId
          ? src.chainId : (typeof chainId === 'string' ? chainId : null);
        if (!docChainId) {
          return Promise.reject(bridgeError(-32602, 'signDirect needs a chain id'));
        }
        var accountNumber = typeof src.accountNumber === 'undefined' || src.accountNumber === null
          ? '0' : String(src.accountNumber);
        return send(CHAIN_COSMOS, 'request', 'signDirect', {
          chainId: docChainId,
          signer: typeof signer === 'string' ? signer : null,
          bodyBytes: body,
          authInfoBytes: authInfo,
          accountNumber: accountNumber
        }).then(function (signature) {
          return keplrSignResult({
            bodyBytes: src.bodyBytes,
            authInfoBytes: src.authInfoBytes,
            chainId: docChainId,
            accountNumber: accountNumber
          }, signature);
        });
      },
      signArbitrary: function (chainId, signer, data) {
        var params = {
          chainId: typeof chainId === 'string' ? chainId : null,
          signer: typeof signer === 'string' ? signer : null
        };
        try { params.message = wrapMessage(data); } catch (e) {}
        return send(CHAIN_COSMOS, 'request', 'signArbitrary', params).then(function (signature) {
          // pub_key is null for the same reason as getKey's: the engine does
          // not expose public keys.
          return { signature: String(signature), pub_key: null };
        });
      },
      on: cosmosEvents.on,
      off: cosmosEvents.removeListener,
      removeListener: cosmosEvents.removeListener
    };

    // The same signer, on the global. CosmJS's own documentation and a large
    // body of dApp code call window.getOfflineSigner directly, so exposing it
    // only under window.keplr would leave those apps broken in exactly the
    // way this is here to fix.
    try {
      window.getOfflineSigner = keplrOfflineSigner;
      window.getOfflineSignerOnlyAmino = keplrOfflineSigner;
      window.getOfflineSignerAuto = function (chainId) {
        return Promise.resolve(keplrOfflineSigner(chainId));
      };
    } catch (e) {}

    var bitcoinEvents = eventsFor('bitcoin');

    window.BitcoinProvider = {
      isRoomBrowser: true,
      connect: function () {
        return send(CHAIN_BITCOIN, 'request', 'connect', []).then(function (value) {
          var address = addressOf(value);
          if (address === null) throw bridgeError(-32603, 'Bitcoin connect returned no address');
          state.bitcoinAddress = address;
          return { address: address, publicKey: null };
        });
      },
      getAccounts: function () {
        // Silent once this host is permitted (the bridge auto-approves a
        // permitted Connect); a prompt the first time, like the other
        // providers' connect.
        if (state.bitcoinAddress) return Promise.resolve([state.bitcoinAddress]);
        return window.BitcoinProvider.connect().then(function (account) {
          return [account.address];
        });
      },
      signMessage: function (message, options) {
        var params = { message: wrapMessage(message) };
        if (typeof options === 'string') params.protocol = options;
        return send(CHAIN_BITCOIN, 'request', 'signMessage', params);
      },
      signTransaction: function () {
        // DELIBERATE LIMIT: the engine refuses Bitcoin dApp transactions
        // (WalletEngine.signAndBroadcastDappTransaction has no ChainType.BITCOIN
        // route), so the wallet does not advertise the method and says so
        // instead of failing halfway through a prompt.
        return Promise.reject(bridgeError(
          4200, 'Bitcoin transaction signing is not supported by this wallet'
        ));
      },
      on: bitcoinEvents.on,
      removeListener: bitcoinEvents.removeListener
    };

    function tronProvider() {
      return {
        isRoomBrowser: true,
        connect: function () {
          return send(CHAIN_TRON, 'request', 'connect', []);
        },
        request: function (args) {
          var method = args && typeof args.method === 'string' ? args.method : '';
          var params = args && typeof args.params !== 'undefined' ? args.params : [];
          if (method === 'tron_requestAccounts') {
            return send(CHAIN_TRON, 'request', 'connect', []);
          }
          if (method === 'tron_signMessage') {
            var payload = Array.isArray(params) ? params[0] : params;
            return send(CHAIN_TRON, 'request', 'signMessage', { message: wrapMessage(payload) });
          }
          if (method === 'tron_signTransaction') {
            var tx = Array.isArray(params) ? params[0] : params;
            if (!tx || typeof tx !== 'object') {
              return Promise.reject(bridgeError(-32602, 'signTransaction expects a transaction object'));
            }
            return send(CHAIN_TRON, 'request', 'signTransaction', tx);
          }
          return send(CHAIN_TRON, 'request', method, params);
        },
        signMessage: function (message) {
          return send(CHAIN_TRON, 'request', 'signMessage', { message: wrapMessage(message) });
        },
        signTransaction: function (tx) {
          if (!tx || typeof tx !== 'object') {
            return Promise.reject(bridgeError(-32602, 'signTransaction expects a transaction object'));
          }
          return send(CHAIN_TRON, 'request', 'signTransaction', tx);
        }
      };
    }

    window.tronLink = tronProvider();

    // DELIBERATE LIMIT — this is NOT the TronWeb SDK. TronWeb is a large
    // library: dApps reach for tronWeb.contract() (ABI encoding +
    // triggerSmartContract), tronWeb.transactionBuilder.*, tronWeb.address.*,
    // tronWeb.utils.* and the tronWeb.trx.* family (getBalance, getAccount,
    // send, broadcast, sign, ...). None of that is re-implemented here, and
    // pretending otherwise would fail later and more confusingly than saying
    // it up front. What IS here: the account identity (defaultAddress), the
    // two unit helpers, and the two signing entry points, all of which route
    // through native confirmation. A dApp that needs the rest must fall back
    // to window.tronLink.request({method: 'tron_signTransaction' |
    // 'tron_signMessage'}) or to tronLink.signTransaction / signMessage.
    // Anything else (for example tronWeb.contract()) throws a TypeError —
    // by design, so the dApp can detect the limit instead of mis-signing.
    window.tronWeb = {
      isRoomBrowser: true,
      ready: true,
      get defaultAddress() {
        var address = state.tronAddress;
        if (address === null) return null;
        // Hex form and the rest of the address helpers are not implemented.
        return { base58: address, name: 'Room Browser', type: 'tron' };
      },
      toSun: function (amount) {
        var parsed = Number(amount);
        if (!isFinite(parsed)) throw new Error('toSun expects a number');
        return Math.round(parsed * 1000000);
      },
      fromSun: function (sun) {
        var parsed = Number(sun);
        if (!isFinite(parsed)) throw new Error('fromSun expects a number');
        return parsed / 1000000;
      },
      trx: {
        sign: function (tx) {
          return send(CHAIN_TRON, 'request', 'signTransaction', tx);
        },
        signMessage: function (message) {
          return send(CHAIN_TRON, 'request', 'signMessage', { message: wrapMessage(message) });
        }
      }
    };
  } catch (e) {
    // A page must still load even when the wallet bridge cannot install.
  }
})();
"""
}
