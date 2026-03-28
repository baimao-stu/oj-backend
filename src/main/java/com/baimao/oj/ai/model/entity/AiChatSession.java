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
 * Entity for AI chat session lifecycle records.
 */
@Data
@TableName("ai_chat_session")
public class AiChatSession implements Serializable {

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
     * OJ question id.
     */
    private Long questionId;

    /**
     * Contest id; 0 means non-contest context.
     */
    private Long contestId;

    /**
     * Session mode: normal / agent.
     */
    private String mode;

    /**
     * Session status defined by {@code AiSessionStatusEnum}.
     */
    private Integer status;

    /**
     * Reason when session is disabled by policy.
     */
    private String disableReason;

    /**
     * Last message timestamp.
     */
    private Date lastMessageTime;

    /**
     * Session expiration timestamp.
     */
    private Date expireTime;

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
