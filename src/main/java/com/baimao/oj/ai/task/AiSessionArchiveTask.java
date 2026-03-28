package com.baimao.oj.ai.task;

import com.baimao.oj.ai.service.AiChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 归档过期 AI 会话的定时任务。
 */
@Component
@Slf4j
public class AiSessionArchiveTask {

    @Resource
    private AiChatService aiChatService;

    /**
     * 按 {@code ai.archive-cron} 周期执行：归档（逻辑上的清理）过期的 AI 会话。
     * 比如用户已经30天没打开与某道题的会话，那么这道题的会话将被归档。30天后再次打开的会话就是新的会话了
     */
    @Scheduled(cron = "${ai.archive-cron:0 0/30 * * * ?}")
    public void archiveExpiredSession() {
        try {
            aiChatService.archiveExpiredSessions();
        } catch (Exception e) {
            log.error("archiveExpiredSession failed", e);
        }
    }
}

