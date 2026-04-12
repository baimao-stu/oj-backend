package com.baimao.oj.service.impl;

import com.baimao.oj.mapper.ContestRankSnapshotMapper;
import com.baimao.oj.model.entity.ContestRankSnapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步刷比赛排行榜快照表。
 */
@Service
@Slf4j
// @Deprecated
public class ContestRankSnapshotSyncService {

    @Resource
    private ContestRankSnapshotMapper contestRankSnapshotMapper;

    @Async
    public void syncSnapshotAsync(ContestRankSnapshot snapshot) {
        if (snapshot == null || snapshot.getContestId() == null || snapshot.getUserId() == null) {
            return;
        }
        try {
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
        } catch (Exception e) {
            log.warn("sync contest rank snapshot failed, contestId={}, userId={}",
                    snapshot.getContestId(), snapshot.getUserId(), e);
        }
    }
}
