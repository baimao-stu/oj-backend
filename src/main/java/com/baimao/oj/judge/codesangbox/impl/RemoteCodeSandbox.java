package com.baimao.oj.judge.codesangbox.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;

/**
 * @author baimao
 * @title RemoteCodeSandbox
 * 远程代码沙箱
 */
public class RemoteCodeSandbox implements CodeSandbox {

    //接口调用鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";
    private static final String AUTH_REQUEST_SECRET = "secret";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("远程代码沙箱");
        String url = "http://localhost:8888/executeCode";
//        String url = "http://192.168.220.130:8888/executeCode";
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        String body = HttpUtil.createPost(url)
                .header(AUTH_REQUEST_HEADER,AUTH_REQUEST_SECRET)
                .body(json)
                .execute()
                .body();
        if(StrUtil.isEmpty(body)) {
            throw new BusinessException(ErrorCode.API_ERROR,"execute remote sandbox error");
        }
        return JSONUtil.toBean(body,ExecuteCodeResponse.class);
    }
}
