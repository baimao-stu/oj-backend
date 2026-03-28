package com.baimao.oj.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI module runtime properties.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * Global feature switch for AI module.
     */
    private Boolean enabled = true;

    /**
     * Session retention period in days.
     */
    private Integer retentionDays = 30;

    /**
     * AI request timeout in milliseconds.
     */
    private Long requestTimeoutMs = 30000L;

    /**
     * Max history messages loaded into prompt context.
     */
    private Integer maxHistoryMessages = 20;

    /**
     * Fallback model provider/model/base-url/api-key
     * when no active model config is found in database.
     */
    private String defaultProvider = "dashscope";
    private String defaultModelName = "qwen-plus";
    private String defaultApiKey = "";

    /**
     * Compliance refusal message for forbidden requests.
     */
    private String refuseMessage = "Sorry, I cannot directly provide full AC-ready solution code. I can help with ideas, debugging, and optimization.";

    /**
     * Secret key used for API key encryption/decryption.
     */
    private String securitySecretKey = "oj-ai-secret-key";
}
