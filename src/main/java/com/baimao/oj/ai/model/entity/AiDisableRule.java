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
 * Entity for AI disable policy rules.
 */
@Data
@TableName("ai_disable_rule")
public class AiDisableRule implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Scope type: GLOBAL/CONTEST/QUESTION/USER.
     */
    private String scopeType;

    /**
     * Scope id; usually 0 for GLOBAL.
     */
    private Long scopeId;

    /**
     * Human-readable disable reason.
     */
    private String reason;

    /**
     * Rule start time, nullable.
     */
    private Date startTime;

    /**
     * Rule end time, nullable.
     */
    private Date endTime;

    /**
     * Enabled flag.
     */
    private Integer enabled;

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
