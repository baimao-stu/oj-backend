package com.baimao.oj.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块运行时配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * AI 模块总开关。
     */
    private Boolean enabled = true;

    /**
     * 会话保留天数。
     */
    private Integer retentionDays = 30;

    /**
     * AI 请求超时时间（毫秒）。
     */
    private Long requestTimeoutMs = 30000L;

    /**
     * 注入提示词的最大历史消息条数。
     */
    private Integer maxHistoryMessages = 20;

    /**
     * 当数据库没有可用模型配置时的兜底提供商/模型/基础地址/接口密钥。
     * （用于数据库无生效模型配置场景）。
     */
    private String defaultProvider = "dashscope";
    private String defaultModelName = "qwen-plus";
    private String defaultBaseUrl = "https://dashscope.aliyuncs.com";
    private String defaultApiKey = "";

    /**
     * 违规请求时的拒答提示语。
     */
    private String refuseMessage = "Sorry, I cannot directly provide full AC-ready solution code. I can help with ideas, debugging, and optimization.";

    /**
     * 用于接口密钥加解密的密钥。
     */
    private String securitySecretKey = "oj-ai-secret-key";
}

