package com.baimao.oj.ai.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体模式下推送到前端的工具事件载荷。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiToolEventVO implements Serializable {
    /**
     * 工具唯一名称。
     */
    private String toolName;
    /**
     * 执行状态：完成/跳过/错误。
     */
    private String status;
    /**
     * 可读摘要信息。
     */
    private String summary;
}

