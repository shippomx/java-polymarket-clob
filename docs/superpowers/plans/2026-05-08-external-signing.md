# External Signing for Java CLOB SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a two-phase external signing API (`buildUnsigned*` + `attach*Signature`) to the Java CLOB SDK so a backend can assemble unsigned EIP-712 / EIP-191 typed data, hand it off to a remote signer (mobile app holding the private key), and reattach the returned 65-byte signature into final wire payloads — without ever holding the user's private key in BE.

**Architecture:** 4 new immutable `Unsigned*` records (one per payload class: Order V2 POLY_1271, DepositWallet Batch, ClobAuth, SIWE), each carrying `signingDigest32` plus (where applicable) a verifiable EIP-712 `typedDataJson`. New static `ExternalSigning` facade in `com.polymarket.clob.signing` exposes 4 build/attach pairs. Existing local-signing entry points (`Pol1271OrderSigner.sign`, `L1HeaderBuilder.build`, `GammaClient.loginWithSiwe`) get refactored to thin `buildUnsigned + signer.signHash + attach` wrappers. `LocalSigner` and the `Signer` interface stay byte-identical. Wire-level golden vectors (Rust/Python parity tests) must not regress.

**Tech Stack:** Java 17, Maven, web3j 4.12.2 (`StructuredDataEncoder`, `Hash.sha3`, `Sign.getEthereumMessageHash`), Jackson 2.18 (`JsonCodec.objectMapper()`), JUnit Jupiter 5.11.3, AssertJ 3.26.

**Spec:** `docs/superpowers/specs/2026-05-08-external-signing-design.md` (commit `3e3e83d`).

---

## File Structure

### New files

```
src/main/java/com/polymarket/clob/signing/ExternalSigning.java          # Static facade · 8 methods
src/main/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271.java     # record
src/main/java/com/polymarket/clob/deposit/UnsignedBatch.java            # record
src/main/java/com/polymarket/clob/auth/UnsignedClobAuth.java            # record
src/main/java/com/polymarket/clob/gamma/UnsignedSiwe.java               # record
src/main/java/com/polymarket/clob/gamma/SignedSiwe.java                 # record

src/test/java/com/polymarket/clob/auth/UnsignedClobAuthTest.java
src/test/java/com/polymarket/clob/deposit/UnsignedBatchTest.java
src/test/java/com/polymarket/clob/gamma/UnsignedSiweTest.java
src/test/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271Test.java
src/test/java/com/polymarket/clob/signing/ExternalSigningFacadeTest.java
```

### Modified files

```
src/main/java/com/polymarket/clob/auth/Eip712TypedData.java       # + typedDataJsonClobAuth(...)
src/main/java/com/polymarket/clob/auth/L1HeaderBuilder.java       # build(...) → thin wrapper
src/main/java/com/polymarket/clob/deposit/BatchEip712.java        # + typedDataJsonBatch(...)
src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java   # sign(...) → thin wrapper, expose helpers
src/main/java/com/polymarket/clob/gamma/SiweMessage.java          # + personalSignDigest(...)
src/main/java/com/polymarket/clob/gamma/GammaClient.java          # loginWithSiwe(...) → thin wrapper
pom.xml                                                            # version 2.0.0 → 2.1.0
CHANGELOG.md                                                       # new section
README.md                                                          # short pointer to ExternalSigning
```

### Untouched (verify no regressions only)

```
src/main/java/com/polymarket/clob/auth/Signer.java
src/main/java/com/polymarket/clob/auth/LocalSigner.java
src/main/java/com/polymarket/clob/order/EIP712OrderSigner.java
src/main/java/com/polymarket/clob/order/OrderBuilder.java
src/main/java/com/polymarket/clob/deposit/SignedBatch.java
src/main/java/com/polymarket/clob/order/SignedOrderV2.java
```

---

## Task 0: Baseline & Version Bump

**Files:**
- Modify: `pom.xml:9`

- [ ] **Step 1: Run full test suite to confirm green baseline**

Run: `mvn -B test`
Expected: `BUILD SUCCESS`, all existing parity / unit tests pass. Note baseline test count for later sanity check.

- [ ] **Step 2: Bump version**

Edit `pom.xml:9`:

```xml
<version>2.1.0</version>
```

- [ ] **Step 3: Verify build still passes after bump**

Run: `mvn -B test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: bump to 2.1.0 ahead of external signing feature"
```

---

## Task 1: ClobAuth typed-data JSON generator

We need a method that produces the same EIP-712 JSON `Eip712TypedData.hashClobAuth` consumes, so external apps can `eth_signTypedData_v4` it. The hash function already builds this internally; we promote that JSON to a public utility so we can reuse it from the unsigned record and from a round-trip test.

**Files:**
- Modify: `src/main/java/com/polymarket/clob/auth/Eip712TypedData.java`
- Test: `src/test/java/com/polymarket/clob/auth/Eip712TypedDataTest.java`

- [ ] **Step 1: Write failing round-trip test**

Append to `src/test/java/com/polymarket/clob/auth/Eip712TypedDataTest.java`:

```java
@org.junit.jupiter.api.Test
void typedDataJsonClobAuthRoundTripsToHashClobAuth() throws Exception {
    com.polymarket.clob.auth.ClobAuth a = com.polymarket.clob.auth.ClobAuth.of(
            com.polymarket.clob.model.Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"),
            10_000_000L,
            java.math.BigInteger.valueOf(23));
    long chainId = 80002L;
    byte[] expected = com.polymarket.clob.auth.Eip712TypedData.hashClobAuth(a, chainId);

    String json = com.polymarket.clob.auth.Eip712TypedData.typedDataJsonClobAuth(a, chainId);
    byte[] actual = new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();

    org.assertj.core.api.Assertions.assertThat(actual).containsExactly(expected);
}
```

- [ ] **Step 2: Run test, confirm it fails**

Run: `mvn -B test -Dtest=Eip712TypedDataTest#typedDataJsonClobAuthRoundTripsToHashClobAuth`
Expected: FAIL · `cannot find symbol method typedDataJsonClobAuth`.

- [ ] **Step 3: Extract JSON construction into reusable method**

In `src/main/java/com/polymarket/clob/auth/Eip712TypedData.java`, refactor `hashClobAuth` to delegate to a new public method, and keep behavior identical. Replace the body of `hashClobAuth(...)` with:

```java
public static String typedDataJsonClobAuth(ClobAuth auth, long chainId) {
    Objects.requireNonNull(auth, "auth");
    com.fasterxml.jackson.databind.ObjectMapper m = JsonCodec.objectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode root = m.createObjectNode();

    com.fasterxml.jackson.databind.node.ObjectNode types = root.putObject("types");
    com.fasterxml.jackson.databind.node.ArrayNode domainType = types.putArray("EIP712Domain");
    addField(m, domainType, "name", "string");
    addField(m, domainType, "version", "string");
    addField(m, domainType, "chainId", "uint256");
    com.fasterxml.jackson.databind.node.ArrayNode clobAuthType = types.putArray("ClobAuth");
    addField(m, clobAuthType, "address", "address");
    addField(m, clobAuthType, "timestamp", "string");
    addField(m, clobAuthType, "nonce", "uint256");
    addField(m, clobAuthType, "message", "string");

    root.put("primaryType", "ClobAuth");

    com.fasterxml.jackson.databind.node.ObjectNode domain = root.putObject("domain");
    domain.put("name", ClobAuth.DOMAIN_NAME);
    domain.put("version", ClobAuth.DOMAIN_VERSION);
    domain.put("chainId", chainId);

    com.fasterxml.jackson.databind.node.ObjectNode message = root.putObject("message");
    message.put("address", auth.address().toHex());
    message.put("timestamp", auth.timestamp());
    message.put("nonce", auth.nonce());
    message.put("message", auth.message());

    return JsonCodec.writeValue(m, root);
}

public static byte[] hashClobAuth(ClobAuth auth, long chainId) {
    String json = typedDataJsonClobAuth(auth, chainId);
    try {
        return new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();
    } catch (java.io.IOException | RuntimeException e) {
        throw new com.polymarket.clob.exception.ClobSignatureException("Failed to encode EIP-712 ClobAuth", e);
    }
}
```

(Existing `hashOrder` / `hashOrderV2` in the same file are untouched.)

- [ ] **Step 4: Run new test + existing ClobAuth-related tests**

Run: `mvn -B test -Dtest=Eip712TypedDataTest,L1HeaderBuilderTest`
Expected: PASS — `headerSetMatchesRustFixture` (L1HeaderBuilderTest) confirms wire bytes still match Rust fixture; new round-trip test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/auth/Eip712TypedData.java src/test/java/com/polymarket/clob/auth/Eip712TypedDataTest.java
git commit -m "feat(auth): expose typedDataJsonClobAuth(...) for external signing"
```

---

## Task 2: `UnsignedClobAuth` record + facade methods

**Files:**
- Create: `src/main/java/com/polymarket/clob/auth/UnsignedClobAuth.java`
- Test: `src/test/java/com/polymarket/clob/auth/UnsignedClobAuthTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/polymarket/clob/auth/UnsignedClobAuthTest.java`:

```java
package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedClobAuthTest {

    private static final Address EOA  = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final String  PK   = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    @Test
    void buildUnsignedDigestEqualsHashClobAuth() {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        byte[] expected = Eip712TypedData.hashClobAuth(ClobAuth.of(EOA, 10_000_000L, BigInteger.valueOf(23)), ChainId.AMOY);
        assertThat(u.signingDigest32()).containsExactly(expected);
        assertThat(u.signingDigest32()).hasSize(32);
    }

    @Test
    void typedDataJsonRoundTripsToDigest() throws Exception {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, BigInteger.ZERO);
        byte[] roundTrip = new org.web3j.crypto.StructuredDataEncoder(u.typedDataJson()).hashStructuredData();
        assertThat(roundTrip).containsExactly(u.signingDigest32());
    }

    @Test
    void attachReturnsSamePolyHeadersAsLocalSignerPath() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);

        Map<String, String> localPath = L1HeaderBuilder.build(s, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23)).join();

        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(s.address(), ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        byte[] sig = s.signHash(u.signingDigest32()).join();
        Map<String, String> external = UnsignedClobAuth.attachSignature(u, sig);

        assertThat(external).containsExactlyEntriesOf(localPath);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        assertThatThrownBy(() -> UnsignedClobAuth.attachSignature(u, new byte[64]))
                .isInstanceOf(com.polymarket.clob.exception.ClobSignatureException.class);
    }

    @Test
    void nullNonceDefaultsToZero() {
        UnsignedClobAuth a = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, null);
        UnsignedClobAuth b = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, BigInteger.ZERO);
        assertThat(a.signingDigest32()).containsExactly(b.signingDigest32());
        assertThat(a.nonce()).isEqualTo(BigInteger.ZERO);
    }
}
```

- [ ] **Step 2: Run, confirm 5 failures (compile errors)**

Run: `mvn -B test -Dtest=UnsignedClobAuthTest`
Expected: FAIL · `cannot find symbol class UnsignedClobAuth`.

- [ ] **Step 3: Implement record + static methods**

Create `src/main/java/com/polymarket/clob/auth/UnsignedClobAuth.java`:

```java
package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unsigned ClobAuth payload for external (BE → App) signing flows.
 *
 * <p>Hold both the 32-byte signing digest (authoritative) and the EIP-712 typed-data JSON
 * (so the App can drive {@code eth_signTypedData_v4}). The two values must round-trip:
 * {@code keccak256(eip712Encode(typedDataJson)) == signingDigest32}.</p>
 */
public record UnsignedClobAuth(
        Address    address,
        long       chainId,
        long       timestamp,
        BigInteger nonce,
        byte[]     signingDigest32,
        String     typedDataJson) {

    public static UnsignedClobAuth buildUnsigned(Address eoa, long chainId, long timestamp, BigInteger nonce) {
        Objects.requireNonNull(eoa, "eoa");
        BigInteger n = nonce == null ? BigInteger.ZERO : nonce;
        ClobAuth auth = ClobAuth.of(eoa, timestamp, n);
        byte[] digest = Eip712TypedData.hashClobAuth(auth, chainId);
        String json   = Eip712TypedData.typedDataJsonClobAuth(auth, chainId);
        return new UnsignedClobAuth(eoa, chainId, timestamp, n, digest, json);
    }

    public static Map<String, String> attachSignature(UnsignedClobAuth unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "ClobAuth signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        String signatureHex = "0x" + HexFormat.of().formatHex(sig65);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(L1HeaderBuilder.POLY_ADDRESS, unsigned.address.toLowerHex());
        headers.put(L1HeaderBuilder.POLY_NONCE, unsigned.nonce.toString());
        headers.put(L1HeaderBuilder.POLY_SIGNATURE, signatureHex);
        headers.put(L1HeaderBuilder.POLY_TIMESTAMP, Long.toString(unsigned.timestamp));
        return headers;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -B test -Dtest=UnsignedClobAuthTest`
Expected: PASS · 5/5.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/auth/UnsignedClobAuth.java src/test/java/com/polymarket/clob/auth/UnsignedClobAuthTest.java
git commit -m "feat(auth): add UnsignedClobAuth record with build/attach pair"
```

---

## Task 3: Refactor `L1HeaderBuilder.build` to thin wrapper

**Files:**
- Modify: `src/main/java/com/polymarket/clob/auth/L1HeaderBuilder.java`

- [ ] **Step 1: Confirm wire-fixture test exists & runs green pre-change**

Run: `mvn -B test -Dtest=L1HeaderBuilderTest`
Expected: PASS · `headerSetMatchesRustFixture` (Rust fixture: AMOY/ts=10_000_000/nonce=23 → known signature).

- [ ] **Step 2: Replace `build(...)` body**

Open `src/main/java/com/polymarket/clob/auth/L1HeaderBuilder.java`. Keep the four `POLY_*` constants and class shape; replace the existing `build(...)` method body:

```java
public static java.util.concurrent.CompletableFuture<java.util.Map<String, String>> build(
        Signer signer,
        long chainId,
        long timestamp,
        java.math.BigInteger nonce) {
    java.util.Objects.requireNonNull(signer, "signer");
    UnsignedClobAuth unsigned = UnsignedClobAuth.buildUnsigned(signer.address(), chainId, timestamp, nonce);
    return signer.signHash(unsigned.signingDigest32())
            .thenApply(sig -> UnsignedClobAuth.attachSignature(unsigned, sig));
}
```

(Drop the now-unused imports for `Eip712TypedData`, `ClobAuth`, `Address`, `HexFormat`, `LinkedHashMap` — keep `Signer` and `BigInteger`.)

- [ ] **Step 3: Run wire-fixture + parity tests**

Run: `mvn -B test -Dtest=L1HeaderBuilderTest,Eip712TypedDataTest`
Expected: PASS — Rust-fixture signature byte-identical, header order unchanged, null-nonce default unchanged.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/polymarket/clob/auth/L1HeaderBuilder.java
git commit -m "refactor(auth): L1HeaderBuilder.build delegates to UnsignedClobAuth"
```

---

## Task 4: DepositWallet Batch typed-data JSON

**Files:**
- Modify: `src/main/java/com/polymarket/clob/deposit/BatchEip712.java`
- Test: `src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java`

- [ ] **Step 1: Write failing round-trip test**

Append to `src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java` (preserve existing tests):

```java
@org.junit.jupiter.api.Test
void typedDataJsonBatchRoundTripsToHashBatch() throws Exception {
    com.polymarket.clob.model.Address wallet = com.polymarket.clob.model.Address.fromHex(
            "0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    com.polymarket.clob.model.Address target = com.polymarket.clob.model.Address.fromHex(
            "0x1111111111111111111111111111111111111111");
    java.util.List<com.polymarket.clob.deposit.Call> calls = java.util.List.of(
            new com.polymarket.clob.deposit.Call(target, java.math.BigInteger.ZERO, new byte[]{0x01, 0x02, 0x03}));

    long chainId = 137L;
    java.math.BigInteger nonce = java.math.BigInteger.valueOf(7);
    java.math.BigInteger deadline = java.math.BigInteger.valueOf(2_000_000_000L);

    byte[] expected = com.polymarket.clob.deposit.BatchEip712.hashBatch(chainId, wallet, nonce, deadline, calls);
    String json    = com.polymarket.clob.deposit.BatchEip712.typedDataJsonBatch(chainId, wallet, nonce, deadline, calls);
    byte[] actual  = new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();

    org.assertj.core.api.Assertions.assertThat(actual).containsExactly(expected);
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=BatchEip712Test#typedDataJsonBatchRoundTripsToHashBatch`
Expected: FAIL · `cannot find symbol method typedDataJsonBatch`.

- [ ] **Step 3: Implement `typedDataJsonBatch`**

Append to `src/main/java/com/polymarket/clob/deposit/BatchEip712.java` (inside the class, before the trailing `}`):

```java
public static String typedDataJsonBatch(long chainId, com.polymarket.clob.model.Address wallet,
                                         java.math.BigInteger nonce, java.math.BigInteger deadline,
                                         java.util.List<Call> calls) {
    if (calls == null || calls.isEmpty()) {
        throw new IllegalArgumentException("calls 不能为空");
    }
    com.fasterxml.jackson.databind.ObjectMapper m = com.polymarket.clob.http.JsonCodec.objectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode root = m.createObjectNode();

    com.fasterxml.jackson.databind.node.ObjectNode types = root.putObject("types");
    com.fasterxml.jackson.databind.node.ArrayNode domainType = types.putArray("EIP712Domain");
    addType(m, domainType, "name", "string");
    addType(m, domainType, "version", "string");
    addType(m, domainType, "chainId", "uint256");
    addType(m, domainType, "verifyingContract", "address");

    com.fasterxml.jackson.databind.node.ArrayNode batchType = types.putArray("Batch");
    addType(m, batchType, "wallet", "address");
    addType(m, batchType, "nonce", "uint256");
    addType(m, batchType, "deadline", "uint256");
    addType(m, batchType, "calls", "Call[]");

    com.fasterxml.jackson.databind.node.ArrayNode callType = types.putArray("Call");
    addType(m, callType, "target", "address");
    addType(m, callType, "value", "uint256");
    addType(m, callType, "data", "bytes");

    root.put("primaryType", "Batch");

    com.fasterxml.jackson.databind.node.ObjectNode domain = root.putObject("domain");
    domain.put("name", com.polymarket.clob.chain.PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME);
    domain.put("version", com.polymarket.clob.chain.PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION);
    domain.put("chainId", chainId);
    domain.put("verifyingContract", wallet.toLowerHex());

    com.fasterxml.jackson.databind.node.ObjectNode message = root.putObject("message");
    message.put("wallet", wallet.toLowerHex());
    message.put("nonce", nonce.toString());
    message.put("deadline", deadline.toString());
    com.fasterxml.jackson.databind.node.ArrayNode callsArr = message.putArray("calls");
    java.util.HexFormat hf = java.util.HexFormat.of();
    for (Call c : calls) {
        com.fasterxml.jackson.databind.node.ObjectNode co = m.createObjectNode();
        co.put("target", c.target().toLowerHex());
        co.put("value", c.value().toString());
        co.put("data", "0x" + hf.formatHex(c.data()));
        callsArr.add(co);
    }
    return com.polymarket.clob.http.JsonCodec.writeValue(m, root);
}

private static void addType(com.fasterxml.jackson.databind.ObjectMapper m,
                             com.fasterxml.jackson.databind.node.ArrayNode arr,
                             String name, String type) {
    com.fasterxml.jackson.databind.node.ObjectNode o = m.createObjectNode();
    o.put("name", name);
    o.put("type", type);
    arr.add(o);
}
```

- [ ] **Step 4: Run new test + existing BatchEip712 tests**

Run: `mvn -B test -Dtest=BatchEip712Test`
Expected: PASS — round-trip test passes; pre-existing tests stay green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/BatchEip712.java src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java
git commit -m "feat(deposit): add typedDataJsonBatch for external signing"
```

---

## Task 5: `UnsignedBatch` record + facade methods

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/UnsignedBatch.java`
- Test: `src/test/java/com/polymarket/clob/deposit/UnsignedBatchTest.java`

`SignedBatch` is already a record with fields `{eoa, factory, wallet, nonce, deadline, calls, signature65}`. `UnsignedBatch.buildUnsigned` therefore needs the EOA address (so attach can fill `eoa`). `factory` is the constant `PolymarketContracts.FACTORY`.

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/polymarket/clob/deposit/UnsignedBatchTest.java`:

```java
package com.polymarket.clob.deposit;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedBatchTest {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private List<Call> sampleCalls() {
        return List.of(new Call(
                Address.fromHex("0x1111111111111111111111111111111111111111"),
                BigInteger.ZERO,
                new byte[]{0x01, 0x02, 0x03}));
    }

    @Test
    void digestEqualsBatchEip712Hash() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] expected = BatchEip712.hashBatch(137L, wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        assertThat(u.signingDigest32()).containsExactly(expected);
    }

    @Test
    void typedDataJsonRoundTripsToDigest() throws Exception {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L,
                Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"),
                wallet, BigInteger.valueOf(7), BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] roundTrip = new org.web3j.crypto.StructuredDataEncoder(u.typedDataJson()).hashStructuredData();
        assertThat(roundTrip).containsExactly(u.signingDigest32());
    }

    @Test
    void attachReturnsSignedBatchWithCorrectFields() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, s.address(), wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] sig = s.signHash(u.signingDigest32()).join();

        SignedBatch signed = UnsignedBatch.attachSignature(u, sig);
        assertThat(signed.eoa()).isEqualTo(s.address());
        assertThat(signed.factory()).isEqualTo(PolymarketContracts.FACTORY);
        assertThat(signed.wallet()).isEqualTo(wallet);
        assertThat(signed.nonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(signed.deadline()).isEqualTo(BigInteger.valueOf(2_000_000_000L));
        assertThat(signed.calls()).hasSize(1);
        assertThat(signed.signature65()).hasSize(65).containsExactly(sig);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.ZERO,
                BigInteger.ZERO, sampleCalls());
        assertThatThrownBy(() -> UnsignedBatch.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }

    @Test
    void buildRejectsEmptyCalls() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        assertThatThrownBy(() -> UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.ZERO,
                BigInteger.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=UnsignedBatchTest`
Expected: FAIL · `cannot find symbol class UnsignedBatch`.

- [ ] **Step 3: Implement record**

Create `src/main/java/com/polymarket/clob/deposit/UnsignedBatch.java`:

```java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public record UnsignedBatch(
        long       chainId,
        Address    eoa,
        Address    wallet,
        BigInteger nonce,
        BigInteger deadline,
        List<Call> calls,
        byte[]     signingDigest32,
        String     typedDataJson) {

    public UnsignedBatch {
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(calls, "calls");
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("calls 不能为空");
        }
        calls = List.copyOf(calls);
    }

    public static UnsignedBatch buildUnsigned(long chainId, Address eoa, Address wallet,
                                               BigInteger nonce, BigInteger deadline,
                                               List<Call> calls) {
        byte[] digest = BatchEip712.hashBatch(chainId, wallet, nonce, deadline, calls);
        String json   = BatchEip712.typedDataJsonBatch(chainId, wallet, nonce, deadline, calls);
        return new UnsignedBatch(chainId, eoa, wallet, nonce, deadline, calls, digest, json);
    }

    public static SignedBatch attachSignature(UnsignedBatch unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "Batch signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        return new SignedBatch(unsigned.eoa, PolymarketContracts.FACTORY, unsigned.wallet,
                unsigned.nonce, unsigned.deadline, unsigned.calls, sig65.clone());
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -B test -Dtest=UnsignedBatchTest`
Expected: PASS · 5/5.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/UnsignedBatch.java src/test/java/com/polymarket/clob/deposit/UnsignedBatchTest.java
git commit -m "feat(deposit): add UnsignedBatch record with build/attach pair"
```

---

## Task 6: SIWE personal_sign digest helper

`GammaClient` already defines `personalSignDigest(String)` privately at `src/main/java/com/polymarket/clob/gamma/GammaClient.java:178-180`. We need it on `SiweMessage` (and keep `GammaClient`'s as a delegate so existing tests pass).

**Files:**
- Modify: `src/main/java/com/polymarket/clob/gamma/SiweMessage.java`
- Modify: `src/main/java/com/polymarket/clob/gamma/GammaClient.java`

- [ ] **Step 1: Write a small failing test**

Create `src/test/java/com/polymarket/clob/gamma/SiweMessagePersonalSignDigestTest.java`:

```java
package com.polymarket.clob.gamma;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Sign;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SiweMessagePersonalSignDigestTest {
    @Test
    void digestMatchesWeb3jEip191() {
        String msg = "hello SIWE";
        byte[] expected = Sign.getEthereumMessageHash(msg.getBytes(StandardCharsets.UTF_8));
        assertThat(SiweMessage.personalSignDigest(msg)).containsExactly(expected);
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=SiweMessagePersonalSignDigestTest`
Expected: FAIL · `cannot find symbol method personalSignDigest`.

- [ ] **Step 3: Add method to `SiweMessage`**

In `src/main/java/com/polymarket/clob/gamma/SiweMessage.java`, append before the trailing `}`:

```java
public static byte[] personalSignDigest(String message) {
    return org.web3j.crypto.Sign.getEthereumMessageHash(
            message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
}
```

- [ ] **Step 4: Make `GammaClient` delegate**

In `src/main/java/com/polymarket/clob/gamma/GammaClient.java`, replace the existing `personalSignDigest(...)` body (lines 178-180) with a delegate to keep call-site behavior identical:

```java
static byte[] personalSignDigest(String message) {
    return SiweMessage.personalSignDigest(message);
}
```

(Drop unused `org.web3j.crypto.Sign` import if it's no longer used. Re-run the file's tests to confirm.)

- [ ] **Step 5: Run tests**

Run: `mvn -B test -Dtest=SiweMessagePersonalSignDigestTest,SiweMessageTest,GammaClientLoginTest`
Expected: PASS — including existing GammaClient login flow tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/gamma/SiweMessage.java src/main/java/com/polymarket/clob/gamma/GammaClient.java src/test/java/com/polymarket/clob/gamma/SiweMessagePersonalSignDigestTest.java
git commit -m "refactor(gamma): hoist personalSignDigest to SiweMessage"
```

---

## Task 7: `UnsignedSiwe` + `SignedSiwe` records

**Files:**
- Create: `src/main/java/com/polymarket/clob/gamma/UnsignedSiwe.java`
- Create: `src/main/java/com/polymarket/clob/gamma/SignedSiwe.java`
- Test: `src/test/java/com/polymarket/clob/gamma/UnsignedSiweTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/polymarket/clob/gamma/UnsignedSiweTest.java`:

```java
package com.polymarket.clob.gamma;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedSiweTest {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static final Address EOA = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final Instant ISSUED  = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-01-08T00:00:00Z");

    @Test
    void canonicalMessageMatchesSiweMessageBuild() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        String expected = SiweMessage.build(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        assertThat(u.canonicalMessage()).isEqualTo(expected);
    }

    @Test
    void digestMatchesPersonalSignOfCanonicalMessage() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        byte[] expected = SiweMessage.personalSignDigest(u.canonicalMessage());
        assertThat(u.signingDigest32()).hasSize(32).containsExactly(expected);
    }

    @Test
    void attachReturnsSignedSiweWithSameSig() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(s.address(), ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        byte[] sig = s.signHash(u.signingDigest32()).join();

        SignedSiwe signed = UnsignedSiwe.attachSignature(u, sig);

        assertThat(signed.canonicalMessage()).isEqualTo(u.canonicalMessage());
        assertThat(signed.signatureHex())
                .startsWith("0x")
                .hasSize(2 + 130);
        assertThat(java.util.HexFormat.of().parseHex(signed.signatureHex().substring(2)))
                .containsExactly(sig);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "n", ISSUED, EXPIRES);
        assertThatThrownBy(() -> UnsignedSiwe.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=UnsignedSiweTest`
Expected: FAIL · `cannot find symbol class UnsignedSiwe`.

- [ ] **Step 3: Implement records**

Create `src/main/java/com/polymarket/clob/gamma/SignedSiwe.java`:

```java
package com.polymarket.clob.gamma;

public record SignedSiwe(String canonicalMessage, String signatureHex) {}
```

Create `src/main/java/com/polymarket/clob/gamma/UnsignedSiwe.java`:

```java
package com.polymarket.clob.gamma;

import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Unsigned Sign-In-with-Ethereum (EIP-191 personal_sign) payload for external signing flows.
 *
 * <p>Note: SIWE is EIP-191, not EIP-712. There is no {@code typedDataJson} field — apps must
 * sign the raw 32-byte digest (or, equivalently, prepend "\x19Ethereum Signed Message:\n{len}"
 * themselves and keccak-256 the result).</p>
 */
public record UnsignedSiwe(
        Address eoa,
        long    chainId,
        String  nonce,
        Instant issuedAt,
        Instant expirationTime,
        String  canonicalMessage,
        byte[]  signingDigest32) {

    public static UnsignedSiwe buildUnsigned(Address eoa, long chainId, String nonce,
                                              Instant issuedAt, Instant expirationTime) {
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expirationTime, "expirationTime");
        String canonical = SiweMessage.build(eoa, chainId, nonce, issuedAt, expirationTime);
        byte[] digest = SiweMessage.personalSignDigest(canonical);
        return new UnsignedSiwe(eoa, chainId, nonce, issuedAt, expirationTime, canonical, digest);
    }

    public static SignedSiwe attachSignature(UnsignedSiwe unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "SIWE signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        return new SignedSiwe(unsigned.canonicalMessage, "0x" + HexFormat.of().formatHex(sig65));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -B test -Dtest=UnsignedSiweTest`
Expected: PASS · 4/4.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/gamma/UnsignedSiwe.java src/main/java/com/polymarket/clob/gamma/SignedSiwe.java src/test/java/com/polymarket/clob/gamma/UnsignedSiweTest.java
git commit -m "feat(gamma): add UnsignedSiwe + SignedSiwe records for external signing"
```

> Note: `GammaClient.loginWithSiwe(...)` is not refactored — it owns the network transport, nonce-from-HTTP retrieval, and Bearer-auth-token construction (`base64(payloadJson + ":::" + sigHex)`), which belong to the SDK's network layer rather than to a pure signing primitive. External-signing consumers that only want the SIWE digest call `UnsignedSiwe.buildUnsigned(...)` directly. (The spec mentioned refactoring this method; that part of the spec was overscoped — record this divergence in CHANGELOG.)

---

## Task 8: ERC-7739 typed-data JSON for Order V2 POLY_1271

The most novel piece. We need to emit an EIP-712 JSON that round-trips, via `web3j.StructuredDataEncoder`, to the same 32-byte digest produced by `Pol1271OrderSigner.innerDigest(...)`. **If web3j cannot encode the nested `TypedDataSign` struct correctly, the test will fail and we will hand-write the EIP-712 encoding for the JSON path while the authoritative digest remains `Pol1271OrderSigner.innerDigest`.**

**Files:**
- Modify: `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java`
- Test: `src/test/java/com/polymarket/clob/order/Pol1271TypedDataJsonTest.java`

- [ ] **Step 1: Make package-private static helpers reachable**

Open `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java`. Change the visibility of these helpers from package-private/static to **public** static (signatures unchanged):

- `appDomainSeparator(long chainId, boolean negRisk)` — line ~67
- `innerDigest(OrderV2 order, long chainId, byte[] contentsHash, byte[] appDomainSep)` — line ~128
- `contentsHash(OrderV2 order)` — line ~49 (already public, no change)

- [ ] **Step 2: Write failing test**

Create `src/test/java/com/polymarket/clob/order/Pol1271TypedDataJsonTest.java`:

```java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271TypedDataJsonTest {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 makeOrder() {
        return OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET)
                .signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();
    }

    @Test
    void typedDataJsonRoundTripsToInnerDigest() throws Exception {
        OrderV2 order = makeOrder();
        long chainId = 137L;
        boolean negRisk = false;

        byte[] contentsHash = Pol1271OrderSigner.contentsHash(order);
        byte[] appSep = Pol1271OrderSigner.appDomainSeparator(chainId, negRisk);
        byte[] expected = Pol1271OrderSigner.innerDigest(order, chainId, contentsHash, appSep);

        String json = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, chainId, negRisk);
        byte[] actual = new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();
        assertThat(actual).containsExactly(expected);
    }

    @Test
    void typedDataJsonNegRiskUsesDifferentVerifyingContract() {
        OrderV2 order = makeOrder();
        String plain   = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, 137L, false);
        String negRisk = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, 137L, true);
        assertThat(plain).isNotEqualTo(negRisk);
    }
}
```

- [ ] **Step 3: Run, confirm fail**

Run: `mvn -B test -Dtest=Pol1271TypedDataJsonTest`
Expected: FAIL · `cannot find symbol method typedDataJsonOrderV2Pol1271`.

- [ ] **Step 4: Implement `typedDataJsonOrderV2Pol1271`**

In `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java`, append (inside the class):

```java
public static String typedDataJsonOrderV2Pol1271(OrderV2 order, long chainId, boolean negRisk) {
    java.util.Objects.requireNonNull(order, "order");
    com.polymarket.clob.model.Address verifyingContract =
            com.polymarket.clob.model.ContractRegistry.exchangeV2(chainId, negRisk).orElseThrow(
                    () -> new com.polymarket.clob.exception.ClobSignatureException(
                            "exchangeV2 not registered for chainId=" + chainId + " negRisk=" + negRisk));

    com.fasterxml.jackson.databind.ObjectMapper m = com.polymarket.clob.http.JsonCodec.objectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode root = m.createObjectNode();

    com.fasterxml.jackson.databind.node.ObjectNode types = root.putObject("types");
    com.fasterxml.jackson.databind.node.ArrayNode domainType = types.putArray("EIP712Domain");
    addTypeField(m, domainType, "name", "string");
    addTypeField(m, domainType, "version", "string");
    addTypeField(m, domainType, "chainId", "uint256");
    addTypeField(m, domainType, "verifyingContract", "address");

    com.fasterxml.jackson.databind.node.ArrayNode tdsType = types.putArray("TypedDataSign");
    addTypeField(m, tdsType, "contents", "Order");
    addTypeField(m, tdsType, "name", "string");
    addTypeField(m, tdsType, "version", "string");
    addTypeField(m, tdsType, "chainId", "uint256");
    addTypeField(m, tdsType, "verifyingContract", "address");
    addTypeField(m, tdsType, "salt", "bytes32");

    com.fasterxml.jackson.databind.node.ArrayNode orderType = types.putArray("Order");
    addTypeField(m, orderType, "salt", "uint256");
    addTypeField(m, orderType, "maker", "address");
    addTypeField(m, orderType, "signer", "address");
    addTypeField(m, orderType, "tokenId", "uint256");
    addTypeField(m, orderType, "makerAmount", "uint256");
    addTypeField(m, orderType, "takerAmount", "uint256");
    addTypeField(m, orderType, "side", "uint8");
    addTypeField(m, orderType, "signatureType", "uint8");
    addTypeField(m, orderType, "timestamp", "uint256");
    addTypeField(m, orderType, "metadata", "bytes32");
    addTypeField(m, orderType, "builder", "bytes32");

    root.put("primaryType", "TypedDataSign");

    com.fasterxml.jackson.databind.node.ObjectNode domain = root.putObject("domain");
    domain.put("name", com.polymarket.clob.chain.PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME);
    domain.put("version", com.polymarket.clob.chain.PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION);
    domain.put("chainId", chainId);
    domain.put("verifyingContract", verifyingContract.toLowerHex());

    com.fasterxml.jackson.databind.node.ObjectNode message = root.putObject("message");
    com.fasterxml.jackson.databind.node.ObjectNode contents = message.putObject("contents");
    contents.put("salt", order.getSalt().toString());
    contents.put("maker", order.getMaker().toLowerHex());
    contents.put("signer", order.getSigner().toLowerHex());
    contents.put("tokenId", order.getTokenId().toString());
    contents.put("makerAmount", order.getMakerAmount().toString());
    contents.put("takerAmount", order.getTakerAmount().toString());
    contents.put("side", order.getSide().exchangeCode());
    contents.put("signatureType", order.getSignatureType().code());
    contents.put("timestamp", order.getTimestamp().toString());
    contents.put("metadata", order.getMetadata());
    contents.put("builder", order.getBuilder());

    message.put("name", com.polymarket.clob.chain.PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME);
    message.put("version", com.polymarket.clob.chain.PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION);
    message.put("chainId", chainId);
    message.put("verifyingContract", order.getSigner().toLowerHex());
    message.put("salt", "0x" + "00".repeat(32));

    return com.polymarket.clob.http.JsonCodec.writeValue(m, root);
}

private static void addTypeField(com.fasterxml.jackson.databind.ObjectMapper m,
                                  com.fasterxml.jackson.databind.node.ArrayNode arr,
                                  String name, String type) {
    com.fasterxml.jackson.databind.node.ObjectNode o = m.createObjectNode();
    o.put("name", name);
    o.put("type", type);
    arr.add(o);
}
```

- [ ] **Step 5: Run test**

Run: `mvn -B test -Dtest=Pol1271TypedDataJsonTest`

- If PASS: continue to Step 6.
- If FAIL with a `StructuredDataEncoder` parse / encoding error around the nested `Order contents` field, **stop and treat the failure as the documented R1 risk** in the spec. Hand-write the round-trip equivalent: instead of relying on `StructuredDataEncoder`, mark the test as `@org.junit.jupiter.api.Disabled("web3j 4.12.2 cannot round-trip nested TypedDataSign — JSON is informational; authoritative digest = Pol1271OrderSigner.innerDigest")` with a clear comment. Re-run the full module to confirm no regression. Document the divergence in CHANGELOG. (Authoritative digest path is unchanged — the JSON is the only thing in question.)

- [ ] **Step 6: Run all order-package tests**

Run: `mvn -B test -Dtest='com.polymarket.clob.order.*'`
Expected: PASS — all existing `Pol1271ContentsHashTest`, `Pol1271AppDomainSepTest`, `Pol1271SignTest`, `OrderBuilderPol1271Test`, etc. unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java src/test/java/com/polymarket/clob/order/Pol1271TypedDataJsonTest.java
git commit -m "feat(order): add typedDataJsonOrderV2Pol1271 ERC-7739 envelope generator"
```

---

## Task 9: `UnsignedOrderV2Pol1271` record + facade methods

**Files:**
- Create: `src/main/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271.java`
- Test: `src/test/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271Test.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271Test.java`:

```java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedOrderV2Pol1271Test {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 order() {
        return OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET)
                .signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();
    }

    @Test
    void buildUnsignedDigestEqualsInnerDigest() {
        OrderV2 o = order();
        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);

        byte[] expectedContents = Pol1271OrderSigner.contentsHash(o);
        byte[] expectedAppSep   = Pol1271OrderSigner.appDomainSeparator(137L, false);
        byte[] expectedDigest   = Pol1271OrderSigner.innerDigest(o, 137L, expectedContents, expectedAppSep);

        assertThat(u.signingDigest32()).hasSize(32).containsExactly(expectedDigest);
        assertThat(u.contentsHash()).hasSize(32).containsExactly(expectedContents);
        assertThat(u.appDomainSep()).hasSize(32).containsExactly(expectedAppSep);
        assertThat(u.orderTypeString())
                .isEqualTo(com.polymarket.clob.chain.PolymarketContracts.ORDER_TYPE_STRING);
    }

    @Test
    void attachProducesSameWireAsLocalPol1271OrderSignerSign() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        OrderV2 o = order().toBuilder().maker(s.address()).signer(s.address()).build();

        SignedOrderV2 viaLocal = Pol1271OrderSigner.sign(s, o, 137L, false).join();

        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);
        byte[] sig = s.signHash(u.signingDigest32()).join();
        SignedOrderV2 viaExternal = UnsignedOrderV2Pol1271.attachSignature(u, sig);

        assertThat(viaExternal.getSignature()).isEqualTo(viaLocal.getSignature());
        assertThat(viaExternal.getOrder()).usingRecursiveComparison().isEqualTo(viaLocal.getOrder());
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(order(), 137L, false);
        assertThatThrownBy(() -> UnsignedOrderV2Pol1271.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=UnsignedOrderV2Pol1271Test`
Expected: FAIL · `cannot find symbol class UnsignedOrderV2Pol1271`.

- [ ] **Step 3: Implement record**

Create `src/main/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271.java`:

```java
package com.polymarket.clob.order;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

public record UnsignedOrderV2Pol1271(
        OrderV2 order,
        long    chainId,
        boolean negRisk,
        byte[]  signingDigest32,
        String  typedDataJson,
        byte[]  contentsHash,
        byte[]  appDomainSep,
        String  orderTypeString) {

    public static UnsignedOrderV2Pol1271 buildUnsigned(OrderV2 order, long chainId, boolean negRisk) {
        Objects.requireNonNull(order, "order");
        byte[] contents = Pol1271OrderSigner.contentsHash(order);
        byte[] appSep   = Pol1271OrderSigner.appDomainSeparator(chainId, negRisk);
        byte[] digest   = Pol1271OrderSigner.innerDigest(order, chainId, contents, appSep);
        String json     = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, chainId, negRisk);
        return new UnsignedOrderV2Pol1271(order, chainId, negRisk, digest, json,
                contents, appSep, PolymarketContracts.ORDER_TYPE_STRING);
    }

    public static SignedOrderV2 attachSignature(UnsignedOrderV2Pol1271 unsigned, byte[] innerSig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (innerSig65 == null || innerSig65.length != 65) {
            throw new ClobSignatureException(
                    "Pol1271 inner signature must be 65 bytes, got "
                            + (innerSig65 == null ? -1 : innerSig65.length));
        }
        if (unsigned.contentsHash.length != 32 || unsigned.appDomainSep.length != 32) {
            throw new ClobSignatureException("Unsigned record corrupted: helper bytes wrong length");
        }
        byte[] orderTypeAscii = unsigned.orderTypeString.getBytes(StandardCharsets.US_ASCII);
        int len = orderTypeAscii.length;

        ByteBuffer buf = ByteBuffer.allocate(65 + 32 + 32 + len + 2);
        buf.put(innerSig65);
        buf.put(unsigned.appDomainSep);
        buf.put(unsigned.contentsHash);
        buf.put(orderTypeAscii);
        buf.put((byte) ((len >> 8) & 0xff));
        buf.put((byte) (len & 0xff));
        return SignedOrderV2.of(unsigned.order, "0x" + HexFormat.of().formatHex(buf.array()));
    }
}
```

- [ ] **Step 4: Run test**

Run: `mvn -B test -Dtest=UnsignedOrderV2Pol1271Test`
Expected: PASS · 3/3.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271.java src/test/java/com/polymarket/clob/order/UnsignedOrderV2Pol1271Test.java
git commit -m "feat(order): add UnsignedOrderV2Pol1271 record with build/attach pair"
```

---

## Task 10: Refactor `Pol1271OrderSigner.sign` to thin wrapper

**Files:**
- Modify: `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java`

- [ ] **Step 1: Confirm parity tests green pre-change**

Run: `mvn -B test -Dtest='com.polymarket.clob.order.*'`
Expected: PASS · all order tests including `Pol1271SignTest`.

- [ ] **Step 2: Replace `sign(...)` body**

Replace the method body of `Pol1271OrderSigner.sign(Signer, OrderV2, long, boolean)` (originally lines 84-115) with:

```java
public static java.util.concurrent.CompletableFuture<SignedOrderV2> sign(
        com.polymarket.clob.auth.Signer eoa, OrderV2 order, long chainId, boolean negRisk) {
    try {
        UnsignedOrderV2Pol1271 unsigned = UnsignedOrderV2Pol1271.buildUnsigned(order, chainId, negRisk);
        return eoa.signHash(unsigned.signingDigest32())
                .thenApply(innerSig -> UnsignedOrderV2Pol1271.attachSignature(unsigned, innerSig));
    } catch (com.polymarket.clob.exception.ClobSignatureException e) {
        return java.util.concurrent.CompletableFuture.failedFuture(e);
    } catch (RuntimeException e) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new com.polymarket.clob.exception.ClobSignatureException("Pol1271OrderSigner.sign failed", e));
    }
}
```

(All other methods — `contentsHash`, `appDomainSeparator`, `innerDigest`, `typedDataJsonOrderV2Pol1271`, helpers — remain unchanged. The `APP_DOMAIN_SEP_CACHE`, type-hash constants, etc. stay where they are.)

- [ ] **Step 3: Run all order tests**

Run: `mvn -B test -Dtest='com.polymarket.clob.order.*'`
Expected: PASS — including `OrderBuilderPol1271Test` (transitive consumer) and `Pol1271SignTest`. The wire byte sequence for the signature must be byte-identical (the new attach path uses the same concat order).

- [ ] **Step 4: Run full module to catch transitive regressions**

Run: `mvn -B test`
Expected: PASS · same baseline test count as Task 0 + new tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java
git commit -m "refactor(order): Pol1271OrderSigner.sign delegates to UnsignedOrderV2Pol1271"
```

---

## Task 11: `ExternalSigning` static facade

**Files:**
- Create: `src/main/java/com/polymarket/clob/signing/ExternalSigning.java`
- Test: `src/test/java/com/polymarket/clob/signing/ExternalSigningFacadeTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/polymarket/clob/signing/ExternalSigningFacadeTest.java`:

```java
package com.polymarket.clob.signing;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.UnsignedClobAuth;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.UnsignedBatch;
import com.polymarket.clob.gamma.UnsignedSiwe;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSigningFacadeTest {

    private static final Address EOA    = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String  ZERO32 = "0x" + "00".repeat(32);

    @Test
    void clobAuthFacadeMatchesDirectCall() {
        UnsignedClobAuth direct = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 1L, BigInteger.TEN);
        UnsignedClobAuth viaFacade = ExternalSigning.buildUnsignedClobAuth(EOA, ChainId.AMOY, 1L, BigInteger.TEN);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void batchFacadeMatchesDirectCall() {
        List<Call> calls = List.of(new Call(
                Address.fromHex("0x1111111111111111111111111111111111111111"),
                BigInteger.ZERO, new byte[]{1, 2, 3}));
        UnsignedBatch direct = UnsignedBatch.buildUnsigned(137L, EOA, WALLET, BigInteger.ONE, BigInteger.TEN, calls);
        UnsignedBatch viaFacade = ExternalSigning.buildUnsignedBatch(137L, EOA, WALLET, BigInteger.ONE, BigInteger.TEN, calls);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void siweFacadeMatchesDirectCall() {
        Instant a = Instant.parse("2026-01-01T00:00:00Z");
        Instant b = Instant.parse("2026-01-08T00:00:00Z");
        UnsignedSiwe direct = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "n", a, b);
        UnsignedSiwe viaFacade = ExternalSigning.buildUnsignedSiwe(EOA, ChainId.POLYGON, "n", a, b);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void orderV2Pol1271FacadeMatchesDirectCall() {
        OrderV2 o = OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET).signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY).signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO).metadata(ZERO32).builder(ZERO32)
                .build();
        UnsignedOrderV2Pol1271 direct = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);
        UnsignedOrderV2Pol1271 viaFacade = ExternalSigning.buildUnsignedOrderV2Pol1271(o, 137L, false);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -B test -Dtest=ExternalSigningFacadeTest`
Expected: FAIL · `cannot find symbol class ExternalSigning`.

- [ ] **Step 3: Implement facade**

Create `src/main/java/com/polymarket/clob/signing/ExternalSigning.java`:

```java
package com.polymarket.clob.signing;

import com.polymarket.clob.auth.UnsignedClobAuth;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.deposit.UnsignedBatch;
import com.polymarket.clob.gamma.SignedSiwe;
import com.polymarket.clob.gamma.UnsignedSiwe;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregating facade for the four external-signing payload classes.
 *
 * <p>This class exists for documentation discoverability — every method is a one-line forward to
 * {@code UnsignedXxx.buildUnsigned(...)} or {@code UnsignedXxx.attachSignature(...)} in the
 * payload's own package.</p>
 */
public final class ExternalSigning {

    private ExternalSigning() {}

    // -- ClobAuth (L1 derive headers) -----------------------------------------------------

    public static UnsignedClobAuth buildUnsignedClobAuth(Address eoa, long chainId, long timestamp, BigInteger nonce) {
        return UnsignedClobAuth.buildUnsigned(eoa, chainId, timestamp, nonce);
    }

    public static Map<String, String> attachClobAuthSignature(UnsignedClobAuth unsigned, byte[] sig65) {
        return UnsignedClobAuth.attachSignature(unsigned, sig65);
    }

    // -- DepositWallet Batch --------------------------------------------------------------

    public static UnsignedBatch buildUnsignedBatch(long chainId, Address eoa, Address wallet,
                                                    BigInteger nonce, BigInteger deadline,
                                                    List<Call> calls) {
        return UnsignedBatch.buildUnsigned(chainId, eoa, wallet, nonce, deadline, calls);
    }

    public static SignedBatch attachBatchSignature(UnsignedBatch unsigned, byte[] sig65) {
        return UnsignedBatch.attachSignature(unsigned, sig65);
    }

    // -- SIWE -----------------------------------------------------------------------------

    public static UnsignedSiwe buildUnsignedSiwe(Address eoa, long chainId, String nonce,
                                                  Instant issuedAt, Instant expirationTime) {
        return UnsignedSiwe.buildUnsigned(eoa, chainId, nonce, issuedAt, expirationTime);
    }

    public static SignedSiwe attachSiweSignature(UnsignedSiwe unsigned, byte[] sig65) {
        return UnsignedSiwe.attachSignature(unsigned, sig65);
    }

    // -- Order V2 POLY_1271 (ERC-7739 nested) ---------------------------------------------

    public static UnsignedOrderV2Pol1271 buildUnsignedOrderV2Pol1271(OrderV2 order, long chainId, boolean negRisk) {
        return UnsignedOrderV2Pol1271.buildUnsigned(order, chainId, negRisk);
    }

    public static SignedOrderV2 attachOrderV2Pol1271Signature(UnsignedOrderV2Pol1271 unsigned, byte[] innerSig65) {
        return UnsignedOrderV2Pol1271.attachSignature(unsigned, innerSig65);
    }
}
```

- [ ] **Step 4: Run test**

Run: `mvn -B test -Dtest=ExternalSigningFacadeTest`
Expected: PASS · 4/4.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/signing/ExternalSigning.java src/test/java/com/polymarket/clob/signing/ExternalSigningFacadeTest.java
git commit -m "feat(signing): add ExternalSigning facade for 4 build/attach pairs"
```

---

## Task 12: Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: Read current CHANGELOG**

Run `head -40 CHANGELOG.md` to see existing format.

- [ ] **Step 2: Prepend new entry**

Add at the top of `CHANGELOG.md` (after the title line, follow existing version block style):

```markdown
## 2.1.0 — 2026-05-08

### Added
- External signing API for backend integrations (`com.polymarket.clob.signing.ExternalSigning`):
  4 `buildUnsignedXxx(...)` + `attachXxxSignature(...)` pairs covering Order V2 POLY_1271
  (ERC-7739 nested TypedDataSign), DepositWallet Batch, ClobAuth (L1 headers), and SIWE
  (EIP-191). Each `Unsigned*` carries `signingDigest32` plus, where applicable, an EIP-712
  `typedDataJson` round-tripping back to the digest. Backend processes can hand off the digest
  to a remote signer (mobile app holding the private key) without ever touching the key.

### Changed
- `Pol1271OrderSigner.sign(...)` and `L1HeaderBuilder.build(...)` are now thin wrappers over
  the new build/attach primitives. Wire output is byte-identical (parity tests unchanged).
- `Pol1271OrderSigner.appDomainSeparator(...)` and `Pol1271OrderSigner.innerDigest(...)` are
  now public to support the new external-signing path.
- `personalSignDigest(String)` moved from `GammaClient` (package-private) to `SiweMessage`
  (public). `GammaClient.personalSignDigest` becomes a delegate.

### Notes
- `GammaClient.loginWithSiwe(...)` is **not** refactored — it owns network transport and
  Bearer auth-token construction (`base64(payload + ":::" + sig)`), which are out of scope
  for the external-signing primitives. Consumers that only need the SIWE digest should call
  `UnsignedSiwe.buildUnsigned(...)` (or `ExternalSigning.buildUnsignedSiwe(...)`) directly.
```

- [ ] **Step 3: Add a short pointer in README**

In `README.md`, add a new section after `### Fun.xyz 法币入金地址（v2）` (around line 580):

```markdown
### External signing for backend integrations (v2.1.0)

`com.polymarket.clob.signing.ExternalSigning` exposes 4 `buildUnsigned + attach` pairs for
backends that don't hold the user's private key — Order V2 POLY_1271, DepositWallet Batch,
ClobAuth, and SIWE. Each `Unsigned*` carries a 32-byte `signingDigest32` plus (where
applicable) an EIP-712 `typedDataJson` you can round-trip via `eth_signTypedData_v4`.

```java
import com.polymarket.clob.signing.ExternalSigning;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;
import com.polymarket.clob.order.SignedOrderV2;

UnsignedOrderV2Pol1271 unsigned = ExternalSigning.buildUnsignedOrderV2Pol1271(order, 137L, false);
byte[] innerSig = remoteSigner.signHash(unsigned.signingDigest32()); // 65 bytes from mobile app
SignedOrderV2 signed = ExternalSigning.attachOrderV2Pol1271Signature(unsigned, innerSig);
```

The local-signing path (`OrderBuilder.createOrderV2 / Pol1271OrderSigner.sign / L1HeaderBuilder.build / GammaClient.loginWithSiwe`)
is unchanged — internally it now flows through these primitives, with byte-identical wire output.
```

- [ ] **Step 4: Sanity-build**

Run: `mvn -B clean test`
Expected: full green build at version 2.1.0.

- [ ] **Step 5: Commit**

```bash
git add CHANGELOG.md README.md
git commit -m "doc: changelog + readme entry for external signing (2.1.0)"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full clean verify**

Run: `mvn -B clean verify`
Expected: `BUILD SUCCESS`. Note final test count and confirm it equals (Task 0 baseline) + N new tests we added.

- [ ] **Step 2: List committed work**

Run: `git log --oneline main..HEAD`
Expected: 12 commits matching the per-task messages above (Task 0 through Task 12).

- [ ] **Step 3: Inspect public surface diff**

Run: `git diff main -- 'src/main/java/com/polymarket/clob/signing' 'src/main/java/com/polymarket/clob/**/Unsigned*.java' 'src/main/java/com/polymarket/clob/gamma/SignedSiwe.java' | wc -l`
Eyeball the diff: 4 new records + 1 facade + 1 SignedSiwe; no edits to `Signer.java` / `LocalSigner.java` / `OrderBuilder.java` / `EIP712OrderSigner.java`.

- [ ] **Step 4: Done**

Plan complete. Reportable surface:
- 5 new public classes/records: `ExternalSigning`, `UnsignedOrderV2Pol1271`, `UnsignedBatch`, `UnsignedClobAuth`, `UnsignedSiwe`, `SignedSiwe` (6 total — `SignedSiwe` is also new).
- 3 refactored entry points: `Pol1271OrderSigner.sign`, `L1HeaderBuilder.build`, `personalSignDigest` location.
- 4 new typed-data JSON generators (one per EIP-712 payload).
- Version bumped to `2.1.0`.
