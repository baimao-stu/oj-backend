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
 * Entity for one chat message in a session.
 */
@Data
@TableName("ai_chat_message")
public class AiChatMessage implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Session id reference.
     */
    private Long sessionId;

    /**
     * Message role: user / assistant.
     */
    private String role;

    /**
     * Message mode: normal / agent.
     */
    private String mode;

    /**
     * Message text content.
     */
    private String content;

    /**
     * Tool call trace JSON string.
     */
    private String toolCalls;

    /**
     * Violation flag, 0 for normal.
     */
    private Integer violation;

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
