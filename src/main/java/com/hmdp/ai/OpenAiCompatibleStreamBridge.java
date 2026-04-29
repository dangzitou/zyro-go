package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OpenAiCompatibleStreamBridge {

    private final ObjectProvider<OpenAiApi> openAiApiProvider;
    private final Environment environment;

    public OpenAiCompatibleStreamBridge(ObjectProvider<OpenAiApi> openAiApiProvider,
                                        Environment environment) {
        this.openAiApiProvider = openAiApiProvider;
        this.environment = environment;
    }

    public boolean available() {
        return openAiApiProvider.getIfAvailable() != null;
    }

    public String chat(String systemPrompt, String userPrompt) {
        OpenAiApi api = openAiApiProvider.getIfAvailable();
        if (api == null) {
            throw new IllegalStateException("OpenAiApi is not available for stream bridge.");
        }
        List<OpenAiApi.ChatCompletionMessage> messages = new ArrayList<OpenAiApi.ChatCompletionMessage>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(new OpenAiApi.ChatCompletionMessage(
                    systemPrompt,
                    OpenAiApi.ChatCompletionMessage.Role.SYSTEM
            ));
        }
        messages.add(new OpenAiApi.ChatCompletionMessage(
                StrUtil.blankToDefault(userPrompt, ""),
                OpenAiApi.ChatCompletionMessage.Role.USER
        ));

        OpenAiApi.ChatCompletionRequest request = new OpenAiApi.ChatCompletionRequest(
                messages,
                resolveModel(),
                resolveTemperature(),
                true
        );

        StringBuilder answer = new StringBuilder();
        api.chatCompletionStream(request).doOnNext(chunk -> {
            if (chunk == null || chunk.choices() == null) {
                return;
            }
            for (OpenAiApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
                if (choice == null || choice.delta() == null) {
                    continue;
                }
                String content = choice.delta().content();
                if (StrUtil.isNotBlank(content)) {
                    answer.append(content);
                }
            }
        }).blockLast();
        return answer.toString();
    }

    private String resolveModel() {
        String model = environment.getProperty("spring.ai.openai.chat.options.model");
        if (StrUtil.isBlank(model)) {
            model = environment.getProperty("AI_MODEL");
        }
        return StrUtil.blankToDefault(model, "gpt-5.4-mini");
    }

    private Double resolveTemperature() {
        String temperature = environment.getProperty("spring.ai.openai.chat.options.temperature");
        if (StrUtil.isBlank(temperature)) {
            temperature = environment.getProperty("AI_TEMPERATURE");
        }
        if (StrUtil.isBlank(temperature)) {
            return 0.2D;
        }
        try {
            return Double.parseDouble(temperature);
        } catch (Exception e) {
            log.debug("Failed to parse AI temperature {}, fallback to 0.2", temperature, e);
            return 0.2D;
        }
    }
}
