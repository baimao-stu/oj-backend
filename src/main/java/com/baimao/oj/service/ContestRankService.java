package com.baimao.oj.service;

import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 竞赛实时排行榜服务
 */
public interface ContestRankService {

    /**
     * 判题完成后，增量更新比赛排行榜缓存
     */
    void updateRankOnJudgeResult(QuestionSubmit questionSubmit);

    /**
     * 从 Redis 读取排行榜分页，未命中时返回 null
     */
    Page<ContestUserVO> getRankPageFromCache(Long contestId, long current, long size);

    /**
     * 使用数据库计算结果回填排行榜缓存
     */
    void warmupRankCache(Long contestId, List<ContestUserVO> contestUserVOList);

    /**
     * 清理指定比赛的排行榜缓存
     */
    void clearContestRankCache(Long contestId);
}
