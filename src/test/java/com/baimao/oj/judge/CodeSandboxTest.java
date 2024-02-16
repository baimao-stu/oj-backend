package com.baimao.oj.judge;

import com.baimao.oj.judge.codesangbox.CodeSandbox;
import com.baimao.oj.judge.codesangbox.CodeSandboxFactory;
import com.baimao.oj.judge.codesangbox.CodeSandboxProxy;
import com.baimao.oj.judge.codesangbox.impl.RemoteCodeSandbox;
import com.baimao.oj.judge.codesangbox.impl.SampleCodeSandbox;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeRequest;
import com.baimao.oj.judge.codesangbox.model.ExecuteCodeResponse;
import com.baimao.oj.model.enums.QuestionSubmitLanguageEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.List;

/**
 * @author baimao
 * @title CodeSandboxTest
 */
@SpringBootTest
public class CodeSandboxTest {

    @Value("${codesandbox.type:sample}")
    private String type;

    @Test
    public void executeCode(){
        CodeSandbox codeSandbox = new RemoteCodeSandbox();
        String code = "int main(){}";
        List<String> input = Arrays.asList("1 2","3 4");
        String language = QuestionSubmitLanguageEnum.JAVA.getValue();
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code(code)
                .input(input)
                .language(language)
                .build();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(request);
        Assertions.assertNotNull(executeCodeResponse);
    }

    @Test
    public void executeCodeByValue(){
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        String code = "int main(){}";
        List<String> input = Arrays.asList("1 2","3 4");
        String language = QuestionSubmitLanguageEnum.JAVA.getValue();
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code(code)
                .input(input)
                .language(language)
                .build();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(request);
        Assertions.assertNotNull(executeCodeResponse);
    }

    @Test
    public void executeCodeByProxy(){
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        String code = "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        int a = Integer.parseInt(args[0]);\n" +
                "        int b = Integer.parseInt(args[1]);\n" +
                "        System.out.println(\"结果：\" + (a + b));\n" +
                "        System.out.println(\"===============\");\n" +
                "    }\n" +
                "}";
        List<String> input = Arrays.asList("1 2","3 4");
        String language = QuestionSubmitLanguageEnum.JAVA.getValue();
        ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                .code(code)
                .input(input)
                .language(language)
                .build();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(request);
        System.out.println(executeCodeResponse);
        Assertions.assertNotNull(executeCodeResponse);
    }

}
