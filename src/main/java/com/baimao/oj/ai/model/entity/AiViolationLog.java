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
 * 合规违规审计日志实体。
 */
@Data
@TableName("ai_violation_log")
public class AiViolationLog implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OJ 用户编号。
     */
    private Long userId;

    /**
     * 会话编号引用。
     */
    private Long sessionId;

    /**
     * 消息编号引用。
     */
    private Long messageId;

    /**
     * 违规规则类型标识。
     */
    private String ruleType;

    /**
     * 用于审计的违规内容截断摘要。
     */
    private String contentSnippet;

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

