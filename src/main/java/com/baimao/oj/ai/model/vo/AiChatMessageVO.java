package com.baimao.oj.ai.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 前端展示单条聊天消息的视图对象。
 */
@Data
public class AiChatMessageVO implements Serializable {
    /**
     * 消息编号。
     */
    private Long id;
    /**
     * 发言角色：用户 / 助手。
     */
    private String role;
    /**
     * 消息模式：普通 / 智能体。
     */
    private String mode;
    /**
     * 纯文本/Markdown 格式消息内容。
     */
    private String content;
    /**
     * 智能体模式下工具调用轨迹 JSON 字符串。
     */
    private String toolCalls;
    /**
     * 消息创建时间。
     */
    private Date createTime;
}

