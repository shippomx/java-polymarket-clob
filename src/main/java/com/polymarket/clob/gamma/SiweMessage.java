package com.polymarket.clob.gamma;

import com.polymarket.clob.model.Address;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * EIP-4361 Sign-In with Ethereum 文本构造。
 * Polymarket 使用固定 domain "polymarket.com" + statement
 * "Welcome to Polymarket! Sign to connect."。
 */
public final class SiweMessage {

    public static final String DOMAIN     = "polymarket.com";
    public static final String STATEMENT  = "Welcome to Polymarket! Sign to connect.";
    public static final String URI        = "https://polymarket.com";
    public static final String VERSION    = "1";

    private SiweMessage() {}

    public static String build(Address eoa, long chainId, String nonce,
                                Instant issuedAt, Instant expirationTime) {
        return DOMAIN + " wants you to sign in with your Ethereum account:\n"
                + eoa.toHex() + "\n"
                + "\n"
                + STATEMENT + "\n"
                + "\n"
                + "URI: " + URI + "\n"
                + "Version: " + VERSION + "\n"
                + "Chain ID: " + chainId + "\n"
                + "Nonce: " + nonce + "\n"
                + "Issued At: " + DateTimeFormatter.ISO_INSTANT.format(issuedAt) + "\n"
                + "Expiration Time: " + DateTimeFormatter.ISO_INSTANT.format(expirationTime);
    }
}
