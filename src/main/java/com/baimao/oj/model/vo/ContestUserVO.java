package com.baimao.oj.model.vo;

import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 比赛用户视图（脱敏）
 */
@Data
public class ContestUserVO implements Serializable, Comparable<ContestUserVO> {

    /**
     * 用户信息
     */
    private UserVO userVO;

    /**
     * 总耗时(ms)
     */
    private Long allTime;

    /**
     * ac 的题目数
     */
    private Integer acNum;

    /**
     * 用户在这场比赛的提交记录情况（同题只保留最后一次提交结果）
     */
    private Map<Long, JudgeInfo> questionSubmitStatus;

    /**
     * 每道题最后一次提交的元数据，仅用于预热 Redis 缓存
     */
    @JsonIgnore
    private Map<Long, QuestionLastSubmitMeta> questionLastSubmitMeta;

    private static final long serialVersionUID = 1L;

    @Override
    public int compareTo(ContestUserVO o) {
        if (o.acNum.equals(this.acNum)) {
            return Long.compare(this.allTime, o.allTime);
        }
        return o.acNum - this.acNum;
    }

    @Data
    public static class QuestionLastSubmitMeta implements Serializable {

        /**
         * 最后一次提交记录的主键，用于同毫秒时间戳下继续比较先后。
         */
        private Long submitId;

        /**
         * 最后一次提交时间戳，单位毫秒。
         */
        private Long submitTime;

        private static final long serialVersionUID = 1L;
    }
}
