package com.baimao.oj.model.vo;

import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.entity.QuestionSubmit;
import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 比赛用户视图（脱敏）

 */
@Data
public class ContestUserVO implements Serializable,Comparable<ContestUserVO> {

    /**
     * 用户信息
     */
    private UserVO userVO;

    /**
     * 总耗时(ms)
     */
    private Long allTime;

    /**
     * ac的题目数
     */
    private Integer acNum;

    /**
     * 用户在这场比赛的提交记录情况（有ac以ac为准，没ac已最后一次提交为准）
     */
    private Map<Long, JudgeInfo> questionSubmitStatus;

    private static final long serialVersionUID = 1L;

    /**
     * 先按ac题目数降序排序，再按时间升序排序
     * @param o the object to be compared.
     * @return
     */
    @Override
    public int compareTo(@NotNull ContestUserVO o) {
        if(o.acNum.equals(this.acNum)) {
            return (int)(this.allTime - o.allTime);
        }
        return o.acNum - this.acNum;
    }
}