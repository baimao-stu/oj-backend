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
 * AI 会话生命周期记录实体。
 */
@Data
@TableName("ai_chat_session")
public class AiChatSession implements Serializable {

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
     * OJ 题目编号。
     */
    private Long questionId;

    /**
     * 比赛编号；0 表示非比赛上下文。
     */
    private Long contestId;

    /**
     * 会话模式：普通 / 智能体。
     */
    private String mode;

    /**
     * 会话状态，定义见 {@code AiSessionStatusEnum}。
     */
    private Integer status;

    /**
     * 策略禁用会话时的原因。
     */
    private String disableReason;

    /**
     * 最后一条消息时间戳。
     */
    private Date lastMessageTime;

    /**
     * 会话过期时间戳。
     */
    private Date expireTime;

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

