package com.polymarket.clob.http;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;

/**
 * 注册 SDK 专有值类型的 Jackson 支持。
 *
 * <p>{@link Address} / {@link Hash32} 标量形态通过 {@code @JsonValue} + {@code @JsonCreator}
 * 已支持；本模块额外覆盖 {@link com.fasterxml.jackson.databind.KeyDeserializer}，
 * 让这两种类型可以直接作为 {@code Map} 的键（例如 {@code Map<AssetType, BalanceAllowanceEntry>}
 * 未来换成 {@code Map<Address, ...>} 时无需再加 KeyDeserializer）。</p>
 */
public final class ClobTypesModule extends SimpleModule {

    public ClobTypesModule() {
        super("ClobTypesModule", Version.unknownVersion());
        addKeyDeserializer(Address.class, new AddressKey());
        addKeyDeserializer(Hash32.class, new Hash32Key());
    }

    private static final class AddressKey extends KeyDeserializer {
        @Override
        public Address deserializeKey(String key, DeserializationContext ctxt) {
            try {
                return Address.fromHex(key);
            } catch (IllegalArgumentException e) {
                throw new ClobSerializationException("Invalid Address map-key: " + key, e);
            }
        }
    }

    private static final class Hash32Key extends KeyDeserializer {
        @Override
        public Hash32 deserializeKey(String key, DeserializationContext ctxt) {
            try {
                return Hash32.fromHex(key);
            } catch (IllegalArgumentException e) {
                throw new ClobSerializationException("Invalid Hash32 map-key: " + key, e);
            }
        }
    }
}
