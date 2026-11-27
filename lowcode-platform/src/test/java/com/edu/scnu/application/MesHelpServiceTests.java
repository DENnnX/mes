package com.edu.scnu.application;

import com.edu.scnu.infrastructure.assistant.DeepSeekHelpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MesHelpServiceTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void returnsLocalGuidanceAndOnlyAuthorizedLinks() {
        MesHelpService help = serviceReturning("""
                {"topics":["report","apps","report"],"answer":"Untrusted answer","url":"https://example.com"}
                """);

        var answer = help.ask("工序报工怎么做？", "/runtime", List.of(),
                Map.of("/runtime", "全部授权应用", "/menu/mes_operation_execution_menu", "工序报工"));

        assertThat(answer.topicIds()).containsExactly("report");
        assertThat(answer.sections()).singleElement().satisfies(section -> {
            assertThat(section.title()).isEqualTo("工序开工与报工");
            assertThat(section.steps()).hasSize(2);
        });
        assertThat(answer.links()).containsExactly(
                new MesHelpService.PageLink("工序报工", "/menu/mes_operation_execution_menu"));
        assertThat(answer.message()).isEmpty();
    }

    @Test
    void refusesUnsupportedTopics() {
        MesHelpService help = serviceReturning("""
                {"topics":["weather","https://example.com"],"answer":"任意文字"}
                """);

        var answer = help.ask("明天的天气怎样？", "", List.of(), Map.of("/runtime", "全部授权应用"));

        assertThat(answer.message()).contains("超出了我的范围");
        assertThat(answer.sections()).isEmpty();
        assertThat(answer.links()).isEmpty();
        assertThat(answer.topicIds()).isEmpty();
    }

    @Test
    void explainsUnlinkedFeaturesWithoutExposingMenuPath() {
        MesHelpService help = serviceReturning("""
                {"topics":["finish"]}
                """);

        var answer = help.ask("怎么完工？", "", List.of("report"), Map.of("/runtime", "全部授权应用"));

        assertThat(answer.sections()).singleElement()
                .satisfies(section -> assertThat(section.summary()).contains("末道工序"));
        assertThat(answer.links()).isEmpty();
        assertThat(answer.message()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"你好, hello, 你好", "在吗, welcome, 我在", "你是谁, identity, 我是这个网页里的 MES 助手",
            "你叫什么, identity_short, 我是 MES 助手", "谢谢, thanks, 不客气"})
    void respondsConversationallyToShortOpeners(String question, String reply, String expected) {
        MesHelpService help = serviceReturning(json.writeValueAsString(Map.of("topics", List.of(), "reply", reply)));

        var answer = help.ask(question, "", List.of(), Map.of("/runtime", "全部授权应用"));

        assertThat(answer.message()).startsWith(expected);
        assertThat(answer.sections()).isEmpty();
        assertThat(answer.links()).isEmpty();
    }

    @Test
    void canGreetAndExplainInTheSameReply() {
        MesHelpService help = serviceReturning("""
                {"topics":["report"],"reply":"hello"}
                """);

        var answer = help.ask("你好，报工怎么做？", "", List.of(), Map.of("/menu/mes_operation_execution_menu", "工序报工"));

        assertThat(answer.message()).isEqualTo("你好！");
        assertThat(answer.sections()).hasSize(1);
        assertThat(answer.links()).hasSize(1);
    }

    private MesHelpService serviceReturning(String completion) {
        DeepSeekHelpClient client = new DeepSeekHelpClient(json, "test-key", "test-model",
                URI.create("http://localhost")) {
            @Override
            public JsonNode selectTopics(String instructions, String questionContext) {
                return json.readTree(completion);
            }
        };
        return new MesHelpService(client, json);
    }
}
