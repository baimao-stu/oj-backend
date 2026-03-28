package com.baimao.oj.ai.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.config.AiProperties;
import com.baimao.oj.ai.dto.AiChatSendRequest;
import com.baimao.oj.ai.dto.AiChatSessionRequest;
import com.baimao.oj.ai.entity.AiChatMessage;
import com.baimao.oj.ai.entity.AiChatSession;
import com.baimao.oj.ai.entity.AiDisableRule;
import com.baimao.oj.ai.entity.AiPromptConfig;
import com.baimao.oj.ai.entity.AiSensitiveWord;
import com.baimao.oj.ai.entity.AiToolCallLog;
import com.baimao.oj.ai.entity.AiToolConfig;
import com.baimao.oj.ai.entity.AiViolationLog;
import com.baimao.oj.ai.enums.AiChatModeEnum;
import com.baimao.oj.ai.enums.AiRuleScopeEnum;
import com.baimao.oj.ai.enums.AiSessionStatusEnum;
import com.baimao.oj.ai.mapper.AiChatMessageMapper;
import com.baimao.oj.ai.mapper.AiChatSessionMapper;
import com.baimao.oj.ai.mapper.AiDisableRuleMapper;
import com.baimao.oj.ai.mapper.AiPromptConfigMapper;
import com.baimao.oj.ai.mapper.AiSensitiveWordMapper;
import com.baimao.oj.ai.mapper.AiToolCallLogMapper;
import com.baimao.oj.ai.mapper.AiToolConfigMapper;
import com.baimao.oj.ai.mapper.AiViolationLogMapper;
import com.baimao.oj.ai.model.AiChatMessageVO;
import com.baimao.oj.ai.model.AiChatSessionVO;
import com.baimao.oj.ai.model.AiToolEventVO;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.CodeSandboxFactory;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.service.ContestService;
import com.baimao.oj.service.QuestionService;
import com.baimao.oj.service.QuestionSubmitService;
import com.baimao.oj.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
/**
 * Core AI chat implementation:
 * session lifecycle, compliance checks, tool orchestration, and SSE streaming.
 */
public class AiChatServiceImpl implements AiChatService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String EVENT_META = "meta";
    private static final String EVENT_TOOL = "tool";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    private static final List<String> TOOL_ORDER = Arrays.asList(
            "submission_analysis", "knowledge_retrieval", "testcase_generator", "sandbox_execute"
    );
    private static final Pattern MAIN_CODE_PATTERN = Pattern.compile(
            "(public\\s+class\\s+\\w+)|(public\\s+static\\s+void\\s+main\\s*\\()|(def\\s+\\w+\\s*\\()|(#include\\s*<)",
            Pattern.CASE_INSENSITIVE
    );

    @Resource
    private UserService userService;
    @Resource
    private QuestionService questionService;
    @Resource
    private ContestService contestService;
    @Resource
    private QuestionSubmitService questionSubmitService;
    @Resource
    private AiChatSessionMapper aiChatSessionMapper;
    @Resource
    private AiChatMessageMapper aiChatMessageMapper;
    @Resource
    private AiPromptConfigMapper aiPromptConfigMapper;
    @Resource
    private AiDisableRuleMapper aiDisableRuleMapper;
    @Resource
    private AiSensitiveWordMapper aiSensitiveWordMapper;
    @Resource
    private AiViolationLogMapper aiViolationLogMapper;
    @Resource
    private AiToolConfigMapper aiToolConfigMapper;
    @Resource
    private AiToolCallLogMapper aiToolCallLogMapper;
    @Resource
    private AiProperties aiProperties;
    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Value("${codesandbox.type:sample}")
    private String codeSandboxType;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * Load or create a scoped session and return ordered message history.
     */
    @Override
    public AiChatSessionVO getSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request) {
        checkAiEnabled();
        validateSessionRequest(aiChatSessionRequest);
        User loginUser = userService.getLoginUser(request);
        Question question = questionService.getById(aiChatSessionRequest.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        Long contestId = normalizeContestId(aiChatSessionRequest.getContestId());
        AiChatSession session = getOrCreateSession(loginUser.getId(), question.getId(), contestId);
        archiveIfExpired(session);
        String disableReason = checkDisableReason(loginUser.getId(), question.getId(), contestId);
        refreshSessionStatus(session, disableReason);

        List<AiChatMessageVO> messages = listSessionMessages(session.getId()).stream()
                .map(this::toMessageVO).collect(Collectors.toList());
        AiChatSessionVO sessionVO = new AiChatSessionVO();
        sessionVO.setSessionId(session.getId());
        sessionVO.setStatus(session.getStatus());
        sessionVO.setMode(session.getMode());
        sessionVO.setEnabled(StringUtils.isBlank(disableReason));
        sessionVO.setDisableReason(disableReason);
        sessionVO.setMessageList(messages);
        return sessionVO;
    }

    /**
     * Clear messages in current scoped session and reset status/mode.
     */
    @Override
    public Boolean clearSession(AiChatSessionRequest aiChatSessionRequest, HttpServletRequest request) {
        checkAiEnabled();
        validateSessionRequest(aiChatSessionRequest);
        User loginUser = userService.getLoginUser(request);
        Long contestId = normalizeContestId(aiChatSessionRequest.getContestId());
        AiChatSession session = findSession(loginUser.getId(), aiChatSessionRequest.getQuestionId(), contestId);
        if (session == null) {
            return true;
        }
        LambdaQueryWrapper<AiChatMessage> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(AiChatMessage::getSessionId, session.getId());
        aiChatMessageMapper.delete(deleteWrapper);

        session.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
        session.setDisableReason(null);
        session.setLastMessageTime(new Date());
        session.setExpireTime(buildExpireTime());
        session.setMode(AiChatModeEnum.NORMAL.getValue());
        aiChatSessionMapper.updateById(session);
        return true;
    }

    /**
     * Non-streaming chat entry.
     */
    @Override
    public AiChatMessageVO chat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request) {
        return doChat(aiChatSendRequest, request, null);
    }

    /**
     * Streaming chat entry based on SSE.
     */
    @Override
    public SseEmitter streamChat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request) {
        final SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                doChat(aiChatSendRequest, request, emitter);
                emitter.complete();
            } catch (Exception e) {
                log.error("streamChat error", e);
                try {
                    sendEvent(emitter, EVENT_ERROR, e.getMessage());
                } catch (Exception ex) {
                    log.error("send stream error event failed", ex);
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * Archive sessions that are still active but already expired.
     */
    @Override
    public void archiveExpiredSessions() {
        LambdaQueryWrapper<AiChatSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatSession::getStatus, AiSessionStatusEnum.ACTIVE.getValue());
        queryWrapper.lt(AiChatSession::getExpireTime, new Date());
        List<AiChatSession> expiredList = aiChatSessionMapper.selectList(queryWrapper);
        for (AiChatSession session : expiredList) {
            session.setStatus(AiSessionStatusEnum.ARCHIVED.getValue());
            aiChatSessionMapper.updateById(session);
        }
    }

    private AiChatMessageVO doChat(AiChatSendRequest requestBody, HttpServletRequest request, SseEmitter emitter) {
        checkAiEnabled();
        validateSendRequest(requestBody);
        User loginUser = userService.getLoginUser(request);
        Question question = questionService.getById(requestBody.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        Long contestId = normalizeContestId(requestBody.getContestId());
        AiChatSession session = getOrCreateSession(loginUser.getId(), question.getId(), contestId);
        archiveIfExpired(session);
        String disableReason = checkDisableReason(loginUser.getId(), question.getId(), contestId);
        if (StringUtils.isNotBlank(disableReason)) {
            refreshSessionStatus(session, disableReason);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, disableReason);
        }

        String userMessage = requestBody.getMessage().trim();
        blockSensitiveInput(loginUser.getId(), session.getId(), userMessage);
        if (containsPromptInjection(userMessage)) {
            saveViolation(loginUser.getId(), session.getId(), null, "prompt_injection", userMessage);
        }

        AiChatMessage userDbMessage = new AiChatMessage();
        userDbMessage.setSessionId(session.getId());
        userDbMessage.setRole(ROLE_USER);
        userDbMessage.setMode(AiChatModeEnum.fromValue(requestBody.getMode()).getValue());
        userDbMessage.setContent(userMessage);
        userDbMessage.setViolation(0);
        aiChatMessageMapper.insert(userDbMessage);

        List<AiChatMessage> history = listRecentMessages(session.getId(), aiProperties.getMaxHistoryMessages());
        String systemPrompt = getSystemPrompt(AiChatModeEnum.fromValue(requestBody.getMode()));

        List<AiToolEventVO> toolEvents = new ArrayList<>();
        if (AiChatModeEnum.AGENT == AiChatModeEnum.fromValue(requestBody.getMode())) {
            toolEvents = runAgentTools(loginUser, question, contestId, requestBody, emitter);
        }

        String modelUserPrompt = buildModelUserPrompt(question, requestBody, toolEvents);
        String assistantContent = generateAssistantContent(systemPrompt, history, modelUserPrompt, toolEvents);
        assistantContent = enforceCompliance(assistantContent, userMessage);
        assistantContent = moderateOutput(loginUser.getId(), session.getId(), assistantContent);

        AiChatMessage assistantMessage = new AiChatMessage();
        assistantMessage.setSessionId(session.getId());
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setMode(AiChatModeEnum.fromValue(requestBody.getMode()).getValue());
        assistantMessage.setContent(assistantContent);
        assistantMessage.setToolCalls(JSONUtil.toJsonStr(toolEvents));
        assistantMessage.setViolation(0);
        aiChatMessageMapper.insert(assistantMessage);

        session.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
        session.setDisableReason(null);
        session.setMode(AiChatModeEnum.fromValue(requestBody.getMode()).getValue());
        session.setLastMessageTime(new Date());
        session.setExpireTime(buildExpireTime());
        aiChatSessionMapper.updateById(session);

        if (emitter != null) {
            sendEvent(emitter, EVENT_META, buildMetaPayload(session, assistantMessage));
            streamText(emitter, assistantContent);
            sendEvent(emitter, EVENT_DONE, assistantMessage.getId());
        }

        return toMessageVO(assistantMessage);
    }

    private void checkAiEnabled() {
        if (!Boolean.TRUE.equals(aiProperties.getEnabled())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 功能当前未开启");
        }
    }

    private void validateSessionRequest(AiChatSessionRequest request) {
        if (request == null || request.getQuestionId() == null || request.getQuestionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目参数错误");
        }
    }

    private void validateSendRequest(AiChatSendRequest request) {
        if (request == null || request.getQuestionId() == null || request.getQuestionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目参数错误");
        }
        if (StringUtils.isBlank(request.getMessage())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不能为空");
        }
    }

    private Long normalizeContestId(Long contestId) {
        return contestId == null ? 0L : contestId;
    }

    private AiChatSession findSession(Long userId, Long questionId, Long contestId) {
        LambdaQueryWrapper<AiChatSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatSession::getUserId, userId);
        queryWrapper.eq(AiChatSession::getQuestionId, questionId);
        queryWrapper.eq(AiChatSession::getContestId, contestId);
        queryWrapper.orderByDesc(AiChatSession::getId);
        queryWrapper.last("limit 1");
        return aiChatSessionMapper.selectOne(queryWrapper);
    }

    private AiChatSession getOrCreateSession(Long userId, Long questionId, Long contestId) {
        AiChatSession session = findSession(userId, questionId, contestId);
        if (session != null) {
            return session;
        }
        AiChatSession newSession = new AiChatSession();
        newSession.setUserId(userId);
        newSession.setQuestionId(questionId);
        newSession.setContestId(contestId);
        newSession.setMode(AiChatModeEnum.NORMAL.getValue());
        newSession.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
        newSession.setLastMessageTime(new Date());
        newSession.setExpireTime(buildExpireTime());
        aiChatSessionMapper.insert(newSession);
        return newSession;
    }

    private Date buildExpireTime() {
        return DateUtil.offsetDay(new Date(), aiProperties.getRetentionDays());
    }

    private void archiveIfExpired(AiChatSession session) {
        if (session == null) {
            return;
        }
        if (session.getExpireTime() != null
                && session.getExpireTime().before(new Date())
                && Objects.equals(session.getStatus(), AiSessionStatusEnum.ACTIVE.getValue())) {
            session.setStatus(AiSessionStatusEnum.ARCHIVED.getValue());
            aiChatSessionMapper.updateById(session);
        }
    }

    private void refreshSessionStatus(AiChatSession session, String disableReason) {
        if (session == null) {
            return;
        }
        if (StringUtils.isNotBlank(disableReason)) {
            session.setStatus(AiSessionStatusEnum.DISABLED.getValue());
            session.setDisableReason(disableReason);
            aiChatSessionMapper.updateById(session);
            return;
        }
        if (Objects.equals(session.getStatus(), AiSessionStatusEnum.DISABLED.getValue())) {
            session.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
            session.setDisableReason(null);
            aiChatSessionMapper.updateById(session);
        }
    }

    private List<AiChatMessage> listSessionMessages(Long sessionId) {
        LambdaQueryWrapper<AiChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatMessage::getSessionId, sessionId);
        queryWrapper.orderByAsc(AiChatMessage::getId);
        return aiChatMessageMapper.selectList(queryWrapper);
    }

    private List<AiChatMessage> listRecentMessages(Long sessionId, Integer limit) {
        LambdaQueryWrapper<AiChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatMessage::getSessionId, sessionId);
        queryWrapper.orderByDesc(AiChatMessage::getId);
        queryWrapper.last("limit " + Math.max(limit == null ? 20 : limit, 1));
        List<AiChatMessage> list = aiChatMessageMapper.selectList(queryWrapper);
        list.sort((a, b) -> a.getId().compareTo(b.getId()));
        return list;
    }

    private AiChatMessageVO toMessageVO(AiChatMessage message) {
        AiChatMessageVO vo = new AiChatMessageVO();
        BeanUtils.copyProperties(message, vo);
        return vo;
    }

    private String checkDisableReason(Long userId, Long questionId, Long contestId) {
        if (contestId != null && contestId > 0) {
            Contest contest = contestService.getById(contestId);
            if (contest != null && contest.getEndTime() != null && contest.getEndTime().before(new Date())) {
                return "比赛已结束，会话已自动禁用";
            }
        }
        List<AiDisableRule> activeRules = listActiveDisableRules();
        if (CollUtil.isEmpty(activeRules)) {
            return null;
        }
        for (AiDisableRule rule : activeRules) {
            if (AiRuleScopeEnum.CONTEST.getValue().equalsIgnoreCase(rule.getScopeType())
                    && contestId != null && contestId > 0
                    && Objects.equals(rule.getScopeId(), contestId)) {
                return rule.getReason();
            }
        }
        for (AiDisableRule rule : activeRules) {
            if (AiRuleScopeEnum.QUESTION.getValue().equalsIgnoreCase(rule.getScopeType())
                    && Objects.equals(rule.getScopeId(), questionId)) {
                return rule.getReason();
            }
        }
        for (AiDisableRule rule : activeRules) {
            if (AiRuleScopeEnum.USER.getValue().equalsIgnoreCase(rule.getScopeType())
                    && Objects.equals(rule.getScopeId(), userId)) {
                return rule.getReason();
            }
        }
        for (AiDisableRule rule : activeRules) {
            if (AiRuleScopeEnum.GLOBAL.getValue().equalsIgnoreCase(rule.getScopeType())) {
                return rule.getReason();
            }
        }
        return null;
    }

    private List<AiDisableRule> listActiveDisableRules() {
        Date now = new Date();
        LambdaQueryWrapper<AiDisableRule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiDisableRule::getEnabled, 1);
        queryWrapper.and(wrapper -> wrapper.isNull(AiDisableRule::getStartTime).or().le(AiDisableRule::getStartTime, now));
        queryWrapper.and(wrapper -> wrapper.isNull(AiDisableRule::getEndTime).or().ge(AiDisableRule::getEndTime, now));
        queryWrapper.orderByDesc(AiDisableRule::getId);
        return aiDisableRuleMapper.selectList(queryWrapper);
    }

    private void blockSensitiveInput(Long userId, Long sessionId, String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        List<String> sensitiveWords = listSensitiveWords();
        for (String sensitiveWord : sensitiveWords) {
            if (text.contains(sensitiveWord)) {
                saveViolation(userId, sessionId, null, "input_sensitive", text);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "输入内容触发敏感词拦截");
            }
        }
    }

    private String moderateOutput(Long userId, Long sessionId, String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        List<String> sensitiveWords = listSensitiveWords();
        for (String sensitiveWord : sensitiveWords) {
            if (text.contains(sensitiveWord)) {
                saveViolation(userId, sessionId, null, "output_sensitive", text);
                return "输出内容触发安全策略，已自动拦截。你可以换一种问法继续。";
            }
        }
        return text;
    }

    private List<String> listSensitiveWords() {
        LambdaQueryWrapper<AiSensitiveWord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiSensitiveWord::getEnabled, 1);
        List<AiSensitiveWord> words = aiSensitiveWordMapper.selectList(queryWrapper);
        return words.stream().map(AiSensitiveWord::getWord).filter(StringUtils::isNotBlank).collect(Collectors.toList());
    }

    private boolean containsPromptInjection(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String lowerCase = text.toLowerCase();
        return lowerCase.contains("忽略之前")
                || lowerCase.contains("ignore previous")
                || lowerCase.contains("系统提示")
                || lowerCase.contains("developer message")
                || lowerCase.contains("越狱");
    }

    private String getSystemPrompt(AiChatModeEnum modeEnum) {
        LambdaQueryWrapper<AiPromptConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiPromptConfig::getScene, modeEnum.getValue());
        queryWrapper.eq(AiPromptConfig::getEnabled, 1);
        queryWrapper.eq(AiPromptConfig::getIsActive, 1);
        queryWrapper.orderByDesc(AiPromptConfig::getVersionNo);
        queryWrapper.last("limit 1");
        AiPromptConfig promptConfig = aiPromptConfigMapper.selectOne(queryWrapper);
        if (promptConfig != null && StringUtils.isNotBlank(promptConfig.getPromptContent())) {
            return promptConfig.getPromptContent();
        }
        if (AiChatModeEnum.AGENT == modeEnum) {
            return "你是 OJ 学习助手（Agent 模式）。你可以基于工具结果给出分析，但绝不能输出可直接通过判题的完整代码。";
        }
        return "你是 OJ 学习助手（普通模式）。你只能提供题意理解、思路提示、错误定位、复杂度分析与优化建议。";
    }

    private List<AiToolEventVO> runAgentTools(User user, Question question, Long contestId,
                                              AiChatSendRequest requestBody, SseEmitter emitter) {
        Map<String, AiToolConfig> configMap = listEnabledToolConfigMap();
        List<AiToolEventVO> result = new ArrayList<>();
        for (String toolName : TOOL_ORDER) {
            AiToolConfig toolConfig = configMap.get(toolName);
            if (toolConfig == null || !Objects.equals(toolConfig.getEnabled(), 1)) {
                continue;
            }
            if (!shouldRunTool(toolName, requestBody)) {
                continue;
            }
            Integer dailyLimit = toolConfig.getDailyLimit() == null ? 30 : toolConfig.getDailyLimit();
            if (!checkAndRecordToolCall(user.getId(), toolName, dailyLimit)) {
                AiToolEventVO eventVO = new AiToolEventVO(toolName, "skipped", "调用次数已达上限");
                result.add(eventVO);
                if (emitter != null) {
                    sendEvent(emitter, EVENT_TOOL, eventVO);
                }
                continue;
            }
            try {
                String summary = executeTool(toolName, user, question, contestId, requestBody);
                AiToolEventVO eventVO = new AiToolEventVO(toolName, "done", summary);
                result.add(eventVO);
                if (emitter != null) {
                    sendEvent(emitter, EVENT_TOOL, eventVO);
                }
            } catch (Exception e) {
                log.error("tool {} execute error", toolName, e);
                AiToolEventVO eventVO = new AiToolEventVO(toolName, "error", "工具执行失败：" + e.getMessage());
                result.add(eventVO);
                if (emitter != null) {
                    sendEvent(emitter, EVENT_TOOL, eventVO);
                }
            }
        }
        return result;
    }

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

    private boolean shouldRunTool(String toolName, AiChatSendRequest requestBody) {
        String message = StringUtils.defaultString(requestBody.getMessage()).toLowerCase();
        if ("submission_analysis".equals(toolName) || "knowledge_retrieval".equals(toolName)) {
            return true;
        }
        if ("testcase_generator".equals(toolName)) {
            return message.contains("测试") || message.contains("边界") || message.contains("用例");
        }
        if ("sandbox_execute".equals(toolName)) {
            return StringUtils.isNotBlank(requestBody.getUserCode())
                    && (message.contains("运行") || message.contains("输出") || message.contains("run"));
        }
        return false;
    }

    private boolean checkAndRecordToolCall(Long userId, String toolName, Integer dailyLimit) {
        String today = DateUtil.formatDate(new Date());
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

    private String executeTool(String toolName, User user, Question question, Long contestId, AiChatSendRequest requestBody) {
        if ("submission_analysis".equals(toolName)) {
            return toolSubmissionAnalysis(user.getId(), question.getId(), contestId);
        }
        if ("knowledge_retrieval".equals(toolName)) {
            return toolKnowledgeRetrieval(question);
        }
        if ("testcase_generator".equals(toolName)) {
            return toolGenerateTestCases(question);
        }
        if ("sandbox_execute".equals(toolName)) {
            return toolSandboxExecute(question, requestBody);
        }
        return "未识别工具";
    }

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
            return "暂无提交记录，建议先用样例验证基础逻辑。";
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
        return String.format("最近%d次提交中 AC=%d，最近一次结果=%s，耗时=%s。",
                submits.size(), acceptedCount, latestJudgeMsg, latestTime == null ? "N/A" : latestTime + "ms");
    }

    private String toolKnowledgeRetrieval(Question question) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotBlank(question.getTags())) {
            try {
                tags = JSONUtil.toList(question.getTags(), String.class);
            } catch (Exception ignored) {
            }
        }
        if (CollUtil.isEmpty(tags)) {
            return "未命中标签，建议优先抽象状态定义、边界条件和复杂度目标。";
        }
        List<String> tips = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String lower = tag.toLowerCase();
            if (lower.contains("dp")) {
                tips.add("动态规划：先定义状态含义，再写状态转移并处理初始化。");
            } else if (lower.contains("greedy") || lower.contains("贪心")) {
                tips.add("贪心：先证明局部最优可导向全局最优，再考虑反例验证。");
            } else if (lower.contains("graph") || lower.contains("图")) {
                tips.add("图论：先明确有向/无向、权重特性，再选 BFS/DFS/最短路。");
            } else if (lower.contains("string") || lower.contains("字符串")) {
                tips.add("字符串：关注双指针、哈希计数、前缀函数等技巧。");
            } else {
                tips.add(tag + "：先从朴素解出发，再做复杂度优化。");
            }
        }
        return "知识点检索建议：" + String.join(" ", tips);
    }

    private String toolGenerateTestCases(Question question) {
        String title = StringUtils.defaultString(question.getTitle());
        List<String> cases = new ArrayList<>();
        cases.add("1) 最小输入规模（边界空值/单元素）");
        cases.add("2) 最大输入规模（性能与复杂度压力）");
        cases.add("3) 特殊构造输入（重复值、逆序、全相同、极端值）");
        return "针对「" + title + "」建议补充测试：\n" + String.join("\n", cases);
    }

    private String toolSandboxExecute(Question question, AiChatSendRequest requestBody) {
        if (StringUtils.isBlank(requestBody.getUserCode())) {
            return "未提供代码，无法执行沙箱。";
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
            return "沙箱执行无返回。";
        }
        String firstOutput = CollUtil.isNotEmpty(response.getOutput()) ? response.getOutput().get(0) : "N/A";
        String status = response.getStatus() == null ? "unknown" : String.valueOf(response.getStatus());
        return "沙箱执行完成：status=" + status + "，样例输出=" + StrUtil.sub(firstOutput, 0, 120);
    }

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

    private String buildModelUserPrompt(Question question, AiChatSendRequest requestBody, List<AiToolEventVO> toolEvents) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("【题目标题】").append(StringUtils.defaultString(question.getTitle())).append("\n");
        promptBuilder.append("【题目内容】").append(StringUtils.defaultString(question.getContent())).append("\n");
        if (StringUtils.isNotBlank(requestBody.getLatestJudgeResult())) {
            promptBuilder.append("【最近判题结果】").append(requestBody.getLatestJudgeResult()).append("\n");
        }
        if (StringUtils.isNotBlank(requestBody.getUserCode())) {
            promptBuilder.append("【用户当前代码（可用于错误分析，不允许直接返回完整可 AC 代码）】\n")
                    .append(StrUtil.sub(requestBody.getUserCode(), 0, 4000)).append("\n");
        }
        if (CollUtil.isNotEmpty(toolEvents)) {
            promptBuilder.append("【工具结果】\n");
            for (AiToolEventVO eventVO : toolEvents) {
                promptBuilder.append("- ").append(eventVO.getToolName())
                        .append(": ").append(eventVO.getSummary()).append("\n");
            }
        }
        promptBuilder.append("【用户问题】").append(StringUtils.defaultString(requestBody.getMessage()));
        return promptBuilder.toString();
    }

    private String generateAssistantContent(String systemPrompt, List<AiChatMessage> history,
                                            String modelUserPrompt, List<AiToolEventVO> toolEvents) {
        String content = callSpringAi(systemPrompt, history, modelUserPrompt);
        if (StringUtils.isBlank(content)) {
            content = buildFallbackAnswer(modelUserPrompt, toolEvents);
        }
        return content;
    }

    private String callSpringAi(String systemPrompt, List<AiChatMessage> history, String modelUserPrompt) {
        try {
            String historyText = buildHistoryText(history);
            String userPrompt;
            if (StringUtils.isBlank(historyText)) {
                userPrompt = modelUserPrompt;
            } else {
                userPrompt = "【历史对话摘要】\n" + historyText + "\n\n【当前请求】\n" + modelUserPrompt;
            }
            return chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Spring AI call failed", e);
            return null;
        }
    }

    private String buildHistoryText(List<AiChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return "";
        }
        List<AiChatMessage> safeHistory = history;
        if (safeHistory.size() > 8) {
            safeHistory = safeHistory.subList(safeHistory.size() - 8, safeHistory.size());
        }
        StringBuilder builder = new StringBuilder();
        for (AiChatMessage message : safeHistory) {
            if (StringUtils.isBlank(message.getContent())) {
                continue;
            }
            builder.append(message.getRole()).append(": ")
                    .append(StrUtil.sub(message.getContent(), 0, 500))
                    .append("\n");
        }
        return builder.toString();
    }

    private String buildFallbackAnswer(String modelUserPrompt, List<AiToolEventVO> toolEvents) {
        StringBuilder sb = new StringBuilder();
        sb.append("我先给你一个不泄露完整代码的解题推进方案：\n");
        sb.append("1. 明确输入输出和边界，先写出你自己的思路草图。\n");
        sb.append("2. 用样例和极端数据逐步验证，优先定位第一处错误分支。\n");
        sb.append("3. 再检查复杂度是否满足题目限制。\n");
        if (CollUtil.isNotEmpty(toolEvents)) {
            sb.append("\n可参考工具结果：\n");
            for (AiToolEventVO eventVO : toolEvents) {
                sb.append("- ").append(eventVO.getToolName()).append("：")
                        .append(eventVO.getSummary()).append("\n");
            }
        }
        if (modelUserPrompt.contains("复杂度")) {
            sb.append("\n复杂度分析建议：先给出现有解法的时间/空间复杂度，再找主循环或重复计算热点。");
        }
        return sb.toString();
    }

    private String enforceCompliance(String output, String userMessage) {
        if (StringUtils.isBlank(output)) {
            return aiProperties.getRefuseMessage();
        }
        String lowerCaseMessage = userMessage == null ? "" : userMessage.toLowerCase();
        boolean directCodeAsk = lowerCaseMessage.contains("完整代码")
                || lowerCaseMessage.contains("直接给代码")
                || lowerCaseMessage.contains("ac代码")
                || lowerCaseMessage.contains("full code");
        if (directCodeAsk) {
            return aiProperties.getRefuseMessage() + "\n\n你可以把你现有代码发我，我来按行帮你定位问题。";
        }
        if (looksLikeFullSolutionCode(output)) {
            return aiProperties.getRefuseMessage() + "\n\n我可以继续帮你拆成伪代码步骤或只给关键函数思路。";
        }
        return output;
    }

    private boolean looksLikeFullSolutionCode(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        int lineCount = text.split("\n").length;
        if (lineCount < 20) {
            return false;
        }
        boolean hasCodeBlock = text.contains("```");
        boolean hasMainPattern = MAIN_CODE_PATTERN.matcher(text).find();
        return hasCodeBlock && hasMainPattern;
    }

    private void saveViolation(Long userId, Long sessionId, Long messageId, String ruleType, String content) {
        AiViolationLog violationLog = new AiViolationLog();
        violationLog.setUserId(userId);
        violationLog.setSessionId(sessionId);
        violationLog.setMessageId(messageId);
        violationLog.setRuleType(ruleType);
        violationLog.setContentSnippet(StrUtil.sub(content, 0, 500));
        aiViolationLogMapper.insert(violationLog);
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(JSONUtil.toJsonStr(data)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> buildMetaPayload(AiChatSession session, AiChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", session.getId());
        map.put("messageId", message.getId());
        map.put("mode", message.getMode());
        return map;
    }

    private void streamText(SseEmitter emitter, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        int chunkSize = 16;
        for (int i = 0; i < content.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, content.length());
            String chunk = content.substring(i, end);
            sendEvent(emitter, EVENT_DELTA, chunk);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
