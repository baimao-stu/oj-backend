package com.baimao.oj.model.vo;

import com.baimao.oj.model.entity.ContestRankSnapshot;
import lombok.Data;

import java.io.Serializable;

/**
 * 比赛排行榜快照视图
 */
@Data
public class ContestRankSnapshotVO implements Serializable {

    /**
     * 排行快照
     */
    private ContestRankSnapshot contestRankSnapshot;

    /**
     * 用户脱敏信息
     */
    private UserVO userVO;

    private static final long serialVersionUID = 1L;
}
