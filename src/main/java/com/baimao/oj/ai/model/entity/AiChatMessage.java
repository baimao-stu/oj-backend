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
 * 会话内单条聊天消息实体。
 */
@Data
@TableName("ai_chat_message")
public class AiChatMessage implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话编号引用。
     */
    private Long sessionId;

    /**
     * 消息角色：用户 / 助手。
     */
    private String role;

    /**
     * 消息模式：普通 / 智能体。
     */
    private String mode;

    /**
     * 消息文本内容。
     */
    private String content;

    /**
     * 工具调用轨迹 JSON 字符串。
     */
    private String toolCalls;

    /**
     * 违规标记，0 表示正常。
     */
    private Integer violation;

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

