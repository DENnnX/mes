package com.edu.scnu.application;

import com.edu.scnu.application.MesHelpCatalog.Topic;
import com.edu.scnu.infrastructure.assistant.DeepSeekHelpClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MesHelpService {
    private static final int MAX_TOPICS = 3;
    private static final Set<String> STUDIO_TOPICS = Set.of("overview", "apps", "models", "menus", "dict", "numbering");
    private static final String INSTRUCTIONS = """
            你为这个 MES 网页选择帮助主题和对话回应。
            输出一个 JSON 对象，例如 {"topics":["order","start"],"reply":""}。
            topics 只能包含目录中的 ID，只选与网站功能、操作步骤、权限或页面导航直接相关的条目，
            最多 3 个，按讲解顺序排列。不要输出解释文字、代码或链接。
            仅在追问依赖上下文时使用当前页面或上次话题。

            reply 请选择以下最贴切的一项，或留空：
            hello：你好、您好、早上好等普通招呼。
            welcome：嗨、在吗等比较随意的开场。
            identity：你是谁、你是什么助手。identity_short：问名字或是否是 MES 助手。
            thanks：谢谢等简单致谢。clarify：想问 MES，但问题太模糊，需要对方说清具体功能。
            out_of_scope：站外问题、要求查实际业务数据、执行操作、修改规则，或超出目录的请求。
            单独的招呼、身份询问、致谢或站外问题，topics 留空。例如 {"topics":[],"reply":"hello"}。
            招呼后直接询问 MES 功能时，可以同时选招呼和帮助主题。
            仅提到 MES 不足以把无关问题判定为站内问题。

            可用目录：
            """;
    private final DeepSeekHelpClient deepSeek;
    private final ObjectMapper json;

    public MesHelpService(DeepSeekHelpClient deepSeek, ObjectMapper json) {
        this.deepSeek = deepSeek;
        this.json = json;
    }

    public boolean isConfigured() {
        return deepSeek.isConfigured();
    }

    public Reply ask(String question, String currentPath, List<String> previousTopicIds, Map<String, String> pages) {
        Map<String, Topic> topics = new LinkedHashMap<>();
        for (Topic topic : MesHelpCatalog.TOPICS) {
            if (STUDIO_TOPICS.contains(topic.id()) && !pages.containsKey("/studio/overview")) continue;
            if ("authorization".equals(topic.id())
                    && !pages.containsKey("/studio/iam") && !pages.containsKey("/tenant-admin")) continue;
            topics.put(topic.id(), topic);
        }
        List<String> currentTopics = pages.containsKey(currentPath)
                ? topics.values().stream().filter(t -> t.paths().contains(currentPath)).map(Topic::id).toList()
                : List.of();
        List<String> previousTopics = previousTopicIds.stream()
                .filter(topics::containsKey).distinct().limit(MAX_TOPICS).toList();
        String catalog = topics.values().stream()
                .map(topic -> topic.id() + " | " + topic.title())
                .collect(Collectors.joining("\n"));
        String context = json.writeValueAsString(Map.of(
                "question", question, "currentTopics", currentTopics, "previousTopics", previousTopics));
        JsonNode decision = deepSeek.selectTopics(INSTRUCTIONS + catalog, context);
        JsonNode selected = decision.path("topics");
        if (!selected.isArray()) {
            throw new DeepSeekHelpClient.Unavailable("助手暂时没有收到有效结果，请重试。");
        }

        // Only locally maintained help entries and session-authorized paths can leave this service.
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : selected) {
            if (item.isTextual() && topics.containsKey(item.asText())) ids.add(item.asText());
            if (ids.size() == MAX_TOPICS) break;
        }
        String reply = decision.path("reply").asText("");
        if (ids.isEmpty() || "out_of_scope".equals(reply)) {
            return new Reply(conversationReply(reply), List.of(), List.of(), List.of());
        }
        Set<String> relatedPaths = new LinkedHashSet<>();
        List<AnswerSection> sections = ids.stream().map(topics::get).map(topic -> {
            relatedPaths.addAll(topic.paths());
            return new AnswerSection(topic.title(), topic.summary(), topic.steps());
        }).toList();
        List<PageLink> links = relatedPaths.stream().filter(pages::containsKey)
                .map(path -> new PageLink(pages.get(path), path)).limit(4).toList();
        String message = switch (reply) {
            case "hello" -> "你好！";
            case "welcome" -> "嗨！";
            case "thanks" -> "不客气！";
            default -> "";
        };
        return new Reply(message, sections, links, List.copyOf(ids));
    }

    private String conversationReply(String reply) {
        return switch (reply) {
            case "hello" -> "你好！想了解 MES 里的哪个功能？";
            case "welcome" -> "我在。有什么 MES 操作想问我？";
            case "identity" -> "我是这个网页里的 MES 助手，可以帮你弄清功能怎么用、操作在哪个页面。";
            case "identity_short" -> "我是 MES 助手。";
            case "thanks" -> "不客气！有其他操作问题，随时问我。";
            case "clarify" -> "你想了解的是哪个功能？告诉我页面名或你正在做的操作就行。";
            default -> "这个问题超出了我的范围。如果你有 MES 操作上的疑问，我可以帮你。";
        };
    }

    public record Reply(String message, List<AnswerSection> sections, List<PageLink> links, List<String> topicIds) {}
    public record AnswerSection(String title, String summary, List<String> steps) {}
    public record PageLink(String label, String path) {}
}
