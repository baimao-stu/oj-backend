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
 * Entity for prompt templates and version control.
 */
@Data
@TableName("ai_prompt_config")
public class AiPromptConfig implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Prompt scene, e.g. normal/agent.
     */
    private String scene;

    /**
     * Prompt version number.
     */
    private Integer versionNo;

    /**
     * Prompt template content.
     */
    private String promptContent;

    /**
     * Enabled flag.
     */
    private Integer enabled;

    /**
     * Active version flag.
     */
    private Integer isActive;

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
