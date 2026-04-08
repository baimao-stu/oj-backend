package com.baimao.oj.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baimao.oj.model.vo.UserVO;
import com.baimao.oj.service.ContestRankService;
import com.baimao.oj.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的竞赛实时排行榜服务。
 *
 * 设计目标：
 * 1. 写路径（判题完成后）只做“增量更新”，避免每次查榜全量扫描提交记录。
 * 2. 读路径（排行榜分页）优先读 Redis，满足高并发下的低延迟查询。
 * 3. 保留缓存预热能力，支持从数据库计算结果回填，便于冷启动和故障恢复。
 *
 * 关键数据结构：
 * 1. ZSET：总榜，member=userId，score=编码后的排序分值。
 * 2. HASH：用户聚合明细（acNum、totalTime、detail/statusMap）。
 *    其中 detail/statusMap 为“题号 -> 最后一次提交结果”的 JSON。
 * 3. STRING：提交事件去重标记，避免重复消费导致分数重复累计。
 */
@Service
@Slf4j
public class ContestRankServiceImpl implements ContestRankService {

    /** 排行总榜 ZSET，member=userId，score=编码后的排名分 */
    private static final String RANK_KEY_PREFIX = "contest:rank:";
    /** 用户在某场比赛下的聚合明细 HASH（acNum、totalTime、detail/statusMap） */
    private static final String DETAIL_KEY_PREFIX = "contest:rank:detail:";
    /** 判题事件去重 key，防止重复消费导致排名重复累计 */
    private static final String DEDUP_KEY_PREFIX = "contest:rank:event:dedup:";

    /** 分值基数：保证 AC 题数优先级远高于总耗时 */
    private static final long SCORE_BASE = 1_000_000_000_000L;
    /** 去重标记 TTL */
    private static final Duration DEDUP_TTL = Duration.ofDays(1);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserService userService;

    @Override
    public void updateRankOnJudgeResult(QuestionSubmit questionSubmit) {
        // 非竞赛提交不参与排行榜：只处理 contestId > 0 的提交
        if (questionSubmit == null || questionSubmit.getContestId() == null || questionSubmit.getContestId() <= 0) {
            return;
        }
        Long submitId = questionSubmit.getId();
        if (submitId == null) {
            return;
        }

        // 幂等去重：同一 submitId 仅允许处理一次，防止重试/重复消息导致重复累加
//        String dedupKey = buildDedupKey(submitId);
//        Boolean firstConsume = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
//        if (!Boolean.TRUE.equals(firstConsume)) {
//            return;
//        }

        Long contestId = questionSubmit.getContestId();
        Long userId = questionSubmit.getUserId();
        Long questionId = questionSubmit.getQuestionId();
        JudgeInfo currentJudgeInfo = parseJudgeInfo(questionSubmit.getJudgeInfo());
        if (currentJudgeInfo == null) {
            return;
        }

        // detailKey：用户在比赛维度的聚合结果（包含每题最后一次提交状态）
        String detailKey = buildDetailKey(contestId, userId);

        // 读取当前聚合状态；同一题目直接覆盖为最后一次提交结果
        Map<Object, Object> detail = stringRedisTemplate.opsForHash().entries(detailKey);
        Object detailJson = detail.get("detail");
        Map<Long, JudgeInfo> statusMap = parseStatusMap(detailJson);
        /** 同一道题目的详细信息直接覆盖 */
        statusMap.put(questionId, currentJudgeInfo);
        /** 用户的 ac 数、总时长重新计算 */
        RankMetrics rankMetrics = calculateRankMetrics(statusMap);
        int acNum = rankMetrics.acNum;
        long totalTime = rankMetrics.totalTime;

        // 回写用户聚合明细（比赛维度）
        Map<String, String> detailToSave = new HashMap<>();
        detailToSave.put("acNum", String.valueOf(acNum));
        detailToSave.put("totalTime", String.valueOf(totalTime));
        String statusJson = toStatusMapJson(statusMap);
        detailToSave.put("detail", statusJson);
        detailToSave.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll(detailKey, detailToSave);

        // 更新总榜分值（AC 数优先，总耗时次优）
        double score = buildScore(acNum, totalTime);
        stringRedisTemplate.opsForZSet().add(buildRankKey(contestId), String.valueOf(userId), score);
        log.info("update rank on judge result, contestId={}, submitId={}, userId={}, questionId={}, score={}",
                contestId, submitId, userId, questionId, score);
    }

    @Override
    public Page<ContestUserVO> getRankPageFromCache(Long contestId, long current, long size) {
        long detailQueryStart = System.currentTimeMillis();

        // 参数非法直接视为未命中，由上层走 DB 回退
        if (contestId == null || contestId <= 0 || current <= 0 || size <= 0) {
            return null;
        }
        String rankKey = buildRankKey(contestId);

        // zCard 为空或为 0，说明当前比赛榜单缓存尚未建立
        Long total = stringRedisTemplate.opsForZSet().zCard(rankKey);
        if (total == null || total <= 0) {
            return null;
        }

        // 计算分页区间，当前页从 1 开始
        long start = (current - 1) * size;
        long end = start + size - 1;
        // 反向读取：score 越大排名越靠前
        Set<String> userIdSet = stringRedisTemplate.opsForZSet().reverseRange(rankKey, start, end);
        if (userIdSet == null || userIdSet.isEmpty()) {
            Page<ContestUserVO> emptyPage = new Page<>(current, size, total);
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }

        List<Long> userIdList = userIdSet.stream().map(Long::valueOf).collect(Collectors.toList());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO, (a, b) -> a));



        // Pipeline 批量获取用户做题详情
        List<ContestUserVO> recordList = getContestUserDetails(contestId, userIdList, userVOMap, true);
        // 非 Pipeline
//         List<ContestUserVO> recordList = getContestUserDetails(contestId, userIdList, userVOMap, false);

        double detailQueryCostMs = (System.currentTimeMillis() - detailQueryStart);
        log.info("rank cache detail query cost with pipline, contestId={}, page={}, size={}, userCount={}, detailQueryCostMs={}",
                contestId, current, size, userIdList.size(), detailQueryCostMs);

        Page<ContestUserVO> page = new Page<>(current, size, total);
        page.setRecords(recordList);

        return page;
    }

    private @NotNull List<ContestUserVO> getContestUserDetails(Long contestId, List<Long> userIdList, Map<Long, UserVO> userVOMap, boolean pipline) {
        List<Object> detailResultList = null;

        if(pipline) {
            detailResultList = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    for (Long userId : userIdList) {
                        operations.opsForHash().entries(buildDetailKey(contestId, userId));
                    }
                    return null;
                }
            });
        }


        List<ContestUserVO> recordList = new ArrayList<>();
        for (int i = 0; i < userIdList.size(); i++) {
            Long userId = userIdList.get(i);
            Map<Object, Object> detail = null;
            if(pipline) {
                // 使用Pipline
                detail = Collections.emptyMap();
                if (detailResultList != null && i < detailResultList.size() && detailResultList.get(i) instanceof Map) {
                    //noinspection unchecked
                    detail = (Map<Object, Object>) detailResultList.get(i);
                }
            }else {
                // 不使用Pipline
                 String detailKey = buildDetailKey(contestId, userId);
                 detail = stringRedisTemplate.opsForHash().entries(detailKey);
            }

            ContestUserVO vo = new ContestUserVO();
            vo.setUserVO(userVOMap.get(userId));
            vo.setAcNum(parseInt(detail.get("acNum"), 0));
            vo.setAllTime(parseLong(detail.get("totalTime"), 0L));
            Object detailJson = detail.get("detail");
            vo.setQuestionSubmitStatus(parseStatusMap(detailJson));
            recordList.add(vo);
        }
        return recordList;
    }

    @Override
    public void warmupRankCache(Long contestId, List<ContestUserVO> contestUserVOList) {
        // 预热采用“全量重建”策略，避免旧缓存残留
        if (contestId == null || contestId <= 0 || contestUserVOList == null || contestUserVOList.isEmpty()) {
            return;
        }
        String rankKey = buildRankKey(contestId);

        // 删除旧总榜，保证重建后的数据一致性
        stringRedisTemplate.delete(rankKey);

        Set<String> detailKeySet = new LinkedHashSet<>();
        for (ContestUserVO vo : contestUserVOList) {
            if (vo == null || vo.getUserVO() == null || vo.getUserVO().getId() == null) {
                continue;
            }
            Long userId = vo.getUserVO().getId();
            String detailKey = buildDetailKey(contestId, userId);
            detailKeySet.add(detailKey);

            int acNum = vo.getAcNum() == null ? 0 : vo.getAcNum();
            long totalTime = vo.getAllTime() == null ? 0L : vo.getAllTime();
            // 用户这场比赛中每道题的提交情况
            Map<Long, JudgeInfo> statusMap = vo.getQuestionSubmitStatus() == null ? new HashMap<>() : vo.getQuestionSubmitStatus();

            Map<String, String> detailToSave = new HashMap<>();
            detailToSave.put("acNum", String.valueOf(acNum));
            detailToSave.put("totalTime", String.valueOf(totalTime));
            String statusJson = toStatusMapJson(statusMap);
            detailToSave.put("detail", statusJson);
//            detailToSave.put("statusMap", statusJson);
            detailToSave.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            stringRedisTemplate.opsForHash().putAll(detailKey, detailToSave);

            // 排行分数
            double score = buildScore(acNum, totalTime);
            // key val score
            stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), score);

        }

        batchExpire(detailKeySet, Duration.ofHours(24));
        // 排行主键也设置过期，降低脏数据长期残留风险
        stringRedisTemplate.expire(rankKey, Duration.ofHours(24));

        log.info("warmup rank cache, contestId={}, userCount={}, detailKeyCount={}, expireSec={}",
                contestId, contestUserVOList.size(), detailKeySet.size(), Duration.ofHours(24).getSeconds());
    }

    /**
     * 使用 pipeline 批量设置过期时间，减少网络往返开销。
     */
    private void batchExpire(Set<String> keys, Duration ttl) {
        if (keys == null || keys.isEmpty() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        long ttlSeconds = ttl.getSeconds();
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                if (StringUtils.isNotBlank(key)) {
                    connection.expire(key.getBytes(StandardCharsets.UTF_8), ttlSeconds);
                }
            }
            return null;
        });
    }

    /**
     * 解析判题信息 JSON。
     * 判题结果异常时返回 null，避免脏数据污染排行榜。
     */
    private JudgeInfo parseJudgeInfo(String judgeInfoStr) {
        if (StringUtils.isBlank(judgeInfoStr)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoStr, JudgeInfo.class);
        } catch (Exception e) {
            log.warn("parse judge info failed: {}", judgeInfoStr);
            return null;
        }
    }

    /**
     * 将 statusMap JSON 转为内存结构。
     * JSON 格式：{"questionId": {judgeInfo...}, ...}
     */
    private Map<Long, JudgeInfo> parseStatusMap(Object statusMapObj) {
        if (statusMapObj == null) {
            return new HashMap<>();
        }
        String json = String.valueOf(statusMapObj);
        if (StringUtils.isBlank(json) || "null".equalsIgnoreCase(json)) {
            return new HashMap<>();
        }
        try {
            JSONObject jsonObject = JSONUtil.parseObj(json);
            Map<Long, JudgeInfo> result = new HashMap<>();
            for (String key : jsonObject.keySet()) {
                Object node = jsonObject.get(key);
                if (node == null) {
                    continue;
                }
                JudgeInfo judgeInfo = JSONUtil.toBean(JSONUtil.toJsonStr(node), JudgeInfo.class);
                result.put(Long.valueOf(key), judgeInfo);
            }
            return result;
        } catch (Exception e) {
            log.warn("parse statusMap failed: {}", json);
            return new HashMap<>();
        }
    }

    /**
     * 将题目状态 Map 序列化成 JSON，写入 detail hash。
     */
    private String toStatusMapJson(Map<Long, JudgeInfo> statusMap) {
        if (statusMap == null || statusMap.isEmpty()) {
            return "{}";
        }
        Map<String, JudgeInfo> data = new HashMap<>();
        for (Map.Entry<Long, JudgeInfo> entry : statusMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                data.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return JSONUtil.toJsonStr(data);
    }

    /**
     * 根据题目维度的最后一次提交结果重算排名指标。
     */
    private RankMetrics calculateRankMetrics(Map<Long, JudgeInfo> statusMap) {
        if (statusMap == null || statusMap.isEmpty()) {
            return new RankMetrics(0, 0L);
        }
        int acNum = 0;
        long totalTime = 0L;
        for (JudgeInfo judgeInfo : statusMap.values()) {
            if (judgeInfo == null) {
                continue;
            }
            if ("Accepted".equalsIgnoreCase(judgeInfo.getMessage())) {
                acNum += 1;
                long time = judgeInfo.getTime() == null ? 0L : Math.max(judgeInfo.getTime(), 0L);
                totalTime += time;
            }
        }
        return new RankMetrics(acNum, totalTime);
    }

    private static class RankMetrics {
        private final int acNum;
        private final long totalTime;

        private RankMetrics(int acNum, long totalTime) {
            this.acNum = acNum;
            this.totalTime = totalTime;
        }
    }

    /** 容错解析 int，异常时使用默认值。 */
    private int parseInt(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 容错解析 long，异常时使用默认值。 */
    private long parseLong(Object value, long defaultValue) {
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double buildScore(int acNum, long totalTime) {
        // 排序编码：先比较 AC 数，再比较总耗时
        // 其中 SCORE_BASE 保证 AC 数差 1 的优先级远高于耗时差
        return (double) acNum * SCORE_BASE - (double) totalTime;
    }

    private String buildRankKey(Long contestId) {
        return RANK_KEY_PREFIX + contestId;
    }

    private String buildDetailKey(Long contestId, Long userId) {
        return DETAIL_KEY_PREFIX + contestId + ":" + userId;
    }

    private String buildDedupKey(Long submitId) {
        return DEDUP_KEY_PREFIX + submitId;
    }
}
