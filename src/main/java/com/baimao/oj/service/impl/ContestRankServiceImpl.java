package com.baimao.oj.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.mapper.ContestRankSnapshotMapper;
import com.baimao.oj.model.entity.ContestRankSnapshot;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baimao.oj.model.vo.UserVO;
import com.baimao.oj.service.ContestRankService;
import com.baimao.oj.service.QuestionSubmitService;
import com.baimao.oj.service.RegistrationsService;
import com.baimao.oj.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 比赛排行榜服务实现。
 *
 * 重构后的设计原则：
 * 1. MySQL 快照表负责保存排行榜真源，所有排行计算结果都先落库。
 * 2. Redis 只缓存分页查询结果，写入时只做缓存失效，不再维护复杂的实时榜结构。
 * 3. 当快照缺失或数量与报名人数不一致时，按比赛维度做一次全量重建。
 */
@Service
@Slf4j
public class ContestRankServiceImpl implements ContestRankService {

    /**
     * 分页缓存 key，格式：contest:rank:page:{contestId}:{current}:{size}
     */
    private static final String PAGE_CACHE_KEY_PREFIX = "contest:rank:page:";

    /**
     * 每场比赛对应的分页缓存索引，用于批量删除缓存。
     */
    private static final String PAGE_CACHE_INDEX_KEY_PREFIX = "contest:rank:page:index:";

    /**
     * 排行榜分页缓存过期时间。
     */
    private static final Duration PAGE_CACHE_TTL = Duration.ofMinutes(10);

    @Resource
    private ContestRankSnapshotMapper contestRankSnapshotMapper;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private RegistrationsService registrationsService;

    @Resource
    private UserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Page<ContestUserVO> listContestRankPage(Long contestId, long current, long size) {
        if (contestId == null || contestId <= 0 || current <= 0 || size <= 0) {
            return new Page<>(current, size, 0);
        }

        Page<ContestUserVO> cachePage = getRankPageFromCache(contestId, current, size);
        if (cachePage != null) {
            return cachePage;
        }

        long registrationCount = registrationsService.getRegistrationCountByContestId(contestId);
        if (registrationCount <= 0) {
            Page<ContestUserVO> emptyPage = new Page<>(current, size, 0);
            emptyPage.setRecords(Collections.emptyList());
            cacheRankPage(contestId, emptyPage);
            return emptyPage;
        }

        ensureSnapshotReady(contestId, registrationCount);

        Page<ContestRankSnapshot> snapshotPage = contestRankSnapshotMapper.selectPage(
                new Page<>(current, size),
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
                        // 排序规则与旧逻辑保持一致：先比通过题数，再比总耗时，最后按用户 id 稳定排序。
                        // TODO 建立联合索引（contestId, acceptedNum, totalTime）优化查询性能
                        .orderByDesc(ContestRankSnapshot::getAcceptedNum)
                        .orderByAsc(ContestRankSnapshot::getTotalTime)
                        .orderByAsc(ContestRankSnapshot::getUserId)
        );

        List<ContestRankSnapshot> snapshotList = snapshotPage.getRecords();
        List<Long> userIdList = snapshotList.stream()
                .map(ContestRankSnapshot::getUserId)
                .filter(id -> id != null && id > 0)
                .toList();
        Map<Long, UserVO> userVOMap = userService.listByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO, (left, right) -> left));

        List<ContestUserVO> recordList = snapshotList.stream()
                .map(snapshot -> toContestUserVO(snapshot, userVOMap.get(snapshot.getUserId())))
                .toList();

        Page<ContestUserVO> resultPage = new Page<>(current, size, snapshotPage.getTotal());
        resultPage.setRecords(recordList);
        cacheRankPage(contestId, resultPage);
        return resultPage;
    }

    @Override
    public void initUserRankSnapshot(Long contestId, Long userId) {
        if (!isValidContestUser(contestId, userId)) {
            return;
        }
        if (!isRegistered(contestId, userId)) {
            return;
        }
        upsertSnapshot(buildSnapshot(contestId, userId, Collections.emptyMap()));
        clearContestRankCache(contestId);
    }

    @Override
    public void refreshUserRankSnapshot(Long contestId, Long userId) {
        if (!isValidContestUser(contestId, userId)) {
            return;
        }
        if (!isRegistered(contestId, userId)) {
            return;
        }

        Map<Long, JudgeInfo> latestQuestionStatus = loadLatestQuestionStatus(contestId, userId);
        upsertSnapshot(buildSnapshot(contestId, userId, latestQuestionStatus));
        clearContestRankCache(contestId);
    }

    @Override
    public void clearContestRankCache(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        try {
            String indexKey = buildPageIndexKey(contestId);
            Set<String> keysToDelete = new LinkedHashSet<>();
            Set<String> pageKeys = stringRedisTemplate.opsForSet().members(indexKey);
            if (pageKeys != null && !pageKeys.isEmpty()) {
                keysToDelete.addAll(pageKeys);
            }
            keysToDelete.add(indexKey);
            if (!keysToDelete.isEmpty()) {
                stringRedisTemplate.delete(keysToDelete);
            }
        } catch (Exception e) {
            log.warn("clear contest rank cache failed, contestId={}", contestId, e);
        }
    }

    @Override
    public void removeContestRankData(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        contestRankSnapshotMapper.delete(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
        );
        clearContestRankCache(contestId);
    }

    /**
     * 当快照数量与报名人数不一致时，说明快照还未初始化或已经失真，需要整场比赛重建。
     */
    private void ensureSnapshotReady(Long contestId, long registrationCount) {
        Long snapshotCount = contestRankSnapshotMapper.selectCount(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
        );
        long safeSnapshotCount = snapshotCount == null ? 0L : snapshotCount;
        if (safeSnapshotCount != registrationCount) {
            rebuildContestSnapshots(contestId);
        }
    }

    /**
     * 以比赛为单位全量重建排行榜快照。
     *
     * 该逻辑只走冷路径，用于首查建快照、修复快照数量不一致等场景。
     */
    private void rebuildContestSnapshots(Long contestId) {
        List<Long> registeredUserIdList = registrationsService.list(
                        Wrappers.<Registrations>lambdaQuery()
                                .eq(Registrations::getContestId, contestId)
                ).stream()
                .map(Registrations::getUserId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        contestRankSnapshotMapper.delete(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
        );

        if (registeredUserIdList.isEmpty()) {
            clearContestRankCache(contestId);
            return;
        }

        // TODO SQL优化：加联合索引
        List<QuestionSubmit> contestSubmitList = questionSubmitService.list(
                new LambdaQueryWrapper<QuestionSubmit>()
                        .eq(QuestionSubmit::getContestId, contestId)
                        // 先按用户、题目分组，再按提交时间和提交 id 倒序，便于保留每题最后一次提交。
                        // 查出来的数据中，同一个用户的提交在一起、同一个题目的提交在一起且按提交时间倒序排序
                        .orderByAsc(QuestionSubmit::getUserId)
                        .orderByAsc(QuestionSubmit::getQuestionId)
                        .orderByDesc(QuestionSubmit::getCreateTime)
//                        .orderByDesc(QuestionSubmit::getId)
        );

        Map<Long, Map<Long, JudgeInfo>> userQuestionStatusMap = new HashMap<>();
        for (QuestionSubmit questionSubmit : contestSubmitList) {
            Long userId = questionSubmit.getUserId();
            Long questionId = questionSubmit.getQuestionId();
            JudgeInfo judgeInfo = parseJudgeInfo(questionSubmit.getJudgeInfo());
            if (userId == null || questionId == null || judgeInfo == null) {
                continue;
            }
            Map<Long, JudgeInfo> questionStatusMap =
                    userQuestionStatusMap.computeIfAbsent(userId, key -> new HashMap<>());
            // 因为结果已经按最新提交倒序，所以同题第一次写入就是最终结果。
            questionStatusMap.putIfAbsent(questionId, judgeInfo);
        }

        for (Long userId : registeredUserIdList) {
            Map<Long, JudgeInfo> questionStatusMap =
                    userQuestionStatusMap.getOrDefault(userId, Collections.emptyMap());
            contestRankSnapshotMapper.insert(buildSnapshot(contestId, userId, questionStatusMap));
        }

        // 数据库更新榜单后再删除 Redis 的榜单，保证数据一致性（有一定延迟，非强一致性）
        clearContestRankCache(contestId);
        log.info("rebuild contest rank snapshot finished, contestId={}, userCount={}",
                contestId, registeredUserIdList.size());
    }

    /**
     * 重算单个用户在某场比赛中的最后提交结果。
     */
    private Map<Long, JudgeInfo> loadLatestQuestionStatus(Long contestId, Long userId) {
        List<QuestionSubmit> submitList = questionSubmitService.list(
                new LambdaQueryWrapper<QuestionSubmit>()
                        // TODO SQL优化：加联合索引（contestId，userId，CreateTime）
                        .eq(QuestionSubmit::getContestId, contestId)
                        .eq(QuestionSubmit::getUserId, userId)
                        .orderByDesc(QuestionSubmit::getCreateTime)
                        .orderByDesc(QuestionSubmit::getId)
        );

        Map<Long, JudgeInfo> latestQuestionStatus = new HashMap<>();
        for (QuestionSubmit submit : submitList) {
            Long questionId = submit.getQuestionId();
            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
            if (questionId == null || judgeInfo == null) {
                continue;
            }
            latestQuestionStatus.putIfAbsent(questionId, judgeInfo);
        }
        return latestQuestionStatus;
    }

    /**
     * 构建排行榜快照。
     */
    private ContestRankSnapshot buildSnapshot(Long contestId, Long userId, Map<Long, JudgeInfo> questionStatusMap) {
        RankMetrics rankMetrics = calculateRankMetrics(questionStatusMap);
        ContestRankSnapshot snapshot = new ContestRankSnapshot();
        snapshot.setContestId(contestId);
        snapshot.setUserId(userId);
        snapshot.setAcceptedNum(rankMetrics.acceptedNum());
        snapshot.setTotalTime(rankMetrics.totalTime());
        snapshot.setQuestionStatus(toQuestionStatusJson(questionStatusMap));
        snapshot.setSnapshotTime(new Date());
        return snapshot;
    }

    /**
     * 按“每题最后一次提交结果”计算排行榜指标。
     */
    private RankMetrics calculateRankMetrics(Map<Long, JudgeInfo> questionStatusMap) {
        if (questionStatusMap == null || questionStatusMap.isEmpty()) {
            return new RankMetrics(0, 0L);
        }
        int acceptedNum = 0;
        long totalTime = 0L;
        for (JudgeInfo judgeInfo : questionStatusMap.values()) {
            if (judgeInfo == null) {
                continue;
            }
            if ("Accepted".equalsIgnoreCase(judgeInfo.getMessage())) {
                acceptedNum++;
                long questionTime = judgeInfo.getTime() == null ? 0L : Math.max(judgeInfo.getTime(), 0L);
                totalTime += questionTime;
            }
        }
        return new RankMetrics(acceptedNum, totalTime);
    }

    /**
     * 插入或更新排行榜快照。
     */
    private void upsertSnapshot(ContestRankSnapshot snapshot) {
        ContestRankSnapshot existedSnapshot = contestRankSnapshotMapper.selectOne(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, snapshot.getContestId())
                        .eq(ContestRankSnapshot::getUserId, snapshot.getUserId())
                        .last("limit 1")
        );
        if (existedSnapshot == null) {
            contestRankSnapshotMapper.insert(snapshot);
            return;
        }
        snapshot.setId(existedSnapshot.getId());
        contestRankSnapshotMapper.updateById(snapshot);
    }

    /**
     * 从 Redis 读取分页缓存。
     */
    private Page<ContestUserVO> getRankPageFromCache(Long contestId, long current, long size) {
        try {
            String cacheValue = stringRedisTemplate.opsForValue().get(buildPageCacheKey(contestId, current, size));
            if (StringUtils.isBlank(cacheValue)) {
                return null;
            }
            ContestRankPageCache cache = objectMapper.readValue(cacheValue, ContestRankPageCache.class);
            Page<ContestUserVO> page = new Page<>(cache.getCurrent(), cache.getSize(), cache.getTotal());
            page.setRecords(cache.getRecords() == null ? Collections.emptyList() : cache.getRecords());
            return page;
        } catch (Exception e) {
            log.warn("load rank page cache failed, contestId={}, current={}, size={}",
                    contestId, current, size, e);
            return null;
        }
    }

    /**
     * 将分页结果写入 Redis。
     */
    private void cacheRankPage(Long contestId, Page<ContestUserVO> page) {
        if (contestId == null || contestId <= 0 || page == null) {
            return;
        }
        try {
            ContestRankPageCache cache = new ContestRankPageCache();
            cache.setCurrent(page.getCurrent());
            cache.setSize(page.getSize());
            cache.setTotal(page.getTotal());
            cache.setRecords(page.getRecords() == null ? Collections.emptyList() : page.getRecords());

            String pageKey = buildPageCacheKey(contestId, page.getCurrent(), page.getSize());
            String indexKey = buildPageIndexKey(contestId);
            String cacheValue = objectMapper.writeValueAsString(cache);
            stringRedisTemplate.opsForValue().set(pageKey, cacheValue, PAGE_CACHE_TTL);
            stringRedisTemplate.opsForSet().add(indexKey, pageKey);
            stringRedisTemplate.expire(indexKey, PAGE_CACHE_TTL);
        } catch (Exception e) {
            log.warn("cache rank page failed, contestId={}, current={}, size={}",
                    contestId, page.getCurrent(), page.getSize(), e);
        }
    }

    /**
     * 将快照转换为对外返回的排行榜视图。
     */
    private ContestUserVO toContestUserVO(ContestRankSnapshot snapshot, UserVO userVO) {
        ContestUserVO contestUserVO = new ContestUserVO();
        contestUserVO.setUserVO(userVO);
        contestUserVO.setAcNum(snapshot.getAcceptedNum() == null ? 0 : snapshot.getAcceptedNum());
        contestUserVO.setAllTime(snapshot.getTotalTime() == null ? 0L : snapshot.getTotalTime());
        contestUserVO.setQuestionSubmitStatus(parseQuestionStatus(snapshot.getQuestionStatus()));
        return contestUserVO;
    }

    /**
     * 反序列化题目状态 JSON。
     */
    private Map<Long, JudgeInfo> parseQuestionStatus(String questionStatusJson) {
        if (StringUtils.isBlank(questionStatusJson) || "null".equalsIgnoreCase(questionStatusJson)) {
            return new HashMap<>();
        }
        try {
            JSONObject jsonObject = JSONUtil.parseObj(questionStatusJson);
            Map<Long, JudgeInfo> result = new HashMap<>();
            for (String key : jsonObject.keySet()) {
                Object node = jsonObject.get(key);
                if (node == null) {
                    continue;
                }
                result.put(Long.valueOf(key), JSONUtil.toBean(JSONUtil.toJsonStr(node), JudgeInfo.class));
            }
            return result;
        } catch (Exception e) {
            log.warn("parse question status failed, questionStatusJson={}", questionStatusJson);
            return new HashMap<>();
        }
    }

    /**
     * 序列化题目状态 JSON。
     */
    private String toQuestionStatusJson(Map<Long, JudgeInfo> questionStatusMap) {
        if (questionStatusMap == null || questionStatusMap.isEmpty()) {
            return "{}";
        }
        Map<String, JudgeInfo> jsonMap = new HashMap<>();
        for (Map.Entry<Long, JudgeInfo> entry : questionStatusMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                jsonMap.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return JSONUtil.toJsonStr(jsonMap);
    }

    /**
     * 解析判题信息。
     */
    private JudgeInfo parseJudgeInfo(String judgeInfoJson) {
        if (StringUtils.isBlank(judgeInfoJson)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoJson, JudgeInfo.class);
        } catch (Exception e) {
            log.warn("parse judge info failed, judgeInfoJson={}", judgeInfoJson);
            return null;
        }
    }

    /**
     * 判断用户是否已报名该比赛。
     */
    private boolean isRegistered(Long contestId, Long userId) {
        Long count = registrationsService.count(
                Wrappers.<Registrations>lambdaQuery()
                        .eq(Registrations::getContestId, contestId)
                        .eq(Registrations::getUserId, userId)
        );
        return count != null && count > 0;
    }

    private boolean isValidContestUser(Long contestId, Long userId) {
        return contestId != null && contestId > 0 && userId != null && userId > 0;
    }

    private String buildPageCacheKey(Long contestId, long current, long size) {
        return PAGE_CACHE_KEY_PREFIX + contestId + ":" + current + ":" + size;
    }

    private String buildPageIndexKey(Long contestId) {
        return PAGE_CACHE_INDEX_KEY_PREFIX + contestId;
    }

    private record RankMetrics(int acceptedNum, long totalTime) {
    }

    /**
     * Redis 中存储的分页缓存结构。
     */
    @Data
    private static class ContestRankPageCache {
        /**
         * 当前页
         */
        private long current;

        /**
         * 每页大小
         */
        private long size;

        /**
         * 总记录数
         */
        private long total;

        /**
         * 当前页记录
         */
        private List<ContestUserVO> records = new ArrayList<>();
    }
}
