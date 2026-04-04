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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;

@Deprecated
//@Component
@Slf4j
/**
 * 工具管理器。
 * 由模型决定是否调用，同时继续保留项目自己的限流、事件推送和业务上下文。
 */
public class AgentToolsManagerOld {

    public record ToolDeclaration(String name, String description, String inputSchema) {
    }

    private static final List<ToolDeclaration> DECLARED_TOOLS = List.of(
            new ToolDeclaration(
                    "submission_analysis",
                    "Analyze the user's recent submissions for the current question and summarize pass rate, latest judge result, and execution metrics.",
                    "{\"limit\": optional integer, default 10}"
            ),
            new ToolDeclaration(
                    "searchWeb",
                    "Search public knowledge related to the current programming problem, algorithm, or failure pattern.",
                    "{\"query\": required string}"
            ),
            new ToolDeclaration(
                    "testcase_generator",
                    "Generate boundary, tricky, and stress test ideas for the current programming problem.",
                    "{}"
            )
    );

    /**
     * 透传给 Spring AI `toolContext` 的上下文键。
     */
    public static final String TOOL_RUNTIME_CONTEXT_KEY = "aiToolRuntimeContext";

    @Resource
    private AiToolConfigMapper aiToolConfigMapper;

    @Resource
    private AiToolCallLogMapper aiToolCallLogMapper;

    @Resource
    private AgentTools agentTools;

    /**
     * 获取当前启用的工具回调列表。
     * 数据库里禁用的工具不会暴露给模型。
     */
    public ToolCallback[] getEnabledToolCallbacks() {
        Map<String, AiToolConfig> configMap = listEnabledToolConfigMap();
        return Arrays.stream(ToolCallbacks.from(agentTools))
                // 数据库管理哪些工具可用
                .filter(callback -> configMap.containsKey(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 最近提交分析工具。
     */
    public List<ToolDeclaration> listEnabledTools() {
        Map<String, ToolCallback> callbackMap = new LinkedHashMap<>();
        for (ToolCallback callback : getEnabledToolCallbacks()) {
            callbackMap.put(callback.getToolDefinition().name(), callback);
        }
        List<ToolDeclaration> definitions = new ArrayList<>();
        for (ToolDeclaration declaration : DECLARED_TOOLS) {
            if (callbackMap.containsKey(declaration.name())) {
                definitions.add(declaration);
            }
        }
        return definitions;
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

//    public String executeTool(String toolName, Map<String, Object> arguments, RuntimeContext runtimeContext) {
//        String normalizedToolName = normalizeToolName(toolName);
//        if (StringUtils.isBlank(normalizedToolName)) {
//            return recordEvent(runtimeContext, "unknown_tool", "error", "Tool name is blank.");
//        }
//        ToolCallback toolCallback = resolveToolCallback(normalizedToolName);
//        if (toolCallback == null) {
//            return recordEvent(runtimeContext, normalizedToolName, "error",
//                    "Unknown tool: " + normalizedToolName);
//        }
//        return executeTool(normalizedToolName, runtimeContext, context ->
//                toolCallback.call(toToolArgumentsJson(arguments), new ToolContext(buildToolContext(context))));
//    }

    /**
     * 工具执行总入口。
     * 统一处理开关、限流、异常日志和事件回传。
     */
//    private String executeTool(String toolName, RuntimeContext runtimeContext, ToolExecutor executor) {
//        AiToolConfig toolConfig = resolveToolConfig(toolName);
//        if (toolConfig == null || !Objects.equals(toolConfig.getEnabled(), 1)) {
//            return recordEvent(runtimeContext, toolName, "skipped", "Tool is disabled.");
//        }
//        int dailyLimit = toolConfig.getDailyLimit() == null ? 30 : toolConfig.getDailyLimit();
//        if (!checkAndRecordToolCall(runtimeContext.userId(), toolName, dailyLimit)) {
//            return recordEvent(runtimeContext, toolName, "skipped", "Daily tool call limit exceeded.");
//        }
//        try {
//            String summary = executor.execute(runtimeContext);
//            log.info("执行工具: {}， 执行结果: {}", toolName, summary);
//            return recordEvent(runtimeContext, toolName, "done", summary);
//        } catch (Exception e) {
//            log.error("tool {} execute error", toolName, e);
//            return recordEvent(runtimeContext, toolName, "error", "Tool execution failed: " + e.getMessage());
//        }
//    }


    private String normalizeToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return null;
        }
        return toolName.trim();
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
     * 查询当前启用的工具配置；如果数据库没有配置，则使用默认兜底配置。
     */
    private Map<String, AiToolConfig> listEnabledToolConfigMap() {
        Map<String, AiToolConfig> map = new HashMap<>();
        LambdaQueryWrapper<AiToolConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiToolConfig::getEnabled, 1);
        List<AiToolConfig> dbList = aiToolConfigMapper.selectList(queryWrapper);
        if (CollUtil.isNotEmpty(dbList)) {
            for (AiToolConfig config : dbList) {
                map.put(config.getToolName(), config);
            }
            return map;
        }
        for (ToolDeclaration tool : DECLARED_TOOLS) {
            AiToolConfig config = new AiToolConfig();
            config.setToolName(tool.name());
            config.setEnabled(1);
            config.setDailyLimit(30);
            map.put(tool.name(), config);
        }
        return map;
    }

    /**
     * 查询单个工具的启用配置。
     */
    private AiToolConfig resolveToolConfig(String toolName) {
        return listEnabledToolConfigMap().get(toolName);
    }

    /**
     * 按“用户 + 工具 + 日期”维度做每日调用次数限制。
     */
    private boolean checkAndRecordToolCall(Long userId, String toolName, Integer dailyLimit) {
        String today = DateUtil.formatDate(new java.util.Date());
        LambdaQueryWrapper<AiToolCallLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiToolCallLog::getUserId, userId);
        queryWrapper.eq(AiToolCallLog::getToolName, toolName);
        queryWrapper.eq(AiToolCallLog::getCallDate, today);
        queryWrapper.last("limit 1");
        AiToolCallLog logRow = aiToolCallLogMapper.selectOne(queryWrapper);
        if (logRow == null) {
            logRow = new AiToolCallLog();
            logRow.setUserId(userId);
            logRow.setToolName(toolName);
            logRow.setCallDate(today);
            logRow.setCallCount(1);
            aiToolCallLogMapper.insert(logRow);
            return true;
        }
        if (logRow.getCallCount() >= dailyLimit) {
            return false;
        }
        logRow.setCallCount(logRow.getCallCount() + 1);
        aiToolCallLogMapper.updateById(logRow);
        return true;
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
