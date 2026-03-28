package com.baimao.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Entity for compliance violation audit logs.
 */
@Data
@TableName("ai_violation_log")
public class AiViolationLog implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OJ user id.
     */
    private Long userId;

    /**
     * Session id reference.
     */
    private Long sessionId;

    /**
     * Message id reference.
     */
    private Long messageId;

    /**
     * Violation rule type identifier.
     */
    private String ruleType;

    /**
     * Truncated violating content for audit.
     */
    private String contentSnippet;

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
