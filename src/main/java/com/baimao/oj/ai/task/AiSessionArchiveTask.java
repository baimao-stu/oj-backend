package com.baimao.oj.ai.task;

import com.baimao.oj.ai.service.AiChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task for archiving expired AI chat sessions.
 */
@Component
@Slf4j
public class AiSessionArchiveTask {

    @Resource
    private AiChatService aiChatService;

    /**
     * Run periodically according to {@code ai.archive-cron}.
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
