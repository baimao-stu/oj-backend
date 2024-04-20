package com.baimao.oj.model.dto.contest;

import com.baimao.oj.common.PageRequest;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/**
 * @author baimao
 * @title ContestUserVOQueryRequest
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContestUserVOQueryRequest extends PageRequest implements Serializable {

    /**
     * 比赛id
     */
    private Long contestId;

}
