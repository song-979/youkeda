package com.youkeda.project.wechatproject.bot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class SkillTools implements ToolService.ProjectTool {

    private static final Logger log = LoggerFactory.getLogger(SkillTools.class);

    private final List<SkillDef> skills;
    private final String skillsSummary;

    public SkillTools(@Value("${agent.tools.skills.path:data/skills}") String skillsPath) {
        this.skills = loadSkills(Path.of(skillsPath));
        this.skillsSummary = buildSummary();
        log.info("SkillTools initialized: {} skill(s) loaded from {}", skills.size(), skillsPath);
    }

    @Override
    public String category() {
        return "skill";
    }

    /**
     * Returns a condensed skill list to inject into the CHAT system prompt.
     */
    public String getSkillsSummary() {
        return skillsSummary;
    }

    @Tool(name = "search_skills",
          description = "检索可用的技能(Skill)。在调用任何领域工具之前，先调用此工具查找最适合当前用户需求的技能。技能会提供详细的执行指南，包括必须使用哪些工具、禁止使用哪些工具、以及具体的执行流程。")
    public String searchSkills(
            @ToolParam(description = "用户需求的描述，用于匹配技能，如'查火车票'、'导航去xx'、'天气怎么样'") String query) {

        if (skills.isEmpty()) {
            return "当前没有可用的技能。请根据你的判断直接使用领域工具处理用户需求。";
        }

        if (query == null || query.isBlank()) {
            return "请提供更具体的需求描述，以便匹配合适的技能。";
        }

        List<SkillMatch> matches = new ArrayList<>();
        for (SkillDef skill : skills) {
            int score = matchScore(query, skill);
            if (score > 0) {
                matches.add(new SkillMatch(skill, score));
            }
        }

        matches.sort(Comparator.comparingInt(SkillMatch::score).reversed());

        if (matches.isEmpty()) {
            return "没有找到与「" + query + "」匹配的技能。请根据你的判断直接使用领域工具处理用户需求。";
        }

        SkillDef best = matches.get(0).skill;
        log.info("skill matched: {} (score={}) for query: {}", best.name, matches.get(0).score, query);

        StringBuilder sb = new StringBuilder();
        sb.append("【已匹配技能】").append(best.name).append("\n\n");
        sb.append(best.body).append("\n");
        sb.append("---\n");
        sb.append("请严格按照上述技能指令执行。");

        if (matches.size() > 1) {
            sb.append("\n\n其他可能相关的技能：");
            for (int i = 1; i < Math.min(matches.size(), 3); i++) {
                sb.append("\n- ").append(matches.get(i).skill.name)
                  .append(": ").append(matches.get(i).skill.description);
            }
        }

        return sb.toString();
    }

    // --- internal ---

    private List<SkillDef> loadSkills(Path skillsDir) {
        List<SkillDef> result = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            log.warn("skills directory not found: {}", skillsDir.toAbsolutePath());
            return result;
        }

        try (Stream<Path> dirs = Files.list(skillsDir)) {
            List<Path> skillDirs = dirs.filter(Files::isDirectory).toList();
            for (Path dir : skillDirs) {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    log.debug("no SKILL.md in {}, skipping", dir.getFileName());
                    continue;
                }
                try {
                    SkillDef def = parseSkillFile(skillFile);
                    if (def != null) {
                        result.add(def);
                        log.info("loaded skill: {} from {}", def.name, skillFile);
                    }
                } catch (IOException e) {
                    log.warn("failed to read skill file: {}", skillFile, e);
                }
            }
        } catch (IOException e) {
            log.warn("failed to list skills directory: {}", skillsDir, e);
        }

        result.sort(Comparator.comparingInt(SkillDef::priority));
        return result;
    }

    private SkillDef parseSkillFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String normalized = content.replace("\r\n", "\n");

        Map<String, String> frontmatter = new LinkedHashMap<>();
        int bodyStart = 0;

        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---\n", 4);
            if (end > 0) {
                String fm = normalized.substring(4, end);
                for (String line : fm.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        frontmatter.put(key, value);
                    }
                }
                bodyStart = end + 5; // skip "\n---\n"
            }
        }

        String name = frontmatter.getOrDefault("name", "");
        String description = frontmatter.getOrDefault("description", "");
        int priority = parseInt(frontmatter.get("priority"), 100);
        String body = bodyStart < normalized.length()
                ? normalized.substring(bodyStart).trim()
                : "";

        if (name.isEmpty() || description.isEmpty()) {
            log.warn("skill file {} missing name or description, skipping", file);
            return null;
        }

        return new SkillDef(name, description, priority, body);
    }

    private String buildSummary() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 可用技能（Skills）\n\n");
        sb.append("在回复用户之前，先调用 search_skills 工具查找是否有匹配的技能。\n");
        sb.append("技能提供了执行指南——包括必须使用和禁止使用的工具。\n\n");
        sb.append("已注册的技能：\n");
        for (SkillDef skill : skills) {
            sb.append("- **").append(skill.name).append("**: ").append(skill.description).append("\n");
        }
        return sb.toString();
    }

    private int matchScore(String query, SkillDef skill) {
        String q = query.toLowerCase();
        int score = 0;

        // name 匹配权重最高
        if (q.contains(skill.name.toLowerCase())) {
            score += 100;
        }

        // description 中的关键词匹配
        String desc = skill.description.toLowerCase();
        for (String word : q.split("[\\s，,。！？]+")) {
            if (word.length() >= 2 && desc.contains(word)) {
                score += 10;
            }
            if (word.length() >= 2 && skill.name.contains(word)) {
                score += 20;
            }
        }

        // 反过来：skill description 中的词是否在 query 中
        for (String word : desc.split("[\\s，,。！？、]+")) {
            if (word.length() >= 2 && q.contains(word)) {
                score += 5;
            }
        }

        return score;
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // --- inner types ---

    private record SkillDef(String name, String description, int priority, String body) {}

    private record SkillMatch(SkillDef skill, int score) {}
}
