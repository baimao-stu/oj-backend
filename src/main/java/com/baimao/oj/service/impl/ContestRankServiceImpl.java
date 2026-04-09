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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** 比赛排行榜明细 key 索引（记录该比赛下所有详情缓存的 Key） */
    private static final String DETAIL_INDEX_KEY_PREFIX = "contest:rank:detail:index:";
    /** 单用户排行榜更新锁 */
    private static final String USER_LOCK_KEY_PREFIX = "contest:rank:lock:user:";
    /** 榜单重建锁 */
    private static final String REBUILD_LOCK_KEY_PREFIX = "contest:rank:lock:rebuild:";

    /** 分值基数：保证 AC 数优先级远高于总耗时 */
    private static final long SCORE_BASE = 1_000_000_000_000L;
    /** 榜单缓存 TTL */
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    /** 单用户更新锁 TTL */
    private static final Duration USER_LOCK_TTL = Duration.ofSeconds(10);
    /** 榜单重建锁 TTL */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofMinutes(2);
    /** 锁竞争重试次数 */
    private static final int LOCK_RETRY_TIMES = 20;
    /** 锁竞争重试间隔 */
    private static final long LOCK_RETRY_INTERVAL_MS = 50L;

    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserService userService;

    @Override
    public void updateRankOnJudgeResult(QuestionSubmit questionSubmit) {
        if (questionSubmit == null || questionSubmit.getContestId() == null || questionSubmit.getContestId() <= 0) {
            return;
        }
        Long submitId = questionSubmit.getId();
        if (submitId == null || questionSubmit.getUserId() == null || questionSubmit.getQuestionId() == null) {
            return;
        }
        JudgeInfo currentJudgeInfo = parseJudgeInfo(questionSubmit.getJudgeInfo());
        if (currentJudgeInfo == null) {
            return;
        }

        Long contestId = questionSubmit.getContestId();
        Long userId = questionSubmit.getUserId();
        Long questionId = questionSubmit.getQuestionId();
        String detailKey = buildDetailKey(contestId, userId);
        String rankKey = buildRankKey(contestId);

        try {
            // 榜单重建期间不直接做增量更新，避免新旧数据交叉写入。
            if (!waitForNoRebuild(contestId)) {
                log.warn("skip rank cache update because rebuild lock is busy, contestId={}, submitId={}", contestId, submitId);
                // 重建锁获取失败（已有进程在重建排行榜），删除排行榜缓存
                safeClearContestRankCache(contestId);
                return;
            }

            /** ------- 当前没有在重建榜单，可以更新榜单 --------- */

            // 锁粒度控制在“比赛 + 用户”，既能避免并发覆盖，又不会把整场比赛串行化。
            String lockKey = buildUserLockKey(contestId, userId);   // TODO key 是否应该将 userId 改成 requestId ？
            String lockValue = UUID.randomUUID().toString();
            if (!tryAcquireLock(lockKey, lockValue, USER_LOCK_TTL, LOCK_RETRY_TIMES)) {
                log.warn("acquire rank update lock failed, contestId={}, userId={}, submitId={}", contestId, userId, submitId);
                safeClearContestRankCache(contestId);
                return;
            }

            /** ------- 抢到分布式锁（用户锁）的线程可以更新榜单 --------- */
            try {
                Map<Object, Object> detail = stringRedisTemplate.opsForHash().entries(detailKey);
                Map<Long, JudgeInfo> statusMap = parseStatusMap(detail.get("detail"));
                Map<Long, ContestUserVO.QuestionLastSubmitMeta> submitMetaMap = parseSubmitMetaMap(detail.get("submitMeta"));

                ContestUserVO.QuestionLastSubmitMeta currentMeta = buildSubmitMeta(questionSubmit); // 当前提交
                ContestUserVO.QuestionLastSubmitMeta existingMeta = submitMetaMap.get(questionId);  // 与当前是同一个题目的已有提交
                // 只接受“更新的那次提交”，避免旧提交晚判完把新结果覆盖掉。
                if (!shouldAcceptUpdate(existingMeta, currentMeta)) {
                    log.info("skip stale rank update, contestId={}, userId={}, questionId={}, submitId={}",
                            contestId, userId, questionId, submitId);
                    refreshCacheTtl(contestId, rankKey, detailKey);
                    return;
                }

                /** ------- 当前的提交是更新的一 --------- */
                statusMap.put(questionId, currentJudgeInfo);
                submitMetaMap.put(questionId, currentMeta);

                RankMetrics rankMetrics = calculateRankMetrics(statusMap);
                int acNum = rankMetrics.acNum;
                long totalTime = rankMetrics.totalTime;

                Map<String, String> detailToSave = new HashMap<>();
                detailToSave.put("acNum", String.valueOf(acNum));
                detailToSave.put("totalTime", String.valueOf(totalTime));
                detailToSave.put("detail", toStatusMapJson(statusMap));
                detailToSave.put("submitMeta", toSubmitMetaJson(submitMetaMap));
                detailToSave.put("updatedAt", String.valueOf(System.currentTimeMillis()));
                stringRedisTemplate.opsForHash().putAll(detailKey, detailToSave);

                // 写完明细后再更新总榜分值，保证分页查询拿到的聚合字段和分值尽量一致。
                double score = buildScore(acNum, totalTime);
                stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), score);
                registerDetailKey(contestId, detailKey);
                refreshCacheTtl(contestId, rankKey, detailKey);

                log.info("update rank on judge result, contestId={}, submitId={}, userId={}, questionId={}, score={}",
                        contestId, submitId, userId, questionId, score);
            } finally {
                /** 释放用户锁 */
                releaseLock(lockKey, lockValue);
            }
        } catch (Exception e) {
            log.error("update rank cache failed, contestId={}, submitId={}", contestId, submitId, e);
            safeClearContestRankCache(contestId);
        }
    }

    @Override
    public Page<ContestUserVO> getRankPageFromCache(Long contestId, long current, long size) {
        if (contestId == null || contestId <= 0 || current <= 0 || size <= 0) {
            return null;
        }
        try {
            // 榜单重建期间直接回退数据库，避免读到半成品缓存。
            if (isRebuilding(contestId)) {
                return null;
            }

            long detailQueryStart = System.currentTimeMillis();
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
            double detailQueryCostMs = (System.currentTimeMillis() - detailQueryStart);
            log.info("rank cache detail query cost with pipeline, contestId={}, page={}, size={}, userCount={}, detailQueryCostMs={}",
                    contestId, current, size, userIdList.size(), detailQueryCostMs);

            Page<ContestUserVO> page = new Page<>(current, size, total);
            page.setRecords(recordList);
            return page;
        } catch (Exception e) {
            log.warn("query rank from redis failed, contestId={}, current={}, size={}", contestId, current, size, e);
            return null;
        }
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
            vo.setQuestionSubmitStatus(parseStatusMap(detail.get("detail")));
            recordList.add(vo);
        }
        return recordList;
    }

    @Override
    public void warmupRankCache(Long contestId, List<ContestUserVO> contestUserVOList) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        if (contestUserVOList == null || contestUserVOList.isEmpty()) {
            clearContestRankCache(contestId);
            return;
        }

        String rebuildLockKey = buildRebuildLockKey(contestId);
        String rebuildLockValue = UUID.randomUUID().toString();
        try {
            // 只允许一个线程执行整榜重建，避免多次全量回填互相覆盖。
            if (!tryAcquireLock(rebuildLockKey, rebuildLockValue, REBUILD_LOCK_TTL, 1)) {
                log.info("skip warmup because rebuild lock already exists, contestId={}", contestId);
                return;
            }

            // 先清旧缓存，再按数据库结果整榜回填。
            doClearContestRankCacheInternal(contestId);
            String rankKey = buildRankKey(contestId);
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
                Map<Long, JudgeInfo> statusMap = vo.getQuestionSubmitStatus() == null
                        ? new HashMap<>()
                        : vo.getQuestionSubmitStatus();
                Map<Long, ContestUserVO.QuestionLastSubmitMeta> submitMetaMap = vo.getQuestionLastSubmitMeta() == null
                        ? new HashMap<>()
                        : vo.getQuestionLastSubmitMeta();

                Map<String, String> detailToSave = new HashMap<>();
                detailToSave.put("acNum", String.valueOf(acNum));
                detailToSave.put("totalTime", String.valueOf(totalTime));
                detailToSave.put("detail", toStatusMapJson(statusMap));
                detailToSave.put("submitMeta", toSubmitMetaJson(submitMetaMap));
                detailToSave.put("updatedAt", String.valueOf(System.currentTimeMillis()));
                stringRedisTemplate.opsForHash().putAll(detailKey, detailToSave);
                registerDetailKey(contestId, detailKey);

                double score = buildScore(acNum, totalTime);
                stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(userId), score);
            }

            batchExpire(detailKeySet, CACHE_TTL);
            stringRedisTemplate.expire(rankKey, CACHE_TTL);
            stringRedisTemplate.expire(buildDetailIndexKey(contestId), CACHE_TTL);

            log.info("warmup rank cache, contestId={}, userCount={}, detailKeyCount={}, expireSec={}",
                    contestId, contestUserVOList.size(), detailKeySet.size(), CACHE_TTL.getSeconds());
        } catch (Exception e) {
            log.error("warmup rank cache failed, contestId={}", contestId, e);
            safeClearContestRankCache(contestId);
        } finally {
            releaseLock(rebuildLockKey, rebuildLockValue);
        }
    }

    @Override
    public void clearContestRankCache(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        safeClearContestRankCache(contestId);
    }

    /**
     * 清缓存是兜底动作，异常时只打日志，避免把调用方一起带失败。
     */
    private void safeClearContestRankCache(Long contestId) {
        try {
            doClearContestRankCacheInternal(contestId);
        } catch (Exception e) {
            log.warn("clear contest rank cache failed, contestId={}", contestId, e);
        }
    }

    /**
     * 优先走索引集合删除 detail key，索引缺失时再降级为按前缀扫描。
     */
    private void doClearContestRankCacheInternal(Long contestId) {
        String rankKey = buildRankKey(contestId);
        String detailIndexKey = buildDetailIndexKey(contestId);
        Set<String> keysToDelete = new LinkedHashSet<>();
        keysToDelete.add(rankKey);

        // 获取集合中所有 key
        Set<String> indexedDetailKeys = stringRedisTemplate.opsForSet().members(detailIndexKey);
        if (indexedDetailKeys != null && !indexedDetailKeys.isEmpty()) {
            keysToDelete.addAll(indexedDetailKeys);
        } else {
            keysToDelete.addAll(scanKeysByPrefix(buildDetailKeyPrefix(contestId)));
        }
        keysToDelete.add(detailIndexKey);

        stringRedisTemplate.delete(keysToDelete);
    }

    /**
     * 仅在索引集合丢失时使用扫描兜底，避免对 Redis 做全量 keys 操作。
     */
    private Set<String> scanKeysByPrefix(String prefix) {
        return stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new LinkedHashSet<>();
            ScanOptions scanOptions = ScanOptions.scanOptions().match(prefix + "*").count(1000).build();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.warn("scan redis keys by prefix failed, prefix={}", prefix, e);
            }
            return keys;
        });
    }

    // 先按提交时间比较、再按提交ID比较，同一个题目取最新的一个提交
    private boolean shouldAcceptUpdate(ContestUserVO.QuestionLastSubmitMeta existingMeta,
                                       ContestUserVO.QuestionLastSubmitMeta currentMeta) {
        if (currentMeta == null) {
            return false;
        }
        if (existingMeta == null) {
            return true;
        }
        // 先比提交时间，再用 submitId 打破同毫秒并发提交的平局。
        long existingTime = existingMeta.getSubmitTime() == null ? 0L : existingMeta.getSubmitTime();
        long currentTime = currentMeta.getSubmitTime() == null ? 0L : currentMeta.getSubmitTime();
        if (currentTime != existingTime) {
            return currentTime > existingTime;
        }
        long existingSubmitId = existingMeta.getSubmitId() == null ? 0L : existingMeta.getSubmitId();
        long currentSubmitId = currentMeta.getSubmitId() == null ? 0L : currentMeta.getSubmitId();
        return currentSubmitId > existingSubmitId;
    }

    private ContestUserVO.QuestionLastSubmitMeta buildSubmitMeta(QuestionSubmit questionSubmit) {
        ContestUserVO.QuestionLastSubmitMeta meta = new ContestUserVO.QuestionLastSubmitMeta();
        meta.setSubmitId(questionSubmit.getId());
        Date createTime = questionSubmit.getCreateTime();
        meta.setSubmitTime(createTime == null ? 0L : createTime.getTime());
        return meta;
    }

    /**
     * submitMeta 用来记录每题最后一次提交的先后关系，避免仅靠 judgeInfo 无法比较新旧。
     */
    private Map<Long, ContestUserVO.QuestionLastSubmitMeta> parseSubmitMetaMap(Object submitMetaObj) {
        if (submitMetaObj == null) {
            return new HashMap<>();
        }
        String json = String.valueOf(submitMetaObj);
        if (StringUtils.isBlank(json) || "null".equalsIgnoreCase(json)) {
            return new HashMap<>();
        }
        try {
            JSONObject jsonObject = JSONUtil.parseObj(json);
            Map<Long, ContestUserVO.QuestionLastSubmitMeta> result = new HashMap<>();
            for (String key : jsonObject.keySet()) {
                Object node = jsonObject.get(key);
                if (node == null) {
                    continue;
                }
                ContestUserVO.QuestionLastSubmitMeta meta =
                        JSONUtil.toBean(JSONUtil.toJsonStr(node), ContestUserVO.QuestionLastSubmitMeta.class);
                result.put(Long.valueOf(key), meta);
            }
            return result;
        } catch (Exception e) {
            log.warn("parse submitMeta failed: {}", json);
            return new HashMap<>();
        }
    }

    private String toSubmitMetaJson(Map<Long, ContestUserVO.QuestionLastSubmitMeta> submitMetaMap) {
        if (submitMetaMap == null || submitMetaMap.isEmpty()) {
            return "{}";
        }
        Map<String, ContestUserVO.QuestionLastSubmitMeta> data = new HashMap<>();
        for (Map.Entry<Long, ContestUserVO.QuestionLastSubmitMeta> entry : submitMetaMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                data.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return JSONUtil.toJsonStr(data);
    }

    private void registerDetailKey(Long contestId, String detailKey) {
        String detailIndexKey = buildDetailIndexKey(contestId);
        stringRedisTemplate.opsForSet().add(detailIndexKey, detailKey);
        stringRedisTemplate.expire(detailIndexKey, CACHE_TTL);
    }

    /**
     * 增量更新命中后顺手续期，避免只有预热链路设置 TTL 导致部分 key 长期残留。
     */
    private void refreshCacheTtl(Long contestId, String rankKey, String detailKey) {
        stringRedisTemplate.expire(rankKey, CACHE_TTL);
        stringRedisTemplate.expire(detailKey, CACHE_TTL);
        stringRedisTemplate.expire(buildDetailIndexKey(contestId), CACHE_TTL);
    }

    /**
     * 榜单重建时短暂等待，减少“重建线程刚起，增量线程立刻清缓存”的互相干扰。
     * 检查锁是否存在，存在则现成阻塞等待
     */
    private boolean waitForNoRebuild(Long contestId) {
        String rebuildLockKey = buildRebuildLockKey(contestId);
        for (int i = 0; i < LOCK_RETRY_TIMES; i++) {
            Boolean exists = stringRedisTemplate.hasKey(rebuildLockKey);
            if (!Boolean.TRUE.equals(exists)) {
                return true;
            }
            sleepQuietly(LOCK_RETRY_INTERVAL_MS);
        }
        return false;
    }

    private boolean isRebuilding(Long contestId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildRebuildLockKey(contestId)));
    }

    /**
     * 使用 setIfAbsent 实现简单分布式锁，失败时短暂重试几次。（SETNX）
     */
    private boolean tryAcquireLock(String lockKey, String lockValue, Duration ttl, int attempts) {
        int retryTimes = Math.max(attempts, 1);
        // 重试拿锁
        for (int i = 0; i < retryTimes; i++) {
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, ttl);
            if (Boolean.TRUE.equals(locked)) {
                return true;
            }
            // 阻塞等待后再次拿锁
            if (i < retryTimes - 1) {
                sleepQuietly(LOCK_RETRY_INTERVAL_MS);
            }
        }
        return false;
    }

    /**
     * 只删除自己持有的锁，避免误删其他线程刚续上的锁。
     */
    private void releaseLock(String lockKey, String lockValue) {
        if (StringUtils.isBlank(lockKey) || StringUtils.isBlank(lockValue)) {
            return;
        }
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("release redis lock failed, lockKey={}", lockKey, e);
        }
    }

    /**
     * 锁竞争等待是短暂阻塞，保留中断标记避免吞掉线程中断语义。
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 批量设置过期时间时走 pipeline，减少多 key 场景下的网络往返。
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
        return buildDetailKeyPrefix(contestId) + userId;
    }

    private String buildDetailKeyPrefix(Long contestId) {
        return DETAIL_KEY_PREFIX + contestId + ":";
    }

    private String buildDetailIndexKey(Long contestId) {
        return DETAIL_INDEX_KEY_PREFIX + contestId;
    }

    private String buildUserLockKey(Long contestId, Long userId) {
        return USER_LOCK_KEY_PREFIX + contestId + ":" + userId;
    }

    private String buildRebuildLockKey(Long contestId) {
        return REBUILD_LOCK_KEY_PREFIX + contestId;
    }
}
