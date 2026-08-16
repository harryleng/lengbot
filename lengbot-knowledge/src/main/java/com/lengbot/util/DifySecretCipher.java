package com.lengbot.util;

import com.lengbot.common.BizException;
import com.lengbot.config.DifyProperties;
import com.lengbot.enums.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用 AES-GCM 加解密外部连接器凭证。 */
@Component
@RequiredArgsConstructor
public class DifySecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final DifyProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 加密 Token。
     *
     * @param plaintext 原始 Token
     * @return Base64(iv):Base64(ciphertext) 格式密文
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID, e);
        }
    }

    /**
     * 解密 Token。
     *
     * @param encryptedCiphertext 加密凭证
     * @return 原始 Token
     */
    public String decrypt(String encryptedCiphertext) {
        try {
            String[] parts = encryptedCiphertext.split(":", -1);
            if (parts.length != 2) {
                throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID);
            }
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH,
                    Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID, e);
        }
    }

    private SecretKeySpec secretKey() {
        if (!StringUtils.hasText(properties.getEncryptionKey())) {
            throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (bytes.length != 32) {
                throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID);
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.DIFY_ENCRYPTION_KEY_INVALID, e);
        }
    }
}
