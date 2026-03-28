package com.baimao.oj.ai.util;

import com.baimao.oj.ai.config.AiProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 用于模型接口密钥入库前加解密的工具类。
 */
@Component
public class AiKeyCryptoUtil {

    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    @Resource
    private AiProperties aiProperties;

    /**
     * 使用本地对称密钥加密明文。
     */
    public String encrypt(String plainText) {
        if (StringUtils.isBlank(plainText)) {
            return plainText;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, buildSecretKey());
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            return plainText;
        }
    }

    /**
     * 解密由 {@link #encrypt(String)} 生成的密文。
     */
    public String decrypt(String encryptedText) {
        if (StringUtils.isBlank(encryptedText)) {
            return encryptedText;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, buildSecretKey());
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encryptedText;
        }
    }

    /**
     * 根据配置密钥构建 16 字节 AES 密钥。
     */
    private SecretKeySpec buildSecretKey() {
        byte[] keyBytes = aiProperties.getSecuritySecretKey().getBytes(StandardCharsets.UTF_8);
        byte[] finalKey = Arrays.copyOf(keyBytes, 16);
        return new SecretKeySpec(finalKey, "AES");
    }
}

