package com.baimao.oj.model.dto.contest;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户-竞赛报名
 */
@Data
public class RegistrationsAddRequest implements Serializable {

    /**
     * 竞赛ID（关联contest.id）
     */
    private Long contestId;


    private static final long serialVersionUID = 1L;
}