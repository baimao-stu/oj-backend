package com.baimao.oj.service;

import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 比赛排行榜服务。
 *
 * 设计约束：
 * 1. Redis ZSet 是实时排名真源。
 * 2. Redis Hash 保存用户榜单详情。
 * 3. MySQL 快照表异步持久化，用于恢复与审计。
 */
public interface ContestRankService {

    /**
     * 分页查询比赛排行榜。
     */
    Page<ContestUserVO> listContestRankPage(Long contestId, long current, long size);

    /**
     * 在报名成功后初始化该用户的排行榜数据。
     */
    void initUserRankSnapshot(Long contestId, Long userId);

    /**
     * 在判题完成后增量刷新该用户的排行榜数据。
     */
    void refreshUserRankSnapshot(QuestionSubmit questionSubmit);

    /**
     * 清理指定比赛的 Redis 排行榜数据。
     */
    void clearContestRankCache(Long contestId);

    /**
     * 删除指定比赛的排行榜数据。
     */
    void removeContestRankData(Long contestId);
}
