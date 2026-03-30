package com.baimao.oj.ai.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.config.AiProperties;
import com.baimao.oj.ai.mapper.AiChatMessageMapper;
import com.baimao.oj.ai.mapper.AiChatSessionMapper;
import com.baimao.oj.ai.mapper.AiDisableRuleMapper;
import com.baimao.oj.ai.mapper.AiPromptConfigMapper;
import com.baimao.oj.ai.mapper.AiSensitiveWordMapper;
import com.baimao.oj.ai.mapper.AiViolationLogMapper;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.dto.AiChatSessionRequest;
import com.baimao.oj.ai.model.entity.AiChatMessage;
import com.baimao.oj.ai.model.entity.AiChatSession;
import com.baimao.oj.ai.model.entity.AiDisableRule;
import com.baimao.oj.ai.model.entity.AiPromptConfig;
import com.baimao.oj.ai.model.entity.AiSensitiveWord;
import com.baimao.oj.ai.model.entity.AiViolationLog;
import com.baimao.oj.ai.model.enums.AiChatModeEnum;
import com.baimao.oj.ai.model.enums.AiRuleScopeEnum;
import com.baimao.oj.ai.model.enums.AiSessionStatusEnum;
import com.baimao.oj.ai.model.vo.AiChatMessageVO;
import com.baimao.oj.ai.model.vo.AiChatSessionVO;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.ai.tools.AgentToolsManager;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.service.ContestService;
import com.baimao.oj.service.QuestionService;
import com.baimao.oj.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.baimao.oj.ai.service.Constant.DEFAULT_AGENT_SYSTEM_PROMPT;
import static com.baimao.oj.ai.service.Constant.DEFAULT_NORMAL_SYSTEM_PROMPT;
import static com.baimao.oj.ai.service.Constant.EVENT_DELTA;
import static com.baimao.oj.ai.service.Constant.EVENT_DONE;
import static com.baimao.oj.ai.service.Constant.EVENT_ERROR;
import static com.baimao.oj.ai.service.Constant.EVENT_META;
import static com.baimao.oj.ai.service.Constant.ROLE_ASSISTANT;
import static com.baimao.oj.ai.service.Constant.ROLE_USER;

@Service
@Slf4j
/**
 * AI 对话服务核心实现。
 * 这里保留了项目原有的会话、消息、风控、SSE 推送等业务逻辑，
 * 同时把上下文记忆和工具调用切换到 Spring AI 的原生能力上。
 */
public class AiChatServiceImpl implements AiChatService {

    private static final String SAFE_GUARD_BLOCKED_RESPONSE = "由于内容敏感，我无法回复。我们能否换个说法或者讨论其他话题？";
    private static final String AI_CALL_FAILED_RESPONSE = "抱歉，我暂时无法回答你的问题。";

    /**
     * 统一的用户提示词模板。
     * 题目信息、语言和用户代码都通过参数注入，
     * 避免继续手工拼接大段字符串。
     */
    private static final String USER_PROMPT_TEMPLATE = """
            Question Title:
            {title}

            Question Content:
            {content}

            Programming Language:
            {language}

            User Request:
            {message}
            """;

    @Resource
    private UserService userService;

    @Resource
    private QuestionService questionService;

    @Resource
    private ContestService contestService;

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
    private AiProperties aiProperties;

    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Resource
    private AiDatabaseChatMemory aiDatabaseChatMemory;

    @Resource
    private AgentToolsManager agentToolsManager;

    /**
     * SSE 流式响应使用独立线程池异步执行，避免阻塞请求线程。
     */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    @Override
    /**
     * 获取当前题目的 AI 会话。
     * 如果会话不存在则自动创建，并回填历史消息给前端。
     */
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
                .map(this::toMessageVO)
                .collect(Collectors.toList());
        AiChatSessionVO sessionVO = new AiChatSessionVO();
        sessionVO.setSessionId(session.getId());
        sessionVO.setStatus(session.getStatus());
        sessionVO.setMode(session.getMode());
        sessionVO.setEnabled(StringUtils.isBlank(disableReason));
        sessionVO.setDisableReason(disableReason);
        sessionVO.setMessageList(messages);
        return sessionVO;
    }

    @Override
    /**
     * 清空指定题目下的聊天记录，但保留会话本身，便于前端继续复用 sessionId。
     */
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
        /**
         * 清除会话消息后，将当前会话重置一个新的上下文（当前用户），复用 session id
         */
        session.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
        session.setDisableReason(null);
        session.setLastMessageTime(new Date());
        session.setExpireTime(buildExpireTime());
        session.setMode(AiChatModeEnum.NORMAL.getValue());
        aiChatSessionMapper.updateById(session);
        return true;
    }

    @Override
    /**
     * 非流式对话入口。
     */
    public AiChatMessageVO chat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request) {
        return doChat(aiChatSendRequest, request, null);
    }

    @Override
    /**
     * 流式对话入口。
     * 内部仍然复用统一的 doChat 逻辑，只是在结果返回阶段拆成 SSE 事件。
     */
    public SseEmitter streamChat(AiChatSendRequest aiChatSendRequest, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(180000L);
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

    @Override
    /**
     * 定时归档（清理）长时间未使用的会话（状态设置为归档，而非 active）。
     */
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

    /**
     * 单轮对话主流程：
     * 1. 校验题目、会话和风控状态
     * 2. 调用 Spring AI 生成回复
     * 3. 持久化用户消息和助手消息
     * 4. 按需推送 SSE 事件
     */
    private AiChatMessageVO doChat(AiChatSendRequest requestBody, HttpServletRequest request, SseEmitter emitter) {
        checkAiEnabled();
        validateSendRequest(requestBody);
        AiChatModeEnum modeEnum = AiChatModeEnum.fromValue(requestBody.getMode());
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
        if (containsPromptInjection(userMessage)) {
            saveViolation(loginUser.getId(), session.getId(), null, "prompt_injection", userMessage);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "检测到疑似提示词注入行为，请求已被拦截");
        }

        // 先调用模型，生成成功后再落库当前轮用户消息，避免 memory advisor 重复注入本轮输入。
        String systemPrompt = getSystemPrompt(modeEnum);
        List<AiToolEventVO> toolEvents = new ArrayList<>();

        String assistantContent = generateAssistantContent(systemPrompt, session, loginUser, question,
                contestId, requestBody, emitter, toolEvents, modeEnum);
        // AI 调用失败
//        if(AI_CALL_FAILED_RESPONSE.equals(assistantContent)) {
//            saveViolation(loginUser.getId(), session.getId(), null, "call AI failed", userMessage);
//        }

        saveMessage(session.getId(), ROLE_USER, modeEnum.getValue(), userMessage, null);
        AiChatMessage assistantMessage = saveMessage(session.getId(), ROLE_ASSISTANT, modeEnum.getValue(),
                assistantContent, JSONUtil.toJsonStr(toolEvents));

        session.setStatus(AiSessionStatusEnum.ACTIVE.getValue());
        session.setDisableReason(null);
        session.setMode(modeEnum.getValue());
        session.setLastMessageTime(new Date());
        session.setExpireTime(buildExpireTime());
        aiChatSessionMapper.updateById(session);

        if (emitter != null) {
            sendEvent(emitter, EVENT_META, buildMetaPayload(session, assistantMessage));
            streamText(emitter, assistantContent);
            sendEvent(emitter, EVENT_DONE, buildDonePayload(assistantMessage, assistantContent));
        }

        return toMessageVO(assistantMessage);
    }

    /**
     * 统一的消息落库方法，保证用户消息和助手消息的结构一致。
     */
    private AiChatMessage saveMessage(Long sessionId, String role, String mode, String content, String toolCalls) {
        AiChatMessage message = new AiChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setMode(mode);
        message.setContent(content);
        message.setToolCalls(toolCalls);
        message.setViolation(0);
        aiChatMessageMapper.insert(message);
        return message;
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

    /**
     * 标准化比赛 id，非比赛场景统一使用 0。
     */
    private Long normalizeContestId(Long contestId) {
        return contestId == null ? 0L : contestId;
    }

    /**
     * 查询当前用户在“题目 + 比赛”维度下最近的会话。
     */
    private AiChatSession findSession(Long userId, Long questionId, Long contestId) {
        LambdaQueryWrapper<AiChatSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatSession::getUserId, userId);
        queryWrapper.eq(AiChatSession::getQuestionId, questionId);
        queryWrapper.eq(AiChatSession::getContestId, contestId);
        queryWrapper.orderByDesc(AiChatSession::getId);
        queryWrapper.last("limit 1");
        return aiChatSessionMapper.selectOne(queryWrapper);
    }

    /**
     * 获取或创建会话。
     */
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

    /**
     * 根据保留策略计算会话过期时间。
     */
    private Date buildExpireTime() {
        return DateUtil.offsetDay(new Date(), aiProperties.getRetentionDays());
    }

    /**
     * 如果会话已过期，则自动将其状态调整为已归档。
     */
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

    /**
     * 根据禁用规则刷新会话状态。
     */
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

    /**
     * 查询指定会话的全部消息，按时间正序返回，主要用于前端初始化。
     */
    private List<AiChatMessage> listSessionMessages(Long sessionId) {
        LambdaQueryWrapper<AiChatMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiChatMessage::getSessionId, sessionId);
        queryWrapper.orderByAsc(AiChatMessage::getId);
        return aiChatMessageMapper.selectList(queryWrapper);
    }

    private AiChatMessageVO toMessageVO(AiChatMessage message) {
        AiChatMessageVO vo = new AiChatMessageVO();
        BeanUtils.copyProperties(message, vo);
        return vo;
    }

    /**
     * 检查当前会话是否命中禁用规则。
     * 优先级：比赛 > 题目 > 用户 > 全局。
     */
    private String checkDisableReason(Long userId, Long questionId, Long contestId) {
        if (contestId != null && contestId > 0) {
            Contest contest = contestService.getById(contestId);
            if (contest != null && contest.getEndTime() != null && contest.getEndTime().before(new Date())) {
                return "比赛已结束，无法继续提问";
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

    /**
     * 查询当前生效中的禁用规则。
     */
    private List<AiDisableRule> listActiveDisableRules() {
        Date now = new Date();
        LambdaQueryWrapper<AiDisableRule> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiDisableRule::getEnabled, 1);
        queryWrapper.and(wrapper -> wrapper.isNull(AiDisableRule::getStartTime).or().le(AiDisableRule::getStartTime, now));
        queryWrapper.and(wrapper -> wrapper.isNull(AiDisableRule::getEndTime).or().ge(AiDisableRule::getEndTime, now));
        queryWrapper.orderByDesc(AiDisableRule::getId);
        return aiDisableRuleMapper.selectList(queryWrapper);
    }

    private List<String> listSensitiveWords() {
        LambdaQueryWrapper<AiSensitiveWord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiSensitiveWord::getEnabled, 1);
        List<AiSensitiveWord> words = aiSensitiveWordMapper.selectList(queryWrapper);
        return words.stream()
                .map(AiSensitiveWord::getWord)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * 提示词注入检测。
     * @param text
     * @return
     */
    private boolean containsPromptInjection(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String lowerCase = text.toLowerCase();
        return lowerCase.contains("ignore previous")
                || lowerCase.contains("developer message")
                || lowerCase.contains("系统提示")
                || lowerCase.contains("忽略之前");
    }

    /**
     * 获取系统提示词。
     * 优先使用数据库中当前激活的提示词配置，否则退回默认模板。
     */
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
            return DEFAULT_AGENT_SYSTEM_PROMPT;
        }
        return DEFAULT_NORMAL_SYSTEM_PROMPT;
    }

    /**
     * 对 Spring AI 调用做一层兜底封装。
     */
    private String generateAssistantContent(String systemPrompt, AiChatSession session, User loginUser,
                                            Question question, Long contestId, AiChatSendRequest requestBody,
                                            SseEmitter emitter, List<AiToolEventVO> toolEvents, AiChatModeEnum modeEnum) {
        String content = callSpringAi(systemPrompt, session, loginUser, question, contestId,
                requestBody, emitter, toolEvents, modeEnum);
        if (StringUtils.isBlank(content)) {
            content = buildFallbackAnswer(requestBody.getMessage(), toolEvents);
        }
        return content;
    }

    /**
     * 真正调用 Spring AI 的位置。
     * 普通模式只挂载聊天记忆，Agent 模式会额外挂载工具回调和运行时上下文。
     */
    private String callSpringAi(String systemPrompt, AiChatSession session, User loginUser, Question question,
                                Long contestId, AiChatSendRequest requestBody, SseEmitter emitter,
                                List<AiToolEventVO> toolEvents, AiChatModeEnum modeEnum) {
        try {
            /**
             * 1、每次会话，生成一个ChatClient（用户可能每次选择了不同的模型，所以不能使用单例）
             * 2、会话信息存储到数据库
             * 3、敏感词过滤 SafeGuardAdvisor （拦截弃用时，保存违规记录到数据库）
             * 4、封装用户提示词：除了用户输入的信息，还包括题目信息、编程语言
             * 5、Agent 模式下，注入工具信息
             */
            ChatClient.ChatClientRequestSpec requestSpec = chatClientBuilder.build()
                    .prompt()
                    .advisors(
                            MessageChatMemoryAdvisor.builder(aiDatabaseChatMemory)
                                    .conversationId(String.valueOf(session.getId()))
                                    .build(),
                            buildSafeGuardAdvisor(loginUser, session, requestBody.getMessage())
                    )
                    .system(systemPrompt)
                    .user(user -> user.text(USER_PROMPT_TEMPLATE)
                            .params(buildUserPromptParams(question, requestBody)));
//                    .options(DashScopeChatOptions.builder()
//                            .model("qwen-max")
//                            .build());

            if (AiChatModeEnum.AGENT == modeEnum) {
                // Agent 模式下把项目里的题目、用户、代码等上下文通过 toolContext 透传给工具层。
                ToolCallback[] toolCallbacks = agentToolsManager.getEnabledToolCallbacks();
                if (toolCallbacks.length > 0) {
                    requestSpec = requestSpec
                            .toolCallbacks(toolCallbacks)
                            .toolContext(buildToolContext(loginUser, question, contestId, requestBody, emitter, toolEvents));
                }
            }

            return requestSpec.call().content();
        } catch (Exception e) {
            log.error("Spring AI call failed", e);
            return null;
        }
    }

    /**
     * 构建SafeGuardAdvisor，执行时保存违规日志
     */
    private SafeGuardAdvisor buildSafeGuardAdvisor(User loginUser, AiChatSession session, String userMessage) {
        List<String> sensitiveWords = listSensitiveWords();
        return new ViolationLoggingSafeGuardAdvisor(sensitiveWords, loginUser, session, userMessage);
    }

    /**
     * 具名 SafeGuardAdvisor，避免匿名类名称为空导致 advisorName 校验失败。
     */
    private class ViolationLoggingSafeGuardAdvisor extends SafeGuardAdvisor {

        private final List<String> sensitiveWords;
        private final User loginUser;
        private final AiChatSession session;
        private final String userMessage;

        private ViolationLoggingSafeGuardAdvisor(List<String> sensitiveWords, User loginUser,
                                                 AiChatSession session, String userMessage) {
            super(sensitiveWords, SAFE_GUARD_BLOCKED_RESPONSE, 0);
            this.sensitiveWords = sensitiveWords;
            this.loginUser = loginUser;
            this.session = session;
            this.userMessage = userMessage;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest advisedRequest, CallAdvisorChain chain) {
            saveSensitiveViolationIfNeeded(advisedRequest, sensitiveWords, loginUser, session, userMessage);
            return super.adviseCall(advisedRequest, chain);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest advisedRequest, StreamAdvisorChain chain) {
            saveSensitiveViolationIfNeeded(advisedRequest, sensitiveWords, loginUser, session, userMessage);
            return super.adviseStream(advisedRequest, chain);
        }
    }

    /**
     * SafeGuardAdvisor 命中敏感词时，同步记录一条违规日志。
     */
    private void saveSensitiveViolationIfNeeded(ChatClientRequest advisedRequest, List<String> sensitiveWords,
                                                User loginUser, AiChatSession session, String userMessage) {
        if (!matchesSensitiveWord(advisedRequest, sensitiveWords)) {
            return;
        }
        String violationContent = StringUtils.defaultIfBlank(extractPromptContent(advisedRequest), userMessage);
        saveViolation(loginUser.getId(), session.getId(), null, "input_sensitive", violationContent);
    }

    /**
     * 复用 SafeGuardAdvisor 的敏感词匹配逻辑，保证业务回调与拦截条件一致。
     */
    private boolean matchesSensitiveWord(ChatClientRequest advisedRequest, List<String> sensitiveWords) {
        if (advisedRequest == null || CollUtil.isEmpty(sensitiveWords)) {
            return false;
        }
        String promptContent = extractPromptContent(advisedRequest);
        if (StringUtils.isBlank(promptContent)) {
            return false;
        }
        return sensitiveWords.stream()
                .filter(StringUtils::isNotBlank)
                .anyMatch(promptContent::contains);
    }

    private String extractPromptContent(ChatClientRequest advisedRequest) {
        if (advisedRequest == null) {
            return null;
        }
        return advisedRequest.prompt().getContents();
    }

    private Map<String, Object> buildUserPromptParams(Question question, AiChatSendRequest requestBody) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("title", StringUtils.defaultString(question.getTitle()));
        params.put("content", StringUtils.defaultString(question.getContent()));
        params.put("language", StringUtils.defaultIfBlank(requestBody.getLanguage(), "unknown"));
//        params.put("latestJudgeResult", StringUtils.defaultIfBlank(requestBody.getLatestJudgeResult(), "N/A"));
//        params.put("userCode", trimUserCode(requestBody.getUserCode()));
        params.put("message", StringUtils.defaultString(requestBody.getMessage()));
        return params;
    }

    /**
     * 控制注入到模型的用户代码长度，避免上下文过大。
     */
    private String trimUserCode(String userCode) {
        if (StringUtils.isBlank(userCode)) {
            return "N/A";
        }
        return StrUtil.sub(userCode, 0, 4000);
    }

    /**
     * 构建工具运行时上下文。
     * Spring AI 只负责调度工具，具体业务对象仍由项目自己传入。
     */
    private Map<String, Object> buildToolContext(User loginUser, Question question, Long contestId,
                                                 AiChatSendRequest requestBody, SseEmitter emitter,
                                                 List<AiToolEventVO> toolEvents) {
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(AgentToolsManager.TOOL_RUNTIME_CONTEXT_KEY,
                new AgentToolsManager.RuntimeContext(loginUser.getId(), question, contestId, requestBody, emitter, toolEvents));
        return toolContext;
    }

    /**
     * AI 调用失败时的降级回复。
     */
    private String buildFallbackAnswer(String userMessage, List<AiToolEventVO> toolEvents) {
        StringBuilder sb = new StringBuilder();
        sb.append(AI_CALL_FAILED_RESPONSE);
        log.info("AI 调用失败，降级回复：{}", sb);
        return sb.toString();
    }

    /**
     * 记录风控违规日志，便于后续审计和排查。
     */
    private void saveViolation(Long userId, Long sessionId, Long messageId, String ruleType, String content) {
        AiViolationLog violationLog = new AiViolationLog();
        violationLog.setUserId(userId);
        violationLog.setSessionId(sessionId);
        violationLog.setMessageId(messageId);
        violationLog.setRuleType(ruleType);
        violationLog.setContentSnippet(StrUtil.sub(content, 0, 500));
        aiViolationLogMapper.insert(violationLog);
    }

    /**
     * 统一发送 SSE 事件。
     */
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

    /**
     * 构建返回给前端的元信息事件。
     */
    private Map<String, Object> buildMetaPayload(AiChatSession session, AiChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", session.getId());
        map.put("messageId", message.getId());
        map.put("mode", message.getMode());
        return map;
    }

    private Map<String, Object> buildDonePayload(AiChatMessage message, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageId", message.getId());
        map.put("mode", message.getMode());
        map.put("content", StringUtils.defaultString(content));
        return map;
    }

    /**
     * 将完整回复按固定块大小拆分成 delta 事件，模拟流式输出体验。
     */
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
