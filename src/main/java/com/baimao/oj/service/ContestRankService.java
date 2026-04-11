package com.baimao.oj.service;

import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 比赛排行榜服务。
 *
 * 新设计中：
 * 1. MySQL 快照表负责保存排行榜真源数据。
 * 2. Redis 只缓存分页结果，不再做实时榜增量计算。
 */
public interface ContestRankService {

    /**
     * 分页查询比赛排行榜。
     */
    Page<ContestUserVO> listContestRankPage(Long contestId, long current, long size);

    /**
     * 在报名成功后初始化该用户的排行榜快照。
     */
    void initUserRankSnapshot(Long contestId, Long userId);

    /**
     * 在判题完成后重算该用户的排行榜快照。
     */
    void refreshUserRankSnapshot(Long contestId, Long userId);

    /**
     * 清理指定比赛的 Redis 分页缓存。
     */
    void clearContestRankCache(Long contestId);

    /**
     * 删除指定比赛的排行榜数据。
     *
     * 会同时删除 MySQL 快照和 Redis 缓存。
     */
    void removeContestRankData(Long contestId);
}
