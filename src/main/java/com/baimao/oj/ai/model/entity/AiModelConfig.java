package com.baimao.oj.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Entity for model provider configuration and routing priority.
 */
@Data
@TableName("ai_model_config")
public class AiModelConfig implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Provider name, e.g. dashscope.
     */
    private String provider;

    /**
     * Model name.
     */
    private String modelName;

    /**
     * Provider base URL.
     */
    private String baseUrl;

    /**
     * Encrypted API key.
     */
    private String apiKey;

    /**
     * Lower value means higher priority.
     */
    private Integer priority;

    /**
     * Enabled flag.
     */
    private Integer enabled;

    /**
     * Default model flag.
     */
    private Integer isDefault;

    /**
     * Record creation time.
     */
    private Date createTime;

    /**
     * Record update time.
     */
    private Date updateTime;

    /**
     * Logical deletion flag.
     */
    @TableLogic
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
