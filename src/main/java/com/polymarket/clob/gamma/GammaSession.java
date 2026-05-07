package com.polymarket.clob.gamma;

import java.time.Instant;

/**
 * Gamma 登录后获取的 session：合并后的 Cookie 头 + 过期时间。
 * cookieHeader 格式 "name1=val1; name2=val2"，可直接放进 HTTP "Cookie" 头。
 */
public record GammaSession(String cookieHeader, Instant expiresAt) {}
