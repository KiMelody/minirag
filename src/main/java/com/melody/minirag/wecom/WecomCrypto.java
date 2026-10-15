package com.melody.minirag.wecom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 企微回调消息加解密与验签（按官方 WXBizMsgCrypt 协议自实现，无第三方依赖）。
 * 结构：Base64( AES-256-CBC( random(16) + msgLen(4, 大端) + msg + receiveId + PKCS7(32) ) )，iv = key 前 16 字节。
 */
public class WecomCrypto {

    private static final int BLOCK = 32;
    private final byte[] key;
    private final String token;
    private final String receiveId;
    private final SecureRandom random = new SecureRandom();

    public WecomCrypto(String token, String encodingAesKey, String receiveId) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 必须为 43 字符");
        }
        this.key = Base64.getDecoder().decode(encodingAesKey + "=");
        if (key.length != 32) {
            throw new IllegalArgumentException("EncodingAESKey 解码后必须为 32 字节");
        }
        this.token = token;
        this.receiveId = receiveId;
    }

    /** 校验回调签名：SHA1(字典序排序(token, timestamp, nonce, encrypt))。 */
    public boolean verify(String msgSignature, String timestamp, String nonce, String encrypt) {
        return signature(timestamp, nonce, encrypt).equals(msgSignature);
    }

    public String signature(String timestamp, String nonce, String encrypt) {
        try {
            List<String> parts = new ArrayList<>(List.of(token, timestamp, nonce, encrypt));
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] msg = plain.getBytes(StandardCharsets.UTF_8);
            byte[] rid = receiveId.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[16 + 4 + msg.length + rid.length];
            random.nextBytes(out);
            out[16] = (byte) (msg.length >>> 24);
            out[17] = (byte) (msg.length >>> 16);
            out[18] = (byte) (msg.length >>> 8);
            out[19] = (byte) msg.length;
            System.arraycopy(msg, 0, out, 20, msg.length);
            System.arraycopy(rid, 0, out, 20 + msg.length, rid.length);
            out = pad(out);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(out));
        } catch (Exception e) {
            throw new IllegalStateException("企微消息加密失败", e);
        }
    }

    public String decrypt(String base64Cipher) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            byte[] all = cipher.doFinal(Base64.getDecoder().decode(base64Cipher));
            int len = ((all[16] & 0xff) << 24) | ((all[17] & 0xff) << 16) | ((all[18] & 0xff) << 8) | (all[19] & 0xff);
            if (len < 0 || 24 + len > all.length) {
                throw new IllegalArgumentException("密文 msgLen 非法");
            }
            String msg = new String(all, 20, len, StandardCharsets.UTF_8);
            int pad = all[all.length - 1] & 0xff;
            int ridLen = all.length - 20 - len - pad;
            String rid = new String(all, 20 + len, ridLen, StandardCharsets.UTF_8);
            if (!receiveId.equals(rid)) {
                throw new IllegalArgumentException("receiveId 不匹配，可能发往其他应用");
            }
            return msg;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("企微消息解密失败", e);
        }
    }

    /** PKCS7 填充，块大小 32（微信官方约定）。 */
    private static byte[] pad(byte[] in) {
        int amount = BLOCK - in.length % BLOCK;
        byte[] out = Arrays.copyOf(in, in.length + amount);
        Arrays.fill(out, in.length, out.length, (byte) amount);
        return out;
    }
}
