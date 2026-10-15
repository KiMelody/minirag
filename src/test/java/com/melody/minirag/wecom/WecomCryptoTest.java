package com.melody.minirag.wecom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WecomCryptoTest {

    private static final String TOKEN = "test-token";
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String CORP_ID = "ww8f0c2a1b3d5e7f9";

    @Test
    void encryptDecryptRoundTrip() {
        WecomCrypto crypto = new WecomCrypto(TOKEN, AES_KEY, CORP_ID);
        String plain = "守卫者平台按点位收费，每点位每年380元。<xml>&amp;特殊字符]]></xml>";
        assertEquals(plain, crypto.decrypt(crypto.encrypt(plain)));
    }

    @Test
    void decryptRejectedOnReceiveIdMismatch() {
        WecomCrypto a = new WecomCrypto(TOKEN, AES_KEY, CORP_ID);
        WecomCrypto b = new WecomCrypto(TOKEN, AES_KEY, "ww-other-corp");
        assertThrows(IllegalArgumentException.class, () -> b.decrypt(a.encrypt("hello")));
    }

    @Test
    void signatureVerifiesAndTamperingRejected() {
        WecomCrypto crypto = new WecomCrypto(TOKEN, AES_KEY, CORP_ID);
        String enc = crypto.encrypt("hello");
        String sig = crypto.signature("1409659813", "n0nce", enc);
        assertTrue(crypto.verify(sig, "1409659813", "n0nce", enc));
        // 首字符换成与原值不同的 hex 字符（固定换 '0' 有 1/16 概率与原值相同导致偶发失败）
        char first = sig.charAt(0);
        String tampered = (first == '0' ? "1" : "0") + sig.substring(1);
        assertThrows(AssertionError.class, () ->
                assertTrue(crypto.verify(tampered, "1409659813", "n0nce", enc)));
    }

    @Test
    void constructorRejectsInvalidAesKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new WecomCrypto(TOKEN, "too-short", CORP_ID));
    }
}
