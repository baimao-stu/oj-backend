package com.baimao.oj.judge.codesangbox;

import com.baimao.oj.judge.codesangbox.impl.RemoteCodeSandbox;
import com.baimao.oj.judge.codesangbox.impl.SampleCodeSandbox;
import com.baimao.oj.judge.codesangbox.impl.ThirdPartyCodeSandbox;

/**
 * @author baimao
 * @title CodeSandboxFactory
 * 代码沙箱工厂（简单工厂）
 */
public class CodeSandboxFactory {

    //根据传入的字符串返回对应的代码沙箱
    public static CodeSandbox newInstance(String type) {
        switch (type) {
            case "remote":
                return new RemoteCodeSandbox();
            case "thirdParty":
                return new ThirdPartyCodeSandbox();
            default:
                return new SampleCodeSandbox();
        }
    }
}
