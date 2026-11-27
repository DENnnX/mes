package com.edu.scnu.infrastructure.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Component
public class DeepSeekHelpClient {
    private final HttpClient http;
    private final Semaphore slots = new Semaphore(4, true);
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final URI endpoint;

    public DeepSeekHelpClient(ObjectMapper json,
                              @Value("${lowcode.assistant.deepseek-key}") String apiKey,
                              @Value("${lowcode.assistant.deepseek-model}") String model,
                              @Value("${lowcode.assistant.deepseek-url:https://api.deepseek.com/chat/completions}") URI endpoint) {
        this.json = json;
        this.apiKey = apiKey.strip();
        this.model = model;
        this.endpoint = endpoint;
        this.http = this.apiKey.isEmpty() ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean isConfigured() {
        return http != null;
    }

    public JsonNode selectTopics(String instructions, String questionContext) {
        if (!isConfigured()) {
            throw new Unavailable("MES 助手尚未配置 DeepSeek，请联系系统管理员。");
        }
        if (!slots.tryAcquire()) {
            throw new Unavailable("助手正在处理较多请求，请稍后重试。");
        }
        try {
            var body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "system", "content", instructions),
                            Map.of("role", "user", "content", questionContext)),
                    "thinking", Map.of("type", "disabled"),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 160
            );
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new Unavailable("暂时无法连接 DeepSeek，请稍后重试或联系管理员。");
            }
            JsonNode choice = json.readTree(response.body()).path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            if (!"stop".equals(choice.path("finish_reason").asText()) || content.isBlank()) {
                throw new Unavailable("助手暂时没有收到完整结果，请重试。");
            }
            return json.readTree(content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("助手请求已中断，请重试。");
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw new Unavailable("暂时无法获取 DeepSeek 的回复，请稍后重试。");
        } finally {
            slots.release();
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
