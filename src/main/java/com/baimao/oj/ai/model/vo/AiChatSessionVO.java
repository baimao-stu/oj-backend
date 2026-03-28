package com.baimao.oj.ai.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 前端初始化会话使用的返回视图对象。
 */
@Data
public class AiChatSessionVO implements Serializable {
    /**
     * 会话编号。
     */
    private Long sessionId;
    /**
     * 会话状态枚举值。
     */
    private Integer status;
    /**
     * 当前会话模式。
     */
    private String mode;
    /**
     * 当前作用域下 AI 是否可用。
     */
    private Boolean enabled;
    /**
     * 禁用时的原因说明。
     */
    private String disableReason;
    /**
     * 有序历史消息列表。
     */
    private List<AiChatMessageVO> messageList;
}

