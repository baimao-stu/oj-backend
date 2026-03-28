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
 * 提示词模板与版本管理实体。
 */
@Data
@TableName("ai_prompt_config")
public class AiPromptConfig implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提示词场景，例如 普通/智能体。
     */
    private String scene;

    /**
     * 提示词版本号。
     */
    private Integer versionNo;

    /**
     * 提示词模板内容。
     */
    private String promptContent;

    /**
     * 启用标记。
     */
    private Integer enabled;

    /**
     * 生效版本标记。
     */
    private Integer isActive;

    /**
     * 记录创建时间。
     */
    private Date createTime;

    /**
     * 记录更新时间。
     */
    private Date updateTime;

    /**
     * 逻辑删除标记。
     */
    @TableLogic
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}

