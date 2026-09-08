package com.lengbot.util;

import com.lengbot.common.BizException;
import com.lengbot.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 通用凭证加解密（AES-GCM）。
 * <p>与 knowledge 模块的 {@code DifySecretCipher} 算法一致，但下沉到 common 供全模块复用，
 * 避免 ai 反向依赖 knowledge。密钥通过环境变量 {@code LENGBOT_CIPHER_KEY}（Base64 编码的 32 字节）注入，
 * 不依赖任何 yml 配置项，方便密钥外置。</p>
 * <p>密文格式：{@code Base64(iv):Base64(ciphertext)}。</p>
 * <p>解密兼容历史明文：若入参不含 {@code ":"} 分隔符，视为未加密的 legacy 明文直接返回，
 * 避免存量数据强制迁移造成停机。任意一次写入都会将其转为密文。</p>
 *
 * @author 阿冷
 * @since 2026-09-08
 */
@Component
public class SecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String SEPARATOR = ":";

    private final String encryptionKey;
    private final String encryptionKeyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(
            @Value("${LENGBOT_CIPHER_KEY:}") String encryptionKey,
            @Value("${LENGBOT_CIPHER_KEY_VERSION:v1}") String encryptionKeyVersion) {
        this.encryptionKey = encryptionKey;
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    /** 当前密钥版本标识，可选用于密钥轮换。 */
    public String getKeyVersion() {
        return encryptionKeyVersion;
    }

    /**
     * 加密明文。空值/空串直接透传（不加密空凭证）。
     *
     * @param plaintext 原始明文
     * @return 密文；入参为空时返回空
     */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + SEPARATOR
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证加密失败", e);
        }
    }

    /**
     * 解密密文。兼容 legacy 明文（不含分隔符时原样返回）。
     *
     * @param ciphertext 密文或 legacy 明文
     * @return 明文
     */
    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext) || ciphertext.indexOf(SEPARATOR) < 0) {
            // 空值或不含分隔符：视为未加密的 legacy 明文
            return ciphertext;
        }
        try {
            String[] parts = ciphertext.split(SEPARATOR, -1);
            if (parts.length != 2) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证格式非法");
            }
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH,
                    Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭证解密失败", e);
        }
    }

    /**
     * 判断字符串是否已是本 cipher 的密文格式（含合法分隔符）。
     * 用于写入时避免对已是密文的字段二次加密。
     */
    public boolean isCiphertext(String value) {
        return StringUtils.hasText(value) && value.indexOf(SEPARATOR) >= 0;
    }

    private SecretKeySpec secretKey() {
        if (!StringUtils.hasText(encryptionKey)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "LENGBOT_CIPHER_KEY 未配置：model_provider 凭证无法加解密，请设置 32 字节 Base64 编码密钥");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encryptionKey);
            if (bytes.length != 32) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "LENGBOT_CIPHER_KEY 必须是 Base64 编码的 32 字节（256 位）密钥");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "LENGBOT_CIPHER_KEY 非法 Base64", e);
        }
    }
}
