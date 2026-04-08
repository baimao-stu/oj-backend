package com.baimao.oj.service;

import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 竞赛实时排行榜服务
 *
 * 说明：
 * 1. 写路径由判题完成后触发，进行增量更新
 * 2. 读路径优先读取 Redis，未命中时由上层回退数据库计算
 * 3. 支持把数据库结果回填到 Redis，减少冷启动抖动
 */
public interface ContestRankService {

    /**
     * 判题完成后，增量更新比赛排行榜缓存
     *
     * @param questionSubmit 判题后的提交记录（需包含 contestId、userId、questionId、judgeInfo）
     */
    void updateRankOnJudgeResult(QuestionSubmit questionSubmit);

    /**
     * 从 Redis 读取排行榜分页（缓存未命中返回 null）
     *
     * @param contestId 比赛 id
     * @param current 当前页（从 1 开始）
     * @param size 每页大小
     */
    Page<ContestUserVO> getRankPageFromCache(Long contestId, long current, long size);

    /**
     * 使用数据库计算结果回填排行榜缓存
     *
     * @param contestId 比赛 id
     * @param contestUserVOList 数据库全量计算后的榜单结果（建议已完成排序）
     */
    void warmupRankCache(Long contestId, List<ContestUserVO> contestUserVOList);
}
