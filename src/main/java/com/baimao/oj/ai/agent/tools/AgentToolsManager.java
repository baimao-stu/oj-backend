package com.baimao.oj.ai.agent.tools;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.mapper.AiToolCallLogMapper;
import com.baimao.oj.ai.mapper.AiToolConfigMapper;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.entity.AiToolCallLog;
import com.baimao.oj.ai.model.entity.AiToolConfig;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.model.entity.Question;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;

@Component
@Slf4j
/**
 * 工具管理器。
 * 由模型决定是否调用，同时继续保留项目自己的限流、事件推送和业务上下文。
 */
public class AgentToolsManager {

    /**
     * 透传给 Spring AI `toolContext` 的上下文键。
     */
    public static final String TOOL_RUNTIME_CONTEXT_KEY = "aiToolRuntimeContext";

    @Resource
    private AgentTools agentTools;

    /**
     * 获取当前启用的工具回调列表。
     * ToolCallbacks.from(agentTools)) 扫描 `AgentTools` 中所有用 `@Tool` 注解声明的工具方法
     */
    public ToolCallback[] getEnabledToolCallbacks() {
        return Arrays.stream(ToolCallbacks.from(agentTools)).toArray(ToolCallback[]::new);
    }

    /**
     * 最近提交分析工具。
     */
    public List<ToolDefinition> listEnabledTools() {
        return Arrays.stream(getEnabledToolCallbacks()).map(ToolCallback::getToolDefinition).toList();
    }

    public String executeTool(String toolName, Map<String, Object> arguments, RuntimeContext runtimeContext) {
        String normalizedToolName = normalizeToolName(toolName);
        if (StringUtils.isBlank(normalizedToolName)) {
            return recordEvent(runtimeContext, "unknown_tool", "error", "Tool name is blank.");
        }
        ToolCallback toolCallback = resolveToolCallback(normalizedToolName);
        if (toolCallback == null) {
            return recordEvent(runtimeContext, normalizedToolName, "error",
                    "Unknown tool: " + normalizedToolName);
        }
        return executeTool(normalizedToolName, runtimeContext, context ->
                toolCallback.call(toToolArgumentsJson(arguments), new ToolContext(buildToolContext(context))));
    }

    /**
     * 工具执行总入口。
     * 统一处理开关、限流、异常日志和事件回传。
     */
    private String executeTool(String toolName, RuntimeContext runtimeContext, ToolExecutor executor) {
        try {
            String summary = executor.execute(runtimeContext);
            log.info("执行工具: {}， 执行结果: {}", toolName, summary);
            return recordEvent(runtimeContext, toolName, "done", summary);
        } catch (Exception e) {
            log.error("tool {} execute error", toolName, e);
            return recordEvent(runtimeContext, toolName, "error", "Tool execution failed: " + e.getMessage());
        }
    }


    private String normalizeToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return null;
        }
        return toolName.trim();
    }

    /**
     * 当前要调用的工具是否支持
     * @param toolName
     * @return
     */
    public boolean supportsTool(String toolName) {
        String normalizedToolName = normalizeToolName(toolName);
        return listEnabledTools().stream()
                .anyMatch(tool -> tool.name().equalsIgnoreCase(normalizedToolName));
    }

    private ToolCallback resolveToolCallback(String toolName) {
        return Arrays.stream(getEnabledToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equalsIgnoreCase(toolName))
                .findFirst()
                .orElse(null);
    }

    private String toToolArgumentsJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        return JSONUtil.toJsonStr(arguments);
    }

    private Map<String, Object> buildToolContext(RuntimeContext runtimeContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(TOOL_RUNTIME_CONTEXT_KEY, runtimeContext);
        return context;
    }

    /**
     * 从 Spring AI 的 `ToolContext` 中取回项目侧运行时上下文。
     */
    /**
     * 记录工具执行结果，并在流式场景下向前端推送 `tool` 事件。
     */
    private String recordEvent(RuntimeContext runtimeContext, String toolName, String status, String summary) {
        AiToolEventVO eventVO = new AiToolEventVO(toolName, status, summary);
        runtimeContext.toolEvents().add(eventVO);
        if (runtimeContext.emitter() != null) {
            sendEvent(runtimeContext.emitter(), EVENT_TOOL, eventVO);
        }
        return summary;
    }

    /**
     * 发送工具执行事件。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(JSONUtil.toJsonStr(data)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 工具运行时上下文。
     * 这里聚合了工具执行时会用到的用户、题目、比赛、请求体和 SSE 句柄。
     */
    public record RuntimeContext(Long userId, Question question, Long contestId,
                                 AiChatSendRequest requestBody, SseEmitter emitter,
                                 List<AiToolEventVO> toolEvents) {
    }
}
