/*
 * VeilCrypto - the zero-knowledge "encrypted escape hatch".
 *
 * The admin has an ECDH P-256 identity. The PUBLIC key is stored on the server;
 * the PRIVATE key is wrapped with a key derived from the admin's password
 * (PBKDF2) and only ever unwrapped in the admin's browser - the server (and we,
 * the operator) can never read it. A sensitive item is sealed to the admin's
 * public key (ECIES: ephemeral ECDH -> HKDF -> AES-GCM), so only the admin can
 * open it after unlocking with their password. Works in the browser and in Node
 * (both expose WebCrypto as crypto.subtle).
 */
(function (root) {
  const subtle = (root.crypto || globalThis.crypto).subtle;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
  const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

  async function wrapKeyFromPassword(password, salt) {
    const base = await subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveKey"]);
    return subtle.deriveKey(
      { name: "PBKDF2", salt, iterations: 150000, hash: "SHA-256" },
      base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"],
    );
  }

  /** Create an identity. Returns { publicJwk, encPrivateKey, salt } to store on the server. */
  async function generateIdentity(password) {
    const pair = await subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const publicJwk = await subtle.exportKey("jwk", pair.publicKey);
    const privateJwk = await subtle.exportKey("jwk", pair.privateKey);
    const salt = root.crypto.getRandomValues(new Uint8Array(16));
    const iv = root.crypto.getRandomValues(new Uint8Array(12));
    const wrap = await wrapKeyFromPassword(password, salt);
    const ct = await subtle.encrypt({ name: "AES-GCM", iv }, wrap, enc.encode(JSON.stringify(privateJwk)));
    return {
      publicJwk,
      encPrivateKey: JSON.stringify({ iv: b64(iv), ct: b64(ct) }),
      salt: b64(salt),
    };
  }

  /** Unlock the private key in the browser from the password + stored blobs. */
  async function unlock(password, encPrivateKey, saltB64) {
    const { iv, ct } = JSON.parse(encPrivateKey);
    const wrap = await wrapKeyFromPassword(password, unb64(saltB64));
    const privateJwk = JSON.parse(dec.decode(await subtle.decrypt({ name: "AES-GCM", iv: unb64(iv) }, wrap, unb64(ct))));
    return subtle.importKey("jwk", privateJwk, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  }

  async function sharedKey(privateKey, publicKey) {
    const bits = await subtle.deriveBits({ name: "ECDH", public: publicKey }, privateKey, 256);
    const hkdfBase = await subtle.importKey("raw", bits, "HKDF", false, ["deriveKey"]);
    return subtle.deriveKey(
      { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: enc.encode("veil-escape-hatch") },
      hkdfBase, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"],
    );
  }

  /** Seal plaintext to the admin's public key. Returns an opaque string the server stores. */
  async function sealTo(publicJwk, plaintext) {
    const recipient = await subtle.importKey("jwk", publicJwk, { name: "ECDH", namedCurve: "P-256" }, false, []);
    const eph = await subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const key = await sharedKey(eph.privateKey, recipient);
    const iv = root.crypto.getRandomValues(new Uint8Array(12));
    const ct = await subtle.encrypt({ name: "AES-GCM", iv }, key, enc.encode(plaintext));
    return JSON.stringify({ epk: await subtle.exportKey("jwk", eph.publicKey), iv: b64(iv), ct: b64(ct) });
  }

  /** Open a sealed item with the unlocked private key. */
  async function openWith(privateKey, sealed) {
    const { epk, iv, ct } = JSON.parse(sealed);
    const eph = await subtle.importKey("jwk", epk, { name: "ECDH", namedCurve: "P-256" }, false, []);
    const key = await sharedKey(privateKey, eph);
    return dec.decode(await subtle.decrypt({ name: "AES-GCM", iv: unb64(iv) }, key, unb64(ct)));
  }

  root.VeilCrypto = { generateIdentity, unlock, sealTo, openWith };
})(typeof window !== "undefined" ? window : globalThis);
