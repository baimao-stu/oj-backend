package com.baimao.oj.service.impl;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.mapper.ContestRankSnapshotMapper;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.model.entity.ContestRankSnapshot;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;
import com.baimao.oj.model.vo.ContestRankSnapshotVO;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baimao.oj.model.vo.UserVO;
import com.baimao.oj.service.ContestService;
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
import java.util.concurrent.TimeUnit;
import java.util.UUID;
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

    /** 读取用户明细时按固定顺序 multiGet，避免字段顺序不一致导致解析错位 */
    private static final List<String> DETAIL_FIELDS = List.of(
            DETAIL_FIELD_SNAPSHOT,
            DETAIL_FIELD_USER_VO
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

    /** 排行榜缓存的最小过期时间，避免已到期场景写入后永不过期。 */
    private static final long MIN_EXPIRE_SECONDS = 1L;

    /** 兜底过期时间（1 天）。 */
    private static final long FALLBACK_EXPIRE_SECONDS = 24L * 60L * 60L;

    /** 冷路径查库互斥锁键前缀，完整键格式：contest:rank:db:fallback:lock:{contestId} */
    private static final String SNAPSHOT_FALLBACK_LOCK_KEY_PREFIX = "contest:rank:db:fallback:lock:";

    /** 冷路径查库互斥锁过期时间（秒），防止异常场景锁无法释放。 */
    private static final long SNAPSHOT_FALLBACK_LOCK_EXPIRE_SECONDS = 10L;

    /**
     * Lua 脚本：原子更新单用户排行榜数据。
     *
     * <p>KEYS[1]：用户明细 Hash key。
     * <p>KEYS[2]：排行榜 ZSet key。
     * <p>ARGV[1]：snapshot JSON。
     * <p>ARGV[2]：userVO JSON。
     * <p>ARGV[3]：score。
     * <p>ARGV[4]：member。
    * <p>ARGV[5]：expireSeconds。
     */
    private static final DefaultRedisScript<Long> UPSERT_RANK_SCRIPT = new DefaultRedisScript<>();

    /** Lua 脚本：原子释放互斥锁，仅删除自己持有的锁。 */
    private static final DefaultRedisScript<Long> RELEASE_MUTEX_LOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        UPSERT_RANK_SCRIPT.setResultType(Long.class);
        UPSERT_RANK_SCRIPT.setScriptText("""
                                redis.call('HSET', KEYS[1], 'snapshot', ARGV[1])
                                redis.call('HSET', KEYS[1], 'userVO', ARGV[2])
                redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
                                redis.call('EXPIRE', KEYS[1], ARGV[5])
                                redis.call('EXPIRE', KEYS[2], ARGV[5])
                return 1
                """);

        RELEASE_MUTEX_LOCK_SCRIPT.setResultType(Long.class);
        RELEASE_MUTEX_LOCK_SCRIPT.setScriptText("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """);
    }

    @Resource
    private ContestRankSnapshotMapper contestRankSnapshotMapper;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private RegistrationsService registrationsService;

    @Resource
    private ContestService contestService;

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
    public Page<ContestRankSnapshotVO> listContestRankPage(Long contestId, long current, long size) {
        if (!isValidContestQuery(contestId, current, size)) {
            return new Page<>(current, size, 0);
        }

        long registrationCount = registrationsService.getRegistrationCountByContestId(contestId);
        if (registrationCount <= 0) {
            return buildEmptyPage(current, size, 0);
        }

        // 用户注册时写了快照、也添加了Redis缓存记录，正常来讲不需要重建redis的排行榜，
        // 但是如果报名后再写快照时出异常（即用户报名了但排行缓存没有写入redis），
        // 就会导致排行榜数据不完整，所以这里做一个容错：当发现排行榜快照数量与报名人数不一致时，触发一次重建，保证排行榜数据完整性。
        ensureRankDataReady(contestId, registrationCount);

        String zsetKey = buildRankZsetKey(contestId);
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        long safeTotal = total == null ? 0L : total;
        if (safeTotal <= 0) {
            return loadSnapshotRankPageWithMutex(contestId, current, size, 0L);
        }

        long start = (current - 1) * size;
        long end = start + size - 1;
        Set<String> memberSet = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (memberSet == null || memberSet.isEmpty()) {
            if (start >= safeTotal) {
                return buildEmptyPage(current, size, safeTotal);
            }
            return loadSnapshotRankPageWithMutex(contestId, current, size, safeTotal);
        }

        List<Long> userIdList = memberSet.stream()
                .map(this::parseUserIdFromMember)
                .filter(Objects::nonNull)
                .toList();
        if (userIdList.isEmpty()) {
            return buildEmptyPage(current, size, safeTotal);
        }
        
        // 缓存中查询 detail
        Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap = loadContestRankSnapshotVOByPipeline(contestId, userIdList);
        List<ContestRankSnapshotVO> records = new ArrayList<>(userIdList.size());
        for (Long userId : userIdList) {
            ContestRankSnapshotVO contestRankSnapshotVO = contestRankSnapshotVOMap.get(userId);
            if (contestRankSnapshotVO == null) {
                contestRankSnapshotVO = buildEmptyContestRankSnapshotVO(contestId, userId, fetchUserVO(userId));
            }
            records.add(contestRankSnapshotVO);
        }

        Page<ContestRankSnapshotVO> resultPage = new Page<>(current, size, safeTotal);
        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 当 Redis 榜单为空或分页出现空洞时，从快照表分页兜底查询。
     */
    private Page<ContestRankSnapshotVO> loadSnapshotRankPage(Long contestId, long current, long size) {
        Page<ContestRankSnapshot> snapshotPage = new Page<>(current, size);

        long startTime = System.currentTimeMillis();

        Page<ContestRankSnapshot> queriedPage = contestRankSnapshotMapper.selectPage(
                snapshotPage,
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
                        .orderByDesc(ContestRankSnapshot::getAcceptedNum)
                        .orderByAsc(ContestRankSnapshot::getTotalTime)
                        .orderByAsc(ContestRankSnapshot::getUserId)
        );

        long endTime = System.currentTimeMillis();
        log.info("query contest rank snapshot page finished, contestId={}, current={}, size={}, timeCost={}ms",
                contestId, current, size, (endTime - startTime));


        if (queriedPage == null || queriedPage.getTotal() <= 0 || queriedPage.getRecords() == null || queriedPage.getRecords().isEmpty()) {
            return buildEmptyPage(current, size, 0);
        }

        List<Long> userIdList = queriedPage.getRecords().stream()
                .map(ContestRankSnapshot::getUserId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        Map<Long, UserVO> userVOMap = userIdList.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO, (left, right) -> left));

        List<ContestRankSnapshotVO> records = new ArrayList<>(queriedPage.getRecords().size());
        for (ContestRankSnapshot snapshot : queriedPage.getRecords()) {
            if (snapshot == null || snapshot.getUserId() == null || snapshot.getUserId() <= 0) {
                continue;
            }
            Long userId = snapshot.getUserId();
            ContestRankSnapshot snapshotCopy = new ContestRankSnapshot();
            snapshotCopy.setId(snapshot.getId());
            snapshotCopy.setContestId(snapshot.getContestId());
            snapshotCopy.setUserId(snapshot.getUserId());
            snapshotCopy.setAcceptedNum(snapshot.getAcceptedNum());
            snapshotCopy.setTotalTime(snapshot.getTotalTime());
            snapshotCopy.setQuestionStatus(copyQuestionStatus(snapshot.getQuestionStatus()));
            snapshotCopy.setQuestionLastSubmitMeta(copyQuestionLastSubmitMeta(snapshot.getQuestionLastSubmitMeta()));
            snapshotCopy.setSnapshotTime(snapshot.getSnapshotTime() == null
                    ? null
                    : new Date(snapshot.getSnapshotTime().getTime()));
            records.add(normalizeContestRankSnapshotVO(toContestRankSnapshotVO(snapshotCopy, userVOMap.get(userId)), userId));
        }

        Page<ContestRankSnapshotVO> resultPage = new Page<>(current, size, queriedPage.getTotal());
        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 冷路径互斥兜底：只允许一个线程查库，其余线程直接返回空分页。
     */
    private Page<ContestRankSnapshotVO> loadSnapshotRankPageWithMutex(Long contestId, long current, long size, long emptyTotal) {
        String lockKey = buildSnapshotFallbackLockKey(contestId);
        String lockValue = tryAcquireMutex(lockKey);
        if (lockValue == null) {
            log.info("skip snapshot fallback query because mutex lock not acquired, contestId={}, current={}, size={}",
                    contestId, current, size);
            return buildEmptyPage(current, size, emptyTotal);
        }
        try {
            return loadSnapshotRankPage(contestId, current, size);
        } finally {
            releaseMutex(lockKey, lockValue);
        }
    }

    /**
     * 初始化某个参赛某个用户的排名快照。
     *
     * <p>用于用户报名后的首次建档：默认 AC=0、耗时=0、题目状态为空。
     */
    @Override
    public void initUserRankSnapshot(Long contestId, Long userId) {
        if (!isValidContestUser(contestId, userId) || !isRegistered(contestId, userId)) {
            return;
        }
        ContestRankSnapshotVO contestRankSnapshotVO = buildEmptyContestRankSnapshotVO(contestId, userId, fetchUserVO(userId));
        // upsertRankData(contestId, userId, contestRankSnapshotVO);
        contestRankSnapshotSyncService.syncSnapshotAsync(toSnapshot(contestId, userId, contestRankSnapshotVO));
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

        UserVO userVO = fetchUserVO(userId);
        ContestRankSnapshotVO oldContestRankSnapshotVO = loadContestRankSnapshotVO(contestId, userId);
        if (oldContestRankSnapshotVO == null) {
            oldContestRankSnapshotVO = buildEmptyContestRankSnapshotVO(contestId, userId, userVO);
        }
        
        if (!isSubmissionAffectRank(oldContestRankSnapshotVO.getContestRankSnapshot(), questionSubmit)) {
            return;
        }

        ContestRankSnapshotVO newContestRankSnapshotVO = copyContestRankSnapshotVO(oldContestRankSnapshotVO);
        newContestRankSnapshotVO.setUserVO(userVO);
        applySubmission(newContestRankSnapshotVO, questionSubmit, judgeInfo);

        // 更新 Redis 的 ZSet 和 对应用户的 Hash key（榜单的事实维护由 redis 做）
        // 排行榜读取主要走 Redis，先写 Redis 可以保证榜单“立即可见”，用户体验最好。
        upsertRankData(contestId, userId, newContestRankSnapshotVO);
        // 异步更新数据库快照 TODO 消息队列
        contestRankSnapshotSyncService.syncSnapshotAsync(toSnapshot(contestId, userId, newContestRankSnapshotVO));
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
            String lockKey = buildSnapshotFallbackLockKey(contestId);
            String lockValue = tryAcquireMutex(lockKey);
            if (lockValue == null) {
                log.info("skip rebuild rank data because mutex lock not acquired, contestId={}, expectedSize={}, currentSize={}",
                        contestId, registrationCount, safeZsetSize);
                return;
            }
            try {
                Long latestZsetSize = stringRedisTemplate.opsForZSet().zCard(buildRankZsetKey(contestId));
                long latestSafeZsetSize = latestZsetSize == null ? 0L : latestZsetSize;
                if (latestSafeZsetSize != registrationCount) {
//                    rebuildContestRankData(contestId);
                    rebuildContestRankDataFromSnapshot(contestId);
                }
            } finally {
                releaseMutex(lockKey, lockValue);
            }
        }
    }
    /**
     * 以比赛为单位全量重建排行榜快照。
     *
     * 该逻辑只走冷路径，用于首查建快照、修复快照数量不一致等场景。
     */
//    private void rebuildContestRankData(Long contestId) {
//        List<Long> registeredUserIdList = registrationsService.list(
//                        Wrappers.<Registrations>lambdaQuery()
//                                .eq(Registrations::getContestId, contestId)
//                ).stream()
//                .map(Registrations::getUserId)
//                .filter(id -> id != null && id > 0)
//                .distinct()
//                .toList();
//
//        if (registeredUserIdList.isEmpty()) {
//            removeContestRankData(contestId);
//            return;
//        }
//
//        Map<Long, UserVO> userVOMap = userService.listByIds(registeredUserIdList).stream()
//                .collect(Collectors.toMap(User::getId, userService::getUserVO, (left, right) -> left));
//
//        Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap = new LinkedHashMap<>();
//
//        for (Long userId : registeredUserIdList) {
//            contestRankSnapshotVOMap.put(userId, buildEmptyContestRankSnapshotVO(contestId, userId, userVOMap.get(userId)));
//        }
//        List<QuestionSubmit> submitList = questionSubmitService.list(
//                new LambdaQueryWrapper<QuestionSubmit>()
//                        // TODO 联合索引优化
//                        .eq(QuestionSubmit::getContestId, contestId)
//                        .orderByAsc(QuestionSubmit::getCreateTime)
//                        .orderByAsc(QuestionSubmit::getId)
//        );
//
////        List<QuestionSubmit> submitList = questionSubmitService.list(
////                new LambdaQueryWrapper<QuestionSubmit>()
////                        .eq(QuestionSubmit::getContestId, contestId)
////                        .orderByAsc(QuestionSubmit::getUserId)
////                        .orderByAsc(QuestionSubmit::getQuestionId)
////                        .orderByAsc(QuestionSubmit::getCreateTime)
////                        .orderByAsc(QuestionSubmit::getId)
////        );
//
//        for (QuestionSubmit submit : submitList) {
//            if (submit.getUserId() == null || !contestRankSnapshotVOMap.containsKey(submit.getUserId())) {
//                continue;
//            }
//            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
//            if (judgeInfo == null || submit.getQuestionId() == null) {
//                continue;
//            }
//            ContestRankSnapshotVO contestRankSnapshotVO = contestRankSnapshotVOMap.get(submit.getUserId());
//            // 当前提交是否是该题的最后一次提交
//            if (!isSubmissionAffectRank(contestRankSnapshotVO.getContestRankSnapshot(), submit)) {
//                continue;
//            }
//            applySubmission(contestRankSnapshotVO, submit, judgeInfo);
//        }
//
//        // 计算完整的排行榜所需要的数据，写回 数据库快照表 和 redis
//         rebuildSnapshots(contestId, contestRankSnapshotVOMap);
//        rewriteRankData(contestId, contestRankSnapshotVOMap);
//
//        log.info("rebuild contest rank data finished, contestId={}, userCount={}",
//                contestId, contestRankSnapshotVOMap.size());
//    }

    /**
     * 基于快照表重建 Redis 排行榜。
     *
     * <p>该方法不会替换原有按提交表重建的逻辑，作为并行补充能力保留。
     */
    private void rebuildContestRankDataFromSnapshot(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // 从快照表查询排行榜
        List<ContestRankSnapshot> snapshotList = contestRankSnapshotMapper.selectList(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, contestId)
                        .orderByDesc(ContestRankSnapshot::getAcceptedNum)
                        .orderByAsc(ContestRankSnapshot::getTotalTime)
                        .orderByAsc(ContestRankSnapshot::getUserId)
        );

        long endTime = System.currentTimeMillis();
        log.info("query contest rank snapshot page finished, contestId={}, timeCost={}ms",
                contestId, (endTime - startTime));

        if (snapshotList == null || snapshotList.isEmpty()) {
            clearContestRankCache(contestId);
            log.info("rebuild rank data from snapshot skipped, contestId={}, reason=no snapshot", contestId);
            return;
        }

        List<Long> userIdList = snapshotList.stream()
                .map(ContestRankSnapshot::getUserId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        Map<Long, UserVO> userVOMap = userService.listByIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO, (left, right) -> left));

        Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap = new LinkedHashMap<>();
        for (ContestRankSnapshot snapshot : snapshotList) {
            if (snapshot == null || snapshot.getUserId() == null || snapshot.getUserId() <= 0) {
                continue;
            }

            Long userId = snapshot.getUserId();
            ContestRankSnapshot snapshotCopy = new ContestRankSnapshot();
            snapshotCopy.setId(snapshot.getId());
            snapshotCopy.setContestId(snapshot.getContestId());
            snapshotCopy.setUserId(snapshot.getUserId());
            snapshotCopy.setAcceptedNum(snapshot.getAcceptedNum());
            snapshotCopy.setTotalTime(snapshot.getTotalTime());
            snapshotCopy.setQuestionStatus(copyQuestionStatus(snapshot.getQuestionStatus()));
            snapshotCopy.setQuestionLastSubmitMeta(new HashMap<>());
            snapshotCopy.setSnapshotTime(snapshot.getSnapshotTime() == null
                    ? null
                    : new Date(snapshot.getSnapshotTime().getTime()));

            contestRankSnapshotVOMap.put(
                    userId,
                    normalizeContestRankSnapshotVO(toContestRankSnapshotVO(snapshotCopy, userVOMap.get(userId)), userId)
            );
        }

        if (contestRankSnapshotVOMap.isEmpty()) {
            clearContestRankCache(contestId);
            log.info("rebuild rank data from snapshot skipped, contestId={}, reason=no valid user snapshot", contestId);
            return;
        }

        rewriteRankData(contestId, contestRankSnapshotVOMap);
        log.info("rebuild rank data from snapshot finished, contestId={}, userCount={}",
                contestId, contestRankSnapshotVOMap.size());
    }

//     private void rebuildSnapshots(Long contestId, Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap) {
//         contestRankSnapshotMapper.delete(
//                 Wrappers.<ContestRankSnapshot>lambdaQuery()
//                         .eq(ContestRankSnapshot::getContestId, contestId)
//         );
//         // 逐用户写入最新快照，保证数据库状态与当前 Redis 结果一致。
//         for (Map.Entry<Long, ContestRankSnapshotVO> entry : contestRankSnapshotVOMap.entrySet()) {
//             contestRankSnapshotMapper.insert(toSnapshot(contestId, entry.getKey(), entry.getValue()));
//         }
//     }

    /**
     * 用全量计算结果重写 Redis 中的排行榜数据。
     *
     * <p>先清缓存再用 pipeline 批量写入，减少 RTT 与重建时间。
     */
    private void rewriteRankData(Long contestId, Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap) {
        String zsetKey = buildRankZsetKey(contestId);
        long expireSeconds = computeContestRankExpireSeconds(contestId);
        clearContestRankCache(contestId);

        long startTime = System.currentTimeMillis();

        // 使用 Pipeline
         stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
             @Override
             @SuppressWarnings("unchecked")
             public Object execute(org.springframework.data.redis.core.RedisOperations operations) throws DataAccessException {
                 for (Map.Entry<Long, ContestRankSnapshotVO> entry : contestRankSnapshotVOMap.entrySet()) {
                     Long userId = entry.getKey();
                     ContestRankSnapshotVO contestRankSnapshotVO = entry.getValue();
                     operations.opsForHash().putAll(
                             buildDetailKey(contestId, userId),
                             buildDetailHashEntries(contestId, userId, contestRankSnapshotVO)
                     );
                     operations.expire(buildDetailKey(contestId, userId), expireSeconds, TimeUnit.SECONDS);
                     operations.opsForZSet().add(zsetKey, buildRankMember(userId), computeScore(contestRankSnapshotVO));
                 }
                 operations.expire(zsetKey, expireSeconds, TimeUnit.SECONDS);
                 return null;
             }
         });

        // 不使用 Pipeline
//        for (Map.Entry<Long, ContestRankSnapshotVO> entry : contestRankSnapshotVOMap.entrySet()) {
//                    Long userId = entry.getKey();
//                    ContestRankSnapshotVO contestRankSnapshotVO = entry.getValue();
//                    stringRedisTemplate.opsForHash().putAll(
//                            buildDetailKey(contestId, userId),
//                            buildDetailHashEntries(contestId, userId, contestRankSnapshotVO)
//                    );
//                    stringRedisTemplate.expire(buildDetailKey(contestId, userId), expireSeconds, TimeUnit.SECONDS);
//                    stringRedisTemplate.opsForZSet().add(zsetKey, buildRankMember(userId), computeScore(contestRankSnapshotVO));
//                }
//            stringRedisTemplate.expire(zsetKey, expireSeconds, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        log.info("rewrite rank data finished, contestId={}, userCount={}, timeCost={}ms",
                contestId, contestRankSnapshotVOMap.size(), (endTime - startTime));
    }

    /**
     * 通过 Redis pipeline 批量加载多个用户明细，避免 N 次往返。
     */
    private Map<Long, ContestRankSnapshotVO> loadContestRankSnapshotVOByPipeline(Long contestId, List<Long> userIdList) {
        if (userIdList == null || userIdList.isEmpty()) {
            return Collections.emptyMap();
        }
        long startTime = System.currentTimeMillis();

        // 使用 Pipeline
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

        // 不使用 Pipeline
//        List<Object> values = new ArrayList<>();
//        for (Long userId : userIdList) {
//            values.add(stringRedisTemplate.opsForHash().multiGet(
//                    buildDetailKey(contestId, userId),
//                    new ArrayList<Object>(DETAIL_FIELDS)
//            ));
//        }

        long endTime = System.currentTimeMillis();
        log.info("load contest rank snapshot finished, contestId={}, userCount={}, timeCost={}ms",
                contestId, userIdList.size(), (endTime - startTime));

        List<Object> safeValues = values == null ? Collections.emptyList() : values;

        Map<Long, ContestRankSnapshotVO> contestRankSnapshotVOMap = new HashMap<>();
        for (int i = 0; i < userIdList.size(); i++) {
            Long userId = userIdList.get(i);
            List<Object> detailValues = safeValues.size() > i && safeValues.get(i) instanceof List<?>
                    ? (List<Object>) safeValues.get(i)
                    : Collections.emptyList();
            ContestRankSnapshotVO contestRankSnapshotVO = parseContestRankSnapshotVO(detailValues, userId);
            if (contestRankSnapshotVO != null) {
                contestRankSnapshotVOMap.put(userId, contestRankSnapshotVO);
            }
        }
        return contestRankSnapshotVOMap;
    }

    private ContestRankSnapshotVO loadContestRankSnapshotVO(Long contestId, Long userId) {
        List<Object> detailValues = stringRedisTemplate.opsForHash().multiGet(
                buildDetailKey(contestId, userId),
                new ArrayList<Object>(DETAIL_FIELDS)
        );
        return parseContestRankSnapshotVO(detailValues, userId);
    }

    /**
     * 将 Redis Hash 的字段列表解析为 ContestUserVO。
     */
    private ContestRankSnapshotVO parseContestRankSnapshotVO(List<Object> detailValues, Long userId) {
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
            return normalizeContestRankSnapshotVO(toContestRankSnapshotVO(snapshot, parseUserVO(getStringValue(detailValues, 1), userId)), userId);
        } catch (Exception e) {
            log.warn("parse contest rank snapshot vo failed, userId={}", userId, e);
            return null;
        }
    }

    private ContestRankSnapshotVO normalizeContestRankSnapshotVO(ContestRankSnapshotVO contestRankSnapshotVO, Long userId) {
        if (contestRankSnapshotVO == null) {
            return buildEmptyContestRankSnapshotVO(null, userId, fetchUserVO(userId));
        }
        if (contestRankSnapshotVO.getUserVO() == null) {
            contestRankSnapshotVO.setUserVO(fetchUserVO(userId));
        }
        if (contestRankSnapshotVO.getContestRankSnapshot() == null) {
            contestRankSnapshotVO.setContestRankSnapshot(buildEmptySnapshot(null, userId));
        }
        ContestRankSnapshot snapshot = contestRankSnapshotVO.getContestRankSnapshot();
        if (snapshot.getUserId() == null) {
            snapshot.setUserId(userId);
        }
        if (snapshot.getAcceptedNum() == null) {
            snapshot.setAcceptedNum(0);
        }
        if (snapshot.getTotalTime() == null) {
            snapshot.setTotalTime(0L);
        }
        if (snapshot.getQuestionStatus() == null) {
            snapshot.setQuestionStatus(new HashMap<>());
        }
        if (snapshot.getQuestionLastSubmitMeta() == null) {
            snapshot.setQuestionLastSubmitMeta(new HashMap<>());
        }
        if (snapshot.getSnapshotTime() == null) {
            snapshot.setSnapshotTime(new Date());
        }
        recalculateMetrics(snapshot);
        return contestRankSnapshotVO;
    }

    private ContestRankSnapshotVO buildEmptyContestRankSnapshotVO(Long contestId, Long userId, UserVO userVO) {
        ContestRankSnapshotVO contestRankSnapshotVO = new ContestRankSnapshotVO();
        contestRankSnapshotVO.setUserVO(userVO);
        contestRankSnapshotVO.setContestRankSnapshot(buildEmptySnapshot(contestId, userId));
        return contestRankSnapshotVO;
    }

    private ContestRankSnapshot buildEmptySnapshot(Long contestId, Long userId) {
        ContestRankSnapshot snapshot = new ContestRankSnapshot();
        snapshot.setContestId(contestId);
        snapshot.setUserId(userId);
        snapshot.setAcceptedNum(0);
        snapshot.setTotalTime(0L);
        snapshot.setQuestionStatus(new HashMap<>());
        snapshot.setQuestionLastSubmitMeta(new HashMap<>());
        snapshot.setSnapshotTime(new Date());
        return snapshot;
    }

    private ContestRankSnapshotVO toContestRankSnapshotVO(ContestRankSnapshot snapshot, UserVO userVO) {
        ContestRankSnapshotVO contestRankSnapshotVO = new ContestRankSnapshotVO();
        contestRankSnapshotVO.setContestRankSnapshot(snapshot);
        contestRankSnapshotVO.setUserVO(userVO);
        Long userId = snapshot == null ? null : snapshot.getUserId();
        return normalizeContestRankSnapshotVO(contestRankSnapshotVO, userId);
    }

    /**
     * 深拷贝 ContestRankSnapshotVO，避免原对象被就地修改导致并发可见性问题。
     */
    private ContestRankSnapshotVO copyContestRankSnapshotVO(ContestRankSnapshotVO source) {
        ContestRankSnapshotVO normalizedSource = normalizeContestRankSnapshotVO(source, source == null || source.getContestRankSnapshot() == null
                ? null
                : source.getContestRankSnapshot().getUserId());
        ContestRankSnapshotVO target = new ContestRankSnapshotVO();
        target.setUserVO(normalizedSource.getUserVO());

        ContestRankSnapshot sourceSnapshot = normalizedSource.getContestRankSnapshot();
        ContestRankSnapshot targetSnapshot = new ContestRankSnapshot();
        targetSnapshot.setId(sourceSnapshot.getId());
        targetSnapshot.setContestId(sourceSnapshot.getContestId());
        targetSnapshot.setUserId(sourceSnapshot.getUserId());
        targetSnapshot.setAcceptedNum(sourceSnapshot.getAcceptedNum());
        targetSnapshot.setTotalTime(sourceSnapshot.getTotalTime());
        targetSnapshot.setQuestionStatus(copyQuestionStatus(sourceSnapshot.getQuestionStatus()));
        targetSnapshot.setQuestionLastSubmitMeta(copyQuestionLastSubmitMeta(sourceSnapshot.getQuestionLastSubmitMeta()));
        targetSnapshot.setSnapshotTime(sourceSnapshot.getSnapshotTime() == null ? null : new Date(sourceSnapshot.getSnapshotTime().getTime()));
        target.setContestRankSnapshot(targetSnapshot);
        return normalizeContestRankSnapshotVO(target, targetSnapshot.getUserId());
    }

    /**
     * 将一次提交应用到用户视图：更新该题状态 + 记录“该题最后一次提交元信息” + 重算指标。
     */
    private void applySubmission(ContestRankSnapshotVO contestRankSnapshotVO, QuestionSubmit questionSubmit, JudgeInfo judgeInfo) {
        ContestRankSnapshot snapshot = contestRankSnapshotVO.getContestRankSnapshot();
        Long questionId = questionSubmit.getQuestionId();
        snapshot.getQuestionStatus().put(questionId, cloneJudgeInfo(judgeInfo));

        ContestUserVO.QuestionLastSubmitMeta meta = new ContestUserVO.QuestionLastSubmitMeta();
        meta.setSubmitId(questionSubmit.getId());
        meta.setSubmitTime(extractSubmitTime(questionSubmit));
        snapshot.getQuestionLastSubmitMeta().put(questionId, meta);

        recalculateMetrics(snapshot);
    }

    /**
     * 基于题目状态重算 AC 数和总耗时。
     */
    private void recalculateMetrics(ContestRankSnapshot snapshot) {
        RankMetrics rankMetrics = calculateRankMetrics(snapshot.getQuestionStatus());
        snapshot.setAcceptedNum(rankMetrics.acceptedNum());
        snapshot.setTotalTime(rankMetrics.totalTime());
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
     * <p>对于同一道题，只有“更新”的提交会生效（防止久提交完处理，新的提交已经处理完毕）。
     */
    private boolean isSubmissionAffectRank(ContestRankSnapshot snapshot, QuestionSubmit questionSubmit) {
        ContestUserVO.QuestionLastSubmitMeta oldMeta =
                snapshot.getQuestionLastSubmitMeta().get(questionSubmit.getQuestionId());
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
    private void upsertRankData(Long contestId, Long userId, ContestRankSnapshotVO contestRankSnapshotVO) {
        ContestRankSnapshot snapshot = toSnapshot(contestId, userId, contestRankSnapshotVO);
        long expireSeconds = computeContestRankExpireSeconds(contestId);
        stringRedisTemplate.execute(
                UPSERT_RANK_SCRIPT,
                List.of(buildDetailKey(contestId, userId), buildRankZsetKey(contestId)),
                toSnapshotJson(snapshot),
                toUserVOJson(contestRankSnapshotVO.getUserVO()),
                String.valueOf(computeScore(contestRankSnapshotVO)),
                buildRankMember(userId),
                String.valueOf(expireSeconds)
        );
    }

    /**
     * 计算排行榜缓存 TTL：竞赛结束时间 + 1 天。
     */
    private long computeContestRankExpireSeconds(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return FALLBACK_EXPIRE_SECONDS;
        }
        Contest contest = contestService.getById(contestId);
        if (contest == null || contest.getEndTime() == null) {
            return FALLBACK_EXPIRE_SECONDS;
        }
        long expireAt = contest.getEndTime().getTime() + TimeUnit.DAYS.toMillis(1);
        long remainingMillis = expireAt - System.currentTimeMillis();
        long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMillis);
        return Math.max(MIN_EXPIRE_SECONDS, remainingSeconds);
    }

    /**
     * 计算 ZSet 分数。
     *
     * <p>公式：score = acNum * SCORE_TIME_RANGE + normalizedTime。
     * <p>normalizedTime = MAX_TOTAL_TIME - min(totalTime, MAX_TOTAL_TIME)。
     * <p>因此 AC 数越高分越高；AC 相同情况下总耗时越小，normalizedTime 越大，排名越靠前。
     */
    private long computeScore(ContestRankSnapshotVO contestRankSnapshotVO) {
        ContestRankSnapshot snapshot = contestRankSnapshotVO == null ? null : contestRankSnapshotVO.getContestRankSnapshot();
        int acceptedNum = snapshot == null || snapshot.getAcceptedNum() == null ? 0 : Math.max(snapshot.getAcceptedNum(), 0);
        long totalTime = snapshot == null || snapshot.getTotalTime() == null ? 0L : Math.max(snapshot.getTotalTime(), 0L);
        long normalizedTime = Math.max(0L, MAX_TOTAL_TIME - Math.min(totalTime, MAX_TOTAL_TIME));
        return acceptedNum * SCORE_TIME_RANGE + normalizedTime;
    }

    /**
     * 将内存态排行榜对象转换为数据库快照实体。
     */
    private ContestRankSnapshot toSnapshot(Long contestId, Long userId, ContestRankSnapshotVO contestRankSnapshotVO) {
        ContestRankSnapshotVO normalizedVO = normalizeContestRankSnapshotVO(contestRankSnapshotVO, userId);
        ContestRankSnapshot source = normalizedVO.getContestRankSnapshot();
        ContestRankSnapshot snapshot = new ContestRankSnapshot();
        snapshot.setId(source.getId());
        snapshot.setContestId(contestId == null ? source.getContestId() : contestId);
        snapshot.setUserId(userId == null ? source.getUserId() : userId);
        snapshot.setAcceptedNum(source.getAcceptedNum());
        snapshot.setTotalTime(source.getTotalTime());
        snapshot.setQuestionStatus(copyQuestionStatus(source.getQuestionStatus()));
        snapshot.setSnapshotTime(new Date());
        snapshot.setQuestionLastSubmitMeta(copyQuestionLastSubmitMeta(source.getQuestionLastSubmitMeta()));
        return snapshot;
    }

    /**
     * 组装 Redis Hash 写入内容。
     */
    private Map<String, String> buildDetailHashEntries(Long contestId, Long userId, ContestRankSnapshotVO contestRankSnapshotVO) {
        Map<String, String> hashEntries = new HashMap<>();
        hashEntries.put(DETAIL_FIELD_SNAPSHOT, toSnapshotJson(toSnapshot(contestId, userId, contestRankSnapshotVO)));
        hashEntries.put(DETAIL_FIELD_USER_VO, toUserVOJson(contestRankSnapshotVO.getUserVO()));
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

    private Map<Long, JudgeInfo> copyQuestionStatus(Map<Long, JudgeInfo> questionStatusMap) {
        Map<Long, JudgeInfo> result = new HashMap<>();
        if (questionStatusMap == null || questionStatusMap.isEmpty()) {
            return result;
        }
        for (Map.Entry<Long, JudgeInfo> entry : questionStatusMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey(), cloneJudgeInfo(entry.getValue()));
        }
        return result;
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
    private Page<ContestRankSnapshotVO> buildEmptyPage(long current, long size, long total) {
        Page<ContestRankSnapshotVO> page = new Page<>(current, size, total);
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

    /** 构造冷路径查库互斥锁键。 */
    private String buildSnapshotFallbackLockKey(Long contestId) {
        return SNAPSHOT_FALLBACK_LOCK_KEY_PREFIX + contestId;
    }

    /** 尝试获取互斥锁，失败返回 null。 */
    private String tryAcquireMutex(String lockKey) {
        if (StringUtils.isBlank(lockKey)) {
            return null;
        }
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                SNAPSHOT_FALLBACK_LOCK_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(locked) ? lockValue : null;
    }

    /** 安全释放互斥锁，仅删除自己持有的锁。 */
    private void releaseMutex(String lockKey, String lockValue) {
        if (StringUtils.isBlank(lockKey) || StringUtils.isBlank(lockValue)) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    RELEASE_MUTEX_LOCK_SCRIPT,
                    List.of(lockKey),
                    lockValue
            );
        } catch (Exception e) {
            log.warn("release mutex lock failed, lockKey={}", lockKey, e);
        }
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
