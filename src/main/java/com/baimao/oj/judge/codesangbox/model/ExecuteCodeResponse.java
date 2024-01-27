package com.baimao.oj.judge.codesangbox.model;

import com.baimao.oj.model.dto.questionsubmit.JudgeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author baimao
 * @title ExecuteCodeResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    //一组输出用例
    private  List<String> output;

    //接口信息
    private String message;

    //执行状态
    private String status;

    //判题信息(花费的内存，时间)
    private JudgeInfo judgeInfo;
}
