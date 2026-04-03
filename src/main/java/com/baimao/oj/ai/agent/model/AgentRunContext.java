package com.baimao.oj.ai.agent.model;

import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行期上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunContext {

    /**
     * 当前 AI 会话 ID，用于关联对话记忆。
     */
    private Long sessionId;

    /**
     * 当前登录用户。
     */
    private User loginUser;

    /**
     * 当前对话关联的题目。
     */
    private Question question;

    /**
     * 比赛场景下的比赛 ID，非比赛场景可为空。
     */
    private Long contestId;

    /**
     * 用户本次发送的请求体。
     */
    private AiChatSendRequest requestBody;

    /**
     * 外层系统提示词，会透传给 Agent。
     */
    private String baseSystemPrompt;

    /**
     * 模型安全审查 Advisor。
     */
    private SafeGuardAdvisor safeGuardAdvisor;

    /**
     * 用于向前端推送执行过程的 SSE 发射器。
     */
    private SseEmitter emitter;

    /**
     * 本轮执行累计产生的工具事件。
     */
    @Builder.Default
    private List<AiToolEventVO> toolEvents = new ArrayList<>();
}
