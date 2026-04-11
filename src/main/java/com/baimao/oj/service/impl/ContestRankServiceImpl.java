package com.baimao.oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.mapper.ContestRankSnapshotMapper;
import com.baimao.oj.model.entity.ContestRankSnapshot;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
/**
 * 比赛排行榜服务实现。
 *
 * <p>核心职责：
 * <p>1. 维护 Redis 中的排行榜有序集合（ZSet）和用户明细（Hash）。
 * <p>2. 维护数据库中的排行榜快照（ContestRankSnapshot），用于持久化与兜底恢复。
 * <p>3. 提供分页查询、单用户初始化、提交后增量刷新、整场比赛重建等能力。
 *
 * <p>存储设计：
 * <p>- ZSet：用于按分数排序和分页，member 中编码 userId 保证同分时稳定次序。
 * <p>- Hash：用于保存某个用户的完整排名明细（用户信息、AC 数、总耗时、题目状态等）。
 *
 * <p>排序规则：
 * <p>- 优先 AC 题数（越多越靠前）。
 * <p>- AC 数相同时比较总耗时（越小越靠前）。
 */
public class ContestRankServiceImpl implements ContestRankService {

    /** 排行榜有序集合键前缀，完整键格式：contest:rank:zset:{contestId} */
    private static final String RANK_ZSET_KEY_PREFIX = "contest:rank:zset:";
    /** 用户明细 Hash 键前缀，完整键格式：contest:rank:detail:{contestId}:{userId} */
    private static final String DETAIL_KEY_PREFIX = "contest:rank:detail:";

    /** Hash 字段：用户脱敏信息 */
    private static final String DETAIL_FIELD_SNAPSHOT = "snapshot";
    private static final String DETAIL_FIELD_USER_VO = "userVO";
    /** Hash 字段：总耗时（仅统计 AC 的耗时） */
    private static final String LEGACY_DETAIL_FIELD_ALL_TIME = "allTime";
    /** Hash 字段：AC 题数 */
    private static final String LEGACY_DETAIL_FIELD_AC_NUM = "acNum";
    /** Hash 字段：题目最新判题结果映射（questionId -> JudgeInfo） */
    private static final String LEGACY_DETAIL_FIELD_QUESTION_STATUS = "questionSubmitStatus";
    /** Hash 字段：题目最新提交元信息（questionId -> 最后一次提交时间和提交 ID） */
    private static final String LEGACY_DETAIL_FIELD_LAST_SUBMIT_META = "questionLastSubmitMeta";

    /** 读取用户明细时按固定顺序 multiGet，避免字段顺序不一致导致解析错位 */
    private static final List<String> DETAIL_FIELDS = List.of(
            DETAIL_FIELD_SNAPSHOT,
            DETAIL_FIELD_USER_VO
    );

    private static final List<String> LEGACY_DETAIL_FIELDS = List.of(
            DETAIL_FIELD_USER_VO,
            LEGACY_DETAIL_FIELD_ALL_TIME,
            LEGACY_DETAIL_FIELD_AC_NUM,
            LEGACY_DETAIL_FIELD_QUESTION_STATUS,
            LEGACY_DETAIL_FIELD_LAST_SUBMIT_META
    );

    /**
     * 分数编码时间位宽（$10^{12}$）。
     *
     * <p>最终分数计算为：acceptedNum * SCORE_TIME_RANGE + normalizedTime。
     * <p>这样可以保证 AC 数是高位主排序键，时间是低位次排序键。
     */
    private static final long SCORE_TIME_RANGE = 1_000_000_000_000L;

    /** 允许参与编码的最大总耗时（用于归一化后映射到分数低位区间） */
    private static final long MAX_TOTAL_TIME = SCORE_TIME_RANGE - 1;

    /**
     * Lua 脚本：原子更新单用户排行榜数据。
     *
     * <p>KEYS[1]：用户明细 Hash key。
     * <p>KEYS[2]：排行榜 ZSet key。
     * <p>ARGV[1..5]：Hash 五个字段。
     * <p>ARGV[6]：score。
     * <p>ARGV[7]：member。
     */
    private static final DefaultRedisScript<Long> UPSERT_RANK_SCRIPT = new DefaultRedisScript<>();

    static {
        UPSERT_RANK_SCRIPT.setResultType(Long.class);
        UPSERT_RANK_SCRIPT.setScriptText("""
                redis.call('HDEL', KEYS[1],
                  'allTime',
                  'acNum',
                  'questionSubmitStatus',
                  'questionLastSubmitMeta'
                )
                redis.call('HSET', KEYS[1],
                  'snapshot', ARGV[1],
                  'userVO', ARGV[2]
                )
                redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
                return 1
                """);
    }

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

    @Resource
    private ContestRankSnapshotSyncService contestRankSnapshotSyncService;

    /**
     * 分页查询比赛排行榜。
     *
     * <p>流程：
     * <p>1. 校验参数并判断报名人数。
     * <p>2. 校验 Redis 快照完整性，不完整则触发全量重建。
     * <p>3. 从 ZSet 分页取 member，再批量从 Hash 拉取详情并组装 VO。
     */
    @Override
    public Page<ContestUserVO> listContestRankPage(Long contestId, long current, long size) {
        if (!isValidContestQuery(contestId, current, size)) {
            return new Page<>(current, size, 0);
        }

        long registrationCount = registrationsService.getRegistrationCountByContestId(contestId);
        if (registrationCount <= 0) {
            return buildEmptyPage(current, size, 0);
        }

        // TODO 什么情况下会调用这个的重建？ 用户注册时写了快照、也添加了Redis缓存记录，为什么会出现注册数量与缓存数量不一致的情况？
        ensureRankDataReady(contestId, registrationCount);

        String zsetKey = buildRankZsetKey(contestId);
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        long safeTotal = total == null ? 0L : total;
        if (safeTotal <= 0) {
            return buildEmptyPage(current, size, 0);
        }

        long start = (current - 1) * size;
        long end = start + size - 1;
        Set<String> memberSet = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (memberSet == null || memberSet.isEmpty()) {
            // TODO 缓存查不到直接返回空？不查数据库吗？
            return buildEmptyPage(current, size, safeTotal);
        }

        List<Long> userIdList = memberSet.stream()
                .map(this::parseUserIdFromMember)
                .filter(Objects::nonNull)
                .toList();
        if (userIdList.isEmpty()) {
            return buildEmptyPage(current, size, safeTotal);
        }

        Map<Long, ContestUserVO> contestUserVOMap = loadContestUserVOByPipeline(contestId, userIdList);
        List<ContestUserVO> records = new ArrayList<>(userIdList.size());
        for (Long userId : userIdList) {
            ContestUserVO contestUserVO = contestUserVOMap.get(userId);
            if (contestUserVO == null) {
                contestUserVO = buildEmptyContestUserVO(fetchUserVO(userId));
            }
            records.add(contestUserVO);
        }

        Page<ContestUserVO> resultPage = new Page<>(current, size, safeTotal);
        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 初始化某个参赛用户的排名快照。
     *
     * <p>用于用户报名后的首次建档：默认 AC=0、耗时=0、题目状态为空。
     */
    @Override
    public void initUserRankSnapshot(Long contestId, Long userId) {
        if (!isValidContestUser(contestId, userId) || !isRegistered(contestId, userId)) {
            return;
        }
        ContestUserVO contestUserVO = buildEmptyContestUserVO(fetchUserVO(userId));
        upsertRankData(contestId, userId, contestUserVO);
        contestRankSnapshotSyncService.syncSnapshotAsync(toSnapshot(contestId, userId, contestUserVO));
    }

    /**
     * 在收到一次新的提交后，刷新对应用户的排行榜快照。
     *
     * <p>仅当该提交是该题“更新”的一次提交（按提交时间 + 提交 ID 判新旧）时，
     * 才会更新题目状态并重算指标，避免乱序消息覆盖新数据。
     */
    @Override
    public void refreshUserRankSnapshot(QuestionSubmit questionSubmit) {
        if (questionSubmit == null) {
            return;
        }
        Long contestId = questionSubmit.getContestId();
        Long userId = questionSubmit.getUserId();
        if (!isValidContestUser(contestId, userId) || !isRegistered(contestId, userId)) {
            return;
        }

        JudgeInfo judgeInfo = parseJudgeInfo(questionSubmit.getJudgeInfo());
        if (judgeInfo == null || questionSubmit.getQuestionId() == null) {
            return;
        }

        ContestUserVO oldContestUserVO = loadContestUserVO(contestId, userId);
        if (oldContestUserVO == null) {
            oldContestUserVO = buildEmptyContestUserVO(fetchUserVO(userId));
        }
        if (!isSubmissionAffectRank(oldContestUserVO, questionSubmit)) {
            return;
        }

        ContestUserVO newContestUserVO = copyContestUserVO(oldContestUserVO);
        newContestUserVO.setUserVO(fetchUserVO(userId));
        applySubmission(newContestUserVO, questionSubmit, judgeInfo);
        upsertRankData(contestId, userId, newContestUserVO);
        contestRankSnapshotSyncService.syncSnapshotAsync(toSnapshot(contestId, userId, newContestUserVO));
    }

    /**
     * 清理某场比赛的排行榜缓存（ZSet + 用户明细 Hash）。
     */
    @Override
    public void clearContestRankCache(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        Set<String> keysToDelete = new LinkedHashSet<>();
        keysToDelete.add(buildRankZsetKey(contestId));
        keysToDelete.addAll(listContestDetailKeys(contestId));
        if (!keysToDelete.isEmpty()) {
            stringRedisTemplate.delete(keysToDelete);
        }
    }

    /**
     * 删除某场比赛的排行榜全量数据（数据库快照 + Redis 缓存）。
     */
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
    private void ensureRankDataReady(Long contestId, long registrationCount) {
        Long zsetSize = stringRedisTemplate.opsForZSet().zCard(buildRankZsetKey(contestId));
        long safeZsetSize = zsetSize == null ? 0L : zsetSize;
        if (safeZsetSize != registrationCount) {
            rebuildContestRankData(contestId);
        }
    }
    /**
     * 以比赛为单位全量重建排行榜快照。
     *
     * 该逻辑只走冷路径，用于首查建快照、修复快照数量不一致等场景。
     */
    private void rebuildContestRankData(Long contestId) {
        List<Long> registeredUserIdList = registrationsService.list(
                        Wrappers.<Registrations>lambdaQuery()
                                .eq(Registrations::getContestId, contestId)
                ).stream()
                .map(Registrations::getUserId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        if (registeredUserIdList.isEmpty()) {
            removeContestRankData(contestId);
            return;
        }

        Map<Long, UserVO> userVOMap = userService.listByIds(registeredUserIdList).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO, (left, right) -> left));

        Map<Long, ContestUserVO> contestUserVOMap = new LinkedHashMap<>();

        for (Long userId : registeredUserIdList) {
            contestUserVOMap.put(userId, buildEmptyContestUserVO(userVOMap.get(userId)));
        }
        List<QuestionSubmit> submitList = questionSubmitService.list(
                new LambdaQueryWrapper<QuestionSubmit>()
                        // TODO 联合索引优化
                        .eq(QuestionSubmit::getContestId, contestId)
                        .orderByAsc(QuestionSubmit::getCreateTime)
                        .orderByAsc(QuestionSubmit::getId)
        );

//        List<QuestionSubmit> submitList = questionSubmitService.list(
//                new LambdaQueryWrapper<QuestionSubmit>()
//                        .eq(QuestionSubmit::getContestId, contestId)
//                        .orderByAsc(QuestionSubmit::getUserId)
//                        .orderByAsc(QuestionSubmit::getQuestionId)
//                        .orderByAsc(QuestionSubmit::getCreateTime)
//                        .orderByAsc(QuestionSubmit::getId)
//        );

        for (QuestionSubmit submit : submitList) {
            if (submit.getUserId() == null || !contestUserVOMap.containsKey(submit.getUserId())) {
                continue;
            }
            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
            if (judgeInfo == null || submit.getQuestionId() == null) {
                continue;
            }
            // 获取空的 ContestUserVO
            ContestUserVO contestUserVO = contestUserVOMap.get(submit.getUserId());
            // 当前提交是否是该题的最后一次提交
            if (!isSubmissionAffectRank(contestUserVO, submit)) {
                continue;
            }
            applySubmission(contestUserVO, submit, judgeInfo);
        }

        rewriteRankData(contestId, contestUserVOMap);
        rebuildSnapshots(contestId, contestUserVOMap);
        log.info("rebuild contest rank data finished, contestId={}, userCount={}",
                contestId, contestUserVOMap.size());
    }

    private void rebuildSnapshots(Long contestId, Map<Long, ContestUserVO> contestUserVOMap) {
        contestRankSnapshotMapper.delete(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
        );
        // 逐用户写入最新快照，保证数据库状态与当前 Redis 结果一致。
        for (Map.Entry<Long, ContestUserVO> entry : contestUserVOMap.entrySet()) {
            contestRankSnapshotMapper.insert(toSnapshot(contestId, entry.getKey(), entry.getValue()));
        }
    }

    /**
     * 用全量计算结果重写 Redis 中的排行榜数据。
     *
     * <p>先清缓存再用 pipeline 批量写入，减少 RTT 与重建时间。
     */
    private void rewriteRankData(Long contestId, Map<Long, ContestUserVO> contestUserVOMap) {
        String zsetKey = buildRankZsetKey(contestId);
        clearContestRankCache(contestId);
        stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(org.springframework.data.redis.core.RedisOperations operations) throws DataAccessException {
                for (Map.Entry<Long, ContestUserVO> entry : contestUserVOMap.entrySet()) {
                    Long userId = entry.getKey();
                    ContestUserVO contestUserVO = entry.getValue();
                    operations.opsForHash().putAll(
                            buildDetailKey(contestId, userId),
                            buildDetailHashEntries(contestId, userId, contestUserVO)
                    );
                    operations.opsForZSet().add(zsetKey, buildRankMember(userId), computeScore(contestUserVO));
                }
                return null;
            }
        });
    }

    /**
     * 通过 Redis pipeline 批量加载多个用户明细，避免 N 次往返。
     */
    private Map<Long, ContestUserVO> loadContestUserVOByPipeline(Long contestId, List<Long> userIdList) {
        if (userIdList == null || userIdList.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object> values = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(org.springframework.data.redis.core.RedisOperations operations) throws DataAccessException {
                for (Long userId : userIdList) {
                    operations.opsForHash().multiGet(buildDetailKey(contestId, userId), DETAIL_FIELDS);
                }
                return null;
            }
        });
        List<Object> safeValues = values == null ? Collections.emptyList() : values;

        Map<Long, ContestUserVO> contestUserVOMap = new HashMap<>();
        for (int i = 0; i < userIdList.size(); i++) {
            Long userId = userIdList.get(i);
            List<Object> detailValues = safeValues.size() > i && safeValues.get(i) instanceof List<?>
                    ? (List<Object>) safeValues.get(i)
                    : Collections.emptyList();
            ContestUserVO contestUserVO = parseContestUserVO(detailValues, userId);
            if (contestUserVO == null) {
                contestUserVO = loadLegacyContestUserVO(contestId, userId);
                if (contestUserVO != null) {
                    upsertRankData(contestId, userId, contestUserVO);
                }
            }
            if (contestUserVO != null) {
                contestUserVOMap.put(userId, contestUserVO);
            }
        }
        return contestUserVOMap;
    }

    /**
     * 加载单个用户排行榜明细。
     */
    private ContestUserVO loadContestUserVO(Long contestId, Long userId) {
        List<Object> detailValues = stringRedisTemplate.opsForHash().multiGet(buildDetailKey(contestId, userId), Collections.singleton(DETAIL_FIELDS));
        ContestUserVO contestUserVO = parseContestUserVO(detailValues, userId);
        if (contestUserVO != null) {
            return contestUserVO;
        }
        contestUserVO = loadLegacyContestUserVO(contestId, userId);
        if (contestUserVO != null) {
            upsertRankData(contestId, userId, contestUserVO);
        }
        return contestUserVO;
    }

    private ContestUserVO loadLegacyContestUserVO(Long contestId, Long userId) {
        List<Object> detailValues = stringRedisTemplate.opsForHash().multiGet(buildDetailKey(contestId, userId), Collections.singleton(LEGACY_DETAIL_FIELDS));
        return parseLegacyContestUserVO(detailValues, userId);
    }

    /**
     * 将 Redis Hash 的字段列表解析为 ContestUserVO。
     */
    private ContestUserVO parseContestUserVO(List<Object> detailValues, Long userId) {
        if (detailValues == null || detailValues.isEmpty()) {
            return null;
        }
        try {
            if (detailValues.stream().allMatch(Objects::isNull)) {
                return null;
            }
            ContestRankSnapshot snapshot = parseSnapshot(getStringValue(detailValues, 0), userId);
            if (snapshot == null) {
                return null;
            }
            ContestUserVO contestUserVO = buildEmptyContestUserVO(parseUserVO(getStringValue(detailValues, 1), userId));
            contestUserVO.setAllTime(snapshot.getTotalTime());
            contestUserVO.setAcNum(snapshot.getAcceptedNum());
            contestUserVO.setQuestionSubmitStatus(parseQuestionStatus(snapshot.getQuestionStatus()));
            contestUserVO.setQuestionLastSubmitMeta(copyQuestionLastSubmitMeta(snapshot.getQuestionLastSubmitMeta()));
            return normalizeContestUserVO(contestUserVO, userId);
        } catch (Exception e) {
            log.warn("parse contest user vo failed, userId={}", userId, e);
            return null;
        }
    }

    private ContestUserVO parseLegacyContestUserVO(List<Object> detailValues, Long userId) {
        if (detailValues == null || detailValues.isEmpty()) {
            return null;
        }
        try {
            if (detailValues.stream().allMatch(Objects::isNull)) {
                return null;
            }
            ContestUserVO contestUserVO = buildEmptyContestUserVO(parseUserVO(getStringValue(detailValues, 0), userId));
            contestUserVO.setAllTime(parseLong(detailValues, 1));
            contestUserVO.setAcNum(parseInteger(detailValues, 2));
            contestUserVO.setQuestionSubmitStatus(parseQuestionStatus(getStringValue(detailValues, 3)));
            contestUserVO.setQuestionLastSubmitMeta(parseQuestionLastSubmitMeta(getStringValue(detailValues, 4)));
            return normalizeContestUserVO(contestUserVO, userId);
        } catch (Exception e) {
            log.warn("parse legacy contest user vo failed, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 对反序列化后的 VO 做空值兜底，并回算指标，确保对象处于可用状态。
     */
    private ContestUserVO normalizeContestUserVO(ContestUserVO contestUserVO, Long userId) {
        if (contestUserVO == null) {
            return buildEmptyContestUserVO(fetchUserVO(userId));
        }
        if (contestUserVO.getUserVO() == null) {
            contestUserVO.setUserVO(fetchUserVO(userId));
        }
        if (contestUserVO.getAllTime() == null) {
            contestUserVO.setAllTime(0L);
        }
        if (contestUserVO.getAcNum() == null) {
            contestUserVO.setAcNum(0);
        }
        if (contestUserVO.getQuestionSubmitStatus() == null) {
            contestUserVO.setQuestionSubmitStatus(new HashMap<>());
        }
        if (contestUserVO.getQuestionLastSubmitMeta() == null) {
            contestUserVO.setQuestionLastSubmitMeta(new HashMap<>());
        }
        recalculateMetrics(contestUserVO);
        return contestUserVO;
    }

    /**
     * 构造一个空的排行榜用户视图对象。
     */
    private ContestUserVO buildEmptyContestUserVO(UserVO userVO) {
        ContestUserVO contestUserVO = new ContestUserVO();
        contestUserVO.setUserVO(userVO);
        contestUserVO.setAllTime(0L);
        contestUserVO.setAcNum(0);
        contestUserVO.setQuestionSubmitStatus(new HashMap<>());
        contestUserVO.setQuestionLastSubmitMeta(new HashMap<>());
        return contestUserVO;
    }

    /**
     * 深拷贝 ContestUserVO，避免原对象被就地修改导致并发可见性问题。
     */
    private ContestUserVO copyContestUserVO(ContestUserVO source) {
        ContestUserVO contestUserVO = new ContestUserVO();
        contestUserVO.setUserVO(source.getUserVO());
        contestUserVO.setAllTime(source.getAllTime());
        contestUserVO.setAcNum(source.getAcNum());

        Map<Long, JudgeInfo> questionStatus = new HashMap<>();
        for (Map.Entry<Long, JudgeInfo> entry : source.getQuestionSubmitStatus().entrySet()) {
            questionStatus.put(entry.getKey(), cloneJudgeInfo(entry.getValue()));
        }
        contestUserVO.setQuestionSubmitStatus(questionStatus);

        Map<Long, ContestUserVO.QuestionLastSubmitMeta> metaMap = new HashMap<>();
        for (Map.Entry<Long, ContestUserVO.QuestionLastSubmitMeta> entry : source.getQuestionLastSubmitMeta().entrySet()) {
            metaMap.put(entry.getKey(), cloneSubmitMeta(entry.getValue()));
        }
        contestUserVO.setQuestionLastSubmitMeta(metaMap);
        return contestUserVO;
    }

    /**
     * 将一次提交应用到用户视图：更新该题状态 + 记录“该题最后一次提交元信息” + 重算指标。
     */
    private void applySubmission(ContestUserVO contestUserVO, QuestionSubmit questionSubmit, JudgeInfo judgeInfo) {
        Long questionId = questionSubmit.getQuestionId();
        contestUserVO.getQuestionSubmitStatus().put(questionId, cloneJudgeInfo(judgeInfo));

        ContestUserVO.QuestionLastSubmitMeta meta = new ContestUserVO.QuestionLastSubmitMeta();
        meta.setSubmitId(questionSubmit.getId());
        meta.setSubmitTime(extractSubmitTime(questionSubmit));
        contestUserVO.getQuestionLastSubmitMeta().put(questionId, meta);

        recalculateMetrics(contestUserVO);
    }

    /**
     * 基于题目状态重算 AC 数和总耗时。
     */
    private void recalculateMetrics(ContestUserVO contestUserVO) {
        RankMetrics rankMetrics = calculateRankMetrics(contestUserVO.getQuestionSubmitStatus());
        contestUserVO.setAcNum(rankMetrics.acceptedNum());
        contestUserVO.setAllTime(rankMetrics.totalTime());
    }

    /**
     * 统计排名指标。
     *
     * <p>当前规则仅统计 AC 题目：
     * <p>- acceptedNum：AC 数量。
     * <p>- totalTime：所有 AC 题目的耗时和。
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
            if (JudgeInfoMessageEnum.ACCEPTED.getText().equalsIgnoreCase(judgeInfo.getMessage())) {
                acceptedNum++;
                long questionTime = judgeInfo.getTime() == null ? 0L : Math.max(judgeInfo.getTime(), 0L);
                totalTime += questionTime;
            }
        }
        return new RankMetrics(acceptedNum, totalTime);
    }

    /**
     * 判断本次提交是否会影响排行榜。
     *
     * <p>对于同一道题，只有“更新”的提交会生效。
     */
    private boolean isSubmissionAffectRank(ContestUserVO contestUserVO, QuestionSubmit questionSubmit) {
        ContestUserVO.QuestionLastSubmitMeta oldMeta =
                contestUserVO.getQuestionLastSubmitMeta().get(questionSubmit.getQuestionId());
        return isNewerSubmission(questionSubmit, oldMeta);
    }

    /**
     * 判断提交是否比历史记录更新。
     *
     * <p>优先比较提交时间；时间一致时比较提交 ID，避免并发/乱序场景下旧数据回写。
     */
    private boolean isNewerSubmission(QuestionSubmit questionSubmit, ContestUserVO.QuestionLastSubmitMeta oldMeta) {
        if (oldMeta == null) {
            return true;
        }
        long submitTime = extractSubmitTime(questionSubmit);
        long oldSubmitTime = oldMeta.getSubmitTime() == null ? 0L : oldMeta.getSubmitTime();
        if (submitTime != oldSubmitTime) {
            return submitTime > oldSubmitTime;
        }
        long submitId = questionSubmit.getId() == null ? 0L : questionSubmit.getId();
        long oldSubmitId = oldMeta.getSubmitId() == null ? 0L : oldMeta.getSubmitId();
        return submitId > oldSubmitId;
    }

    /**
     * 提取提交时间戳（毫秒），空值时返回 0。
     */
    private long extractSubmitTime(QuestionSubmit questionSubmit) {
        return questionSubmit.getCreateTime() == null ? 0L : questionSubmit.getCreateTime().getTime();
    }

    /**
     * 原子更新单用户排名数据（Hash + ZSet）。
     */
    private void upsertRankData(Long contestId, Long userId, ContestUserVO contestUserVO) {
        ContestRankSnapshot snapshot = toSnapshot(contestId, userId, contestUserVO);
        stringRedisTemplate.execute(
                UPSERT_RANK_SCRIPT,
                List.of(buildDetailKey(contestId, userId), buildRankZsetKey(contestId)),
                toSnapshotJson(snapshot),
                toUserVOJson(contestUserVO.getUserVO()),
                String.valueOf(computeScore(contestUserVO)),
                buildRankMember(userId)
        );
    }

    /**
     * 计算 ZSet 分数。
     *
     * <p>公式：score = acNum * SCORE_TIME_RANGE + normalizedTime。
     * <p>normalizedTime = MAX_TOTAL_TIME - min(totalTime, MAX_TOTAL_TIME)。
     * <p>因此 AC 数越高分越高；AC 相同情况下总耗时越小，normalizedTime 越大，排名越靠前。
     */
    private long computeScore(ContestUserVO contestUserVO) {
        int acceptedNum = contestUserVO.getAcNum() == null ? 0 : Math.max(contestUserVO.getAcNum(), 0);
        long totalTime = contestUserVO.getAllTime() == null ? 0L : Math.max(contestUserVO.getAllTime(), 0L);
        long normalizedTime = Math.max(0L, MAX_TOTAL_TIME - Math.min(totalTime, MAX_TOTAL_TIME));
        return acceptedNum * SCORE_TIME_RANGE + normalizedTime;
    }

    /**
     * 将内存态排行榜对象转换为数据库快照实体。
     */
    private ContestRankSnapshot toSnapshot(Long contestId, Long userId, ContestUserVO contestUserVO) {
        ContestRankSnapshot snapshot = new ContestRankSnapshot();
        snapshot.setContestId(contestId);
        snapshot.setUserId(userId);
        snapshot.setAcceptedNum(contestUserVO.getAcNum());
        snapshot.setTotalTime(contestUserVO.getAllTime());
        snapshot.setQuestionStatus(toQuestionStatusJson(contestUserVO.getQuestionSubmitStatus()));
        snapshot.setSnapshotTime(new Date());
        snapshot.setQuestionLastSubmitMeta(copyQuestionLastSubmitMeta(contestUserVO.getQuestionLastSubmitMeta()));
        return snapshot;
    }

    /**
     * 组装 Redis Hash 写入内容。
     */
    private Map<String, String> buildDetailHashEntries(Long contestId, Long userId, ContestUserVO contestUserVO) {
        Map<String, String> hashEntries = new HashMap<>();
        hashEntries.put(DETAIL_FIELD_SNAPSHOT, toSnapshotJson(toSnapshot(contestId, userId, contestUserVO)));
        hashEntries.put(DETAIL_FIELD_USER_VO, toUserVOJson(contestUserVO.getUserVO()));
        return hashEntries;
    }

    private String toSnapshotJson(ContestRankSnapshot snapshot) {
        try {
            return snapshot == null ? "{}" : objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("serialize contest rank snapshot failed", e);
        }
    }

    /**
     * 序列化用户 VO。
     */
    private String toUserVOJson(UserVO userVO) {
        try {
            return userVO == null ? "{}" : objectMapper.writeValueAsString(userVO);
        } catch (Exception e) {
            throw new IllegalStateException("serialize contest rank user vo failed", e);
        }
    }

    /**
     * 反序列化用户 VO，失败时回源数据库兜底。
     */
    private UserVO parseUserVO(String userVOJson, Long userId) {
        if (StringUtils.isBlank(userVOJson) || "{}".equals(userVOJson) || "null".equalsIgnoreCase(userVOJson)) {
            return fetchUserVO(userId);
        }
        try {
            return objectMapper.readValue(userVOJson, UserVO.class);
        } catch (Exception e) {
            log.warn("parse user vo failed, userId={}", userId, e);
            return fetchUserVO(userId);
        }
    }

    private ContestRankSnapshot parseSnapshot(String snapshotJson, Long userId) {
        if (StringUtils.isBlank(snapshotJson) || "{}".equals(snapshotJson) || "null".equalsIgnoreCase(snapshotJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotJson, ContestRankSnapshot.class);
        } catch (Exception e) {
            log.warn("parse contest rank snapshot failed, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 序列化“题目最后一次提交元信息”映射。
     */
    /**
     * 序列化题目判题状态映射。
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
     * 反序列化题目判题状态映射。
     */
    private Map<Long, JudgeInfo> parseQuestionStatus(String questionStatusJson) {
        if (StringUtils.isBlank(questionStatusJson) || "null".equalsIgnoreCase(questionStatusJson)) {
            return new HashMap<>();
        }
        try {
            Map<String, JudgeInfo> rawMap = objectMapper.readValue(
                    questionStatusJson,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, JudgeInfo.class)
            );
            Map<Long, JudgeInfo> result = new HashMap<>();
            if (rawMap == null) {
                return result;
            }
            for (Map.Entry<String, JudgeInfo> entry : rawMap.entrySet()) {
                if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                result.put(Long.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            log.warn("parse question status failed, questionStatusJson={}", questionStatusJson, e);
            return new HashMap<>();
        }
    }

    /**
     * 反序列化“题目最后一次提交元信息”映射。
     */
    private Map<Long, ContestUserVO.QuestionLastSubmitMeta> parseQuestionLastSubmitMeta(String metaJson) {
        if (StringUtils.isBlank(metaJson) || "null".equalsIgnoreCase(metaJson)) {
            return new HashMap<>();
        }
        try {
            Map<String, ContestUserVO.QuestionLastSubmitMeta> rawMap = objectMapper.readValue(
                    metaJson,
                    objectMapper.getTypeFactory().constructMapType(
                            HashMap.class,
                            String.class,
                            ContestUserVO.QuestionLastSubmitMeta.class
                    )
            );
            Map<Long, ContestUserVO.QuestionLastSubmitMeta> result = new HashMap<>();
            if (rawMap == null) {
                return result;
            }
            for (Map.Entry<String, ContestUserVO.QuestionLastSubmitMeta> entry : rawMap.entrySet()) {
                if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                result.put(Long.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            log.warn("parse question last submit meta failed, metaJson={}", metaJson, e);
            return new HashMap<>();
        }
    }

    /**
     * 解析判题信息 JSON。
     */
    private JudgeInfo parseJudgeInfo(String judgeInfoJson) {
        if (StringUtils.isBlank(judgeInfoJson) || "null".equalsIgnoreCase(judgeInfoJson)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoJson, JudgeInfo.class);
        } catch (Exception e) {
            log.warn("parse judge info failed, judgeInfoJson={}", judgeInfoJson, e);
            return null;
        }
    }

    /**
     * 深拷贝 JudgeInfo，避免对象共享引发意外修改。
     */
    private JudgeInfo cloneJudgeInfo(JudgeInfo judgeInfo) {
        if (judgeInfo == null) {
            return null;
        }
        JudgeInfo copy = new JudgeInfo();
        copy.setMessage(judgeInfo.getMessage());
        copy.setMemory(judgeInfo.getMemory());
        copy.setTime(judgeInfo.getTime());
        copy.setErrorIndex(judgeInfo.getErrorIndex());
        return copy;
    }

    /**
     * 深拷贝最后一次提交元信息。
     */
    private ContestUserVO.QuestionLastSubmitMeta cloneSubmitMeta(ContestUserVO.QuestionLastSubmitMeta meta) {
        if (meta == null) {
            return null;
        }
        ContestUserVO.QuestionLastSubmitMeta copy = new ContestUserVO.QuestionLastSubmitMeta();
        copy.setSubmitId(meta.getSubmitId());
        copy.setSubmitTime(meta.getSubmitTime());
        return copy;
    }

    private Map<Long, ContestUserVO.QuestionLastSubmitMeta> copyQuestionLastSubmitMeta(
            Map<Long, ContestUserVO.QuestionLastSubmitMeta> metaMap) {
        Map<Long, ContestUserVO.QuestionLastSubmitMeta> result = new HashMap<>();
        if (metaMap == null || metaMap.isEmpty()) {
            return result;
        }
        for (Map.Entry<Long, ContestUserVO.QuestionLastSubmitMeta> entry : metaMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey(), cloneSubmitMeta(entry.getValue()));
        }
        return result;
    }

    /**
     * 查询并转换用户信息为 UserVO。
     */
    private UserVO fetchUserVO(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        User user = userService.getById(userId);
        return user == null ? null : userService.getUserVO(user);
    }

    /**
     * 判断用户是否报名了该比赛。
     */
    private boolean isRegistered(Long contestId, Long userId) {
        Long count = registrationsService.count(
                Wrappers.<Registrations>lambdaQuery()
                        .eq(Registrations::getContestId, contestId)
                        .eq(Registrations::getUserId, userId)
        );
        return count != null && count > 0;
    }

    /** 比赛 ID 与用户 ID 的基础合法性校验。 */
    private boolean isValidContestUser(Long contestId, Long userId) {
        return contestId != null && contestId > 0 && userId != null && userId > 0;
    }

    /** 分页参数合法性校验。 */
    private boolean isValidContestQuery(Long contestId, long current, long size) {
        return contestId != null && contestId > 0 && current > 0 && size > 0;
    }

    /** 构造空分页返回对象。 */
    private Page<ContestUserVO> buildEmptyPage(long current, long size, long total) {
        Page<ContestUserVO> page = new Page<>(current, size, total);
        page.setRecords(Collections.emptyList());
        return page;
    }

    /** 构造比赛排行榜 ZSet 键。 */
    private String buildRankZsetKey(Long contestId) {
        return RANK_ZSET_KEY_PREFIX + contestId;
    }

    /** 构造用户明细 Hash 键。 */
    private String buildDetailKey(Long contestId, Long userId) {
        return DETAIL_KEY_PREFIX + contestId + ":" + userId;
    }

    /**
     * 列出比赛相关的明细键。
     *
     * <p>来源包括：
     * <p>1. 当前 ZSet 中存在的 member；
     * <p>2. 报名表中的用户（用于覆盖“只剩明细或只剩索引”的异常情况）。
     */
    private Set<String> listContestDetailKeys(Long contestId) {
        Set<String> detailKeys = new LinkedHashSet<>();
        Set<String> members = stringRedisTemplate.opsForZSet().range(buildRankZsetKey(contestId), 0, -1);
        if (members != null) {
            for (String member : members) {
                Long userId = parseUserIdFromMember(member);
                if (userId != null) {
                    detailKeys.add(buildDetailKey(contestId, userId));
                }
            }
        }
        List<Long> registeredUserIdList = registrationsService.list(
                        Wrappers.<Registrations>lambdaQuery()
                                .eq(Registrations::getContestId, contestId)
                ).stream()
                .map(Registrations::getUserId)
                .filter(Objects::nonNull)
                .toList();
        for (Long userId : registeredUserIdList) {
            detailKeys.add(buildDetailKey(contestId, userId));
        }
        return detailKeys;
    }

    /** 从 Redis 字段值解析整数，空值返回 0。 */
    private Integer parseInteger(List<Object> values, int index) {
        String value = getStringValue(values, index);
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    /** 从 Redis 字段值解析长整数，空值返回 0。 */
    private Long parseLong(List<Object> values, int index) {
        String value = getStringValue(values, index);
        if (StringUtils.isBlank(value)) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    /** 安全读取字段字符串值。 */
    private String getStringValue(List<Object> values, int index) {
        if (values == null || index < 0 || values.size() <= index || values.get(index) == null) {
            return null;
        }
        return String.valueOf(values.get(index));
    }

    /**
     * 构造 ZSet member。
     *
     * <p>格式：{Long.MAX_VALUE-userId}:{userId}，用于在同分时让 userId 小者靠前。
     * <p>因为这里使用 reverseRange 取高分在前，member 的字典序会作为同分的次级排序依据。
     */
    private String buildRankMember(Long userId) {
        long safeUserId = userId == null ? 0L : userId;
        return String.format("%019d:%019d", Long.MAX_VALUE - safeUserId, safeUserId);
    }

    /**
     * 从 member 中解析 userId。
     */
    private Long parseUserIdFromMember(String member) {
        if (StringUtils.isBlank(member)) {
            return null;
        }
        int index = member.lastIndexOf(':');
        String userIdPart = index >= 0 ? member.substring(index + 1) : member;
        try {
            return Long.parseLong(userIdPart);
        } catch (NumberFormatException e) {
            log.warn("parse rank member userId failed, member={}", member, e);
            return null;
        }
    }

    /** 排名统计结果载体：AC 数与总耗时。 */
    private record RankMetrics(int acceptedNum, long totalTime) {
    }
}
