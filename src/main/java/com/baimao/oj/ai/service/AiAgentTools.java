package com.baimao.oj.ai.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.mapper.AiToolCallLogMapper;
import com.baimao.oj.ai.mapper.AiToolConfigMapper;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.entity.AiToolCallLog;
import com.baimao.oj.ai.model.entity.AiToolConfig;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.CodeSandboxFactory;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.service.QuestionSubmitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;
import static com.baimao.oj.ai.service.Constant.TOOL_ORDER;

@Component
@Slf4j
/**
 * Spring AI 工具集合。
 * 这里把原本手工编排的题目分析能力收敛成 `@Tool` 方法，
 * 由模型决定是否调用，同时继续保留项目自己的限流、事件推送和业务上下文。
 */
public class AiAgentTools {

    /**
     * 透传给 Spring AI `toolContext` 的上下文键。
     */
    public static final String TOOL_RUNTIME_CONTEXT_KEY = "aiToolRuntimeContext";

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private AiToolConfigMapper aiToolConfigMapper;

    @Resource
    private AiToolCallLogMapper aiToolCallLogMapper;

    @Value("${codesandbox.type:sample}")
    private String codeSandboxType;

    /**
     * 获取当前启用的工具回调列表。
     * 数据库里禁用的工具不会暴露给模型。
     */
    public ToolCallback[] getEnabledToolCallbacks() {
        Map<String, AiToolConfig> configMap = listEnabledToolConfigMap();
        return java.util.Arrays.stream(ToolCallbacks.from(this))
                .filter(callback -> configMap.containsKey(callback.getToolDefinition().name()))
                .sorted((left, right) -> Integer.compare(toolOrder(left), toolOrder(right)))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 最近提交分析工具。
     */
    @Tool(name = "submission_analysis", description = "Analyze the user's recent submissions for the current question and summarize pass rate and latest judge result.")
    public String submissionAnalysis(ToolContext toolContext) {
        return executeTool("submission_analysis", toolContext,
                context -> toolSubmissionAnalysis(context.userId(), context.question().getId(), context.contestId()));
    }

    /**
     * 题目知识提示工具。
     */
    @Tool(name = "knowledge_retrieval", description = "Summarize algorithm hints for the current question based on its tags and content.")
    public String knowledgeRetrieval(ToolContext toolContext) {
        return executeTool("knowledge_retrieval", toolContext,
                context -> toolKnowledgeRetrieval(context.question()));
    }

    /**
     * 测试用例建议工具。
     */
    @Tool(name = "testcase_generator", description = "Generate edge cases and test ideas for the current programming problem.")
    public String testcaseGenerator(ToolContext toolContext) {
        return executeTool("testcase_generator", toolContext,
                context -> toolGenerateTestCases(context.question()));
    }

    /**
     * 代码沙箱运行工具。
     */
    @Tool(name = "sandbox_execute", description = "Run the user's current code against a sample input from the current question and summarize the result.")
    public String sandboxExecute(ToolContext toolContext) {
        return executeTool("sandbox_execute", toolContext,
                context -> toolSandboxExecute(context.question(), context.requestBody()));
    }

    /**
     * 按预定义顺序暴露工具，避免工具列表顺序不稳定。
     */
    private int toolOrder(ToolCallback callback) {
        int index = TOOL_ORDER.indexOf(callback.getToolDefinition().name());
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    /**
     * 工具执行总入口。
     * 统一处理开关、限流、异常日志和事件回传。
     */
    private String executeTool(String toolName, ToolContext toolContext, ToolExecutor executor) {
        RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        AiToolConfig toolConfig = resolveToolConfig(toolName);
        if (toolConfig == null || !Objects.equals(toolConfig.getEnabled(), 1)) {
            return recordEvent(runtimeContext, toolName, "skipped", "Tool is disabled.");
        }
        int dailyLimit = toolConfig.getDailyLimit() == null ? 30 : toolConfig.getDailyLimit();
        if (!checkAndRecordToolCall(runtimeContext.userId(), toolName, dailyLimit)) {
            return recordEvent(runtimeContext, toolName, "skipped", "Daily tool call limit exceeded.");
        }
        try {
            String summary = executor.execute(runtimeContext);
            return recordEvent(runtimeContext, toolName, "done", summary);
        } catch (Exception e) {
            log.error("tool {} execute error", toolName, e);
            return recordEvent(runtimeContext, toolName, "error", "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * 从 Spring AI 的 `ToolContext` 中取回项目侧运行时上下文。
     */
    private RuntimeContext getRuntimeContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing tool runtime context");
        }
        Object runtimeContext = toolContext.getContext().get(TOOL_RUNTIME_CONTEXT_KEY);
        if (runtimeContext instanceof RuntimeContext context) {
            return context;
        }
        throw new IllegalStateException("Invalid tool runtime context");
    }

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
        LambdaQueryWrapper<AiToolConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiToolConfig::getEnabled, 1);
        List<AiToolConfig> dbList = aiToolConfigMapper.selectList(queryWrapper);
        Map<String, AiToolConfig> map = new HashMap<>();
        if (CollUtil.isNotEmpty(dbList)) {
            for (AiToolConfig config : dbList) {
                map.put(config.getToolName(), config);
            }
            return map;
        }
        for (String tool : TOOL_ORDER) {
            AiToolConfig config = new AiToolConfig();
            config.setToolName(tool);
            config.setEnabled(1);
            config.setDailyLimit(30);
            map.put(tool, config);
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
     * 汇总用户最近的判题记录，给模型提供更具体的辅导上下文。
     */
    private String toolSubmissionAnalysis(Long userId, Long questionId, Long contestId) {
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getUserId, userId);
        queryWrapper.eq(QuestionSubmit::getQuestionId, questionId);
        if (contestId != null && contestId > 0) {
            queryWrapper.eq(QuestionSubmit::getContestId, contestId);
        }
        queryWrapper.orderByDesc(QuestionSubmit::getCreateTime);
        queryWrapper.last("limit 5");
        List<QuestionSubmit> submits = questionSubmitService.list(queryWrapper);
        if (CollUtil.isEmpty(submits)) {
            return "No related submission history was found.";
        }
        long acceptedCount = 0;
        String latestJudgeMsg = "N/A";
        Long latestTime = null;
        for (QuestionSubmit submit : submits) {
            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
            if (judgeInfo == null) {
                continue;
            }
            if ("Accepted".equalsIgnoreCase(judgeInfo.getMessage())) {
                acceptedCount++;
            }
            if ("N/A".equals(latestJudgeMsg)) {
                latestJudgeMsg = StringUtils.defaultString(judgeInfo.getMessage(), "N/A");
                latestTime = judgeInfo.getTime();
            }
        }
        return String.format("Recent %d submissions: accepted=%d, latestResult=%s, latestTime=%s.",
                submits.size(), acceptedCount, latestJudgeMsg, latestTime == null ? "N/A" : latestTime + "ms");
    }

    /**
     * 根据题目标签生成算法方向提示。
     */
    private String toolKnowledgeRetrieval(Question question) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotBlank(question.getTags())) {
            try {
                tags = JSONUtil.toList(question.getTags(), String.class);
            } catch (Exception ignored) {
            }
        }
        if (CollUtil.isEmpty(tags)) {
            return "Question tags are missing. Focus on state definition, boundary cases, and target complexity first.";
        }
        List<String> tips = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String lower = tag.toLowerCase();
            if (lower.contains("dp")) {
                tips.add("Dynamic programming: define the state clearly and draw the transition path before coding.");
            } else if (lower.contains("greedy")) {
                tips.add("Greedy: verify the local choice can be extended to a globally optimal solution.");
            } else if (lower.contains("graph")) {
                tips.add("Graph: confirm traversal order, visited-state design, and pruning opportunities.");
            } else if (lower.contains("string")) {
                tips.add("String: pay close attention to boundary handling and consider two-pointers or sliding window techniques.");
            } else {
                tips.add(tag + ": review the core algorithm or data structure pattern behind this tag.");
            }
        }
        return String.join("\n", tips);
    }

    /**
     * 生成基础、边界和压力场景的测试建议。
     */
    private String toolGenerateTestCases(Question question) {
        return """
                Suggested test ideas:
                1. Basic sample that verifies the main happy path.
                2. Boundary values such as minimum, maximum, empty, or single-element input.
                3. Stress case with large input to validate complexity and stability.
                Question title: %s
                """.formatted(StringUtils.defaultString(question.getTitle()));
    }

    /**
     * 使用现有代码沙箱对用户代码做一次样例运行。
     */
    private String toolSandboxExecute(Question question, AiChatSendRequest requestBody) {
        if (requestBody == null || StringUtils.isBlank(requestBody.getUserCode())) {
            return "No user code was provided, so sandbox execution was skipped.";
        }
        List<String> inputList = new ArrayList<>();
        if (StringUtils.isNotBlank(question.getJudgeCase())) {
            try {
                List<JudgeCase> judgeCaseList = JSONUtil.toList(question.getJudgeCase(), JudgeCase.class);
                if (CollUtil.isNotEmpty(judgeCaseList)) {
                    inputList.add(judgeCaseList.get(0).getInput());
                }
            } catch (Exception ignored) {
            }
        }
        if (CollUtil.isEmpty(inputList)) {
            inputList.add("");
        }
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(codeSandboxType);
        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .language(StringUtils.defaultIfBlank(requestBody.getLanguage(), "java"))
                .code(requestBody.getUserCode())
                .input(inputList)
                .build();
        ExecuteCodeResponse response = codeSandbox.executeCode(executeCodeRequest);
        if (response == null) {
            return "Sandbox execution returned no response.";
        }
        String firstOutput = CollUtil.isNotEmpty(response.getOutput()) ? response.getOutput().get(0) : "N/A";
        String status = response.getStatus() == null ? "unknown" : String.valueOf(response.getStatus());
        return "Sandbox finished: status=" + status + ", sampleOutput=" + StrUtil.sub(firstOutput, 0, 120);
    }

    /**
     * 解析判题结果 JSON。
     */
    private JudgeInfo parseJudgeInfo(String judgeInfoJson) {
        if (StringUtils.isBlank(judgeInfoJson)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoJson, JudgeInfo.class);
        } catch (Exception e) {
            return null;
        }
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

    @FunctionalInterface
    /**
     * 工具统一执行函数接口，便于在公共流程中复用限流和事件逻辑。
     */
    private interface ToolExecutor {
        String execute(RuntimeContext runtimeContext);
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
