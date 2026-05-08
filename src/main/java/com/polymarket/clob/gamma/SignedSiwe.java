package com.polymarket.clob.gamma;

public record SignedSiwe(String canonicalMessage, String signatureHex) {}
