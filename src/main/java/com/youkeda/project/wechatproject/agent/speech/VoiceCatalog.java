package com.youkeda.project.wechatproject.agent.speech;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Qwen3-TTS-Flash 系统音色目录。
 * <p>
 * 音色来源：阿里云百炼 Qwen3-TTS-Flash 模型支持的精品音色库。
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/qwen-tts-api">Qwen-TTS API</a>
 */
public class VoiceCatalog {

    private final Map<String, VoiceProfile> profiles = new LinkedHashMap<>();

    public VoiceCatalog() {
        // ===== Standard Bilingual Voices (标准双语) =====
        register(new VoiceProfile(
                "Cherry", "Cherry / 芊悦", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "bright, positive, friendly", "阳光积极、亲切自然小姐姐",
                List.of("happy", "casual", "cheerful", "neutral"),
                List.of("zh-CN", "en-US")));

        register(new VoiceProfile(
                "Serena", "Serena / 苏瑶", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "gentle, soft, warm", "温柔小姐姐",
                List.of("sad", "calm", "comforting", "casual"),
                List.of("zh-CN", "en-US")));

        register(new VoiceProfile(
                "Ethan", "Ethan / 晨煦", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "warm, sunny, standard Mandarin", "标准普通话，阳光温暖",
                List.of("happy", "formal", "casual", "storytelling"),
                List.of("zh-CN", "en-US")));

        register(new VoiceProfile(
                "Chelsie", "Chelsie / 千雪", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "anime-style, virtual companion", "二次元虚拟女友",
                List.of("happy", "casual"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Momo", "Momo / 茉兔", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "playful, cute, coquettish", "撒娇搞怪，逗你开心",
                List.of("happy", "excited", "casual"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Vivian", "Vivian / 十三", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "sassy, cute, slightly grumpy", "拽拽的、可爱的小暴躁",
                List.of("excited", "casual"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Moon", "Moon / 月白", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "cool, handsome, frank", "率性帅气",
                List.of("casual", "storytelling"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Maia", "Maia / 四月", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "intellectual, gentle", "知性与温柔的碰撞",
                List.of("formal", "calm", "storytelling"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Kai", "Kai / 凯", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "soothing, relaxing", "耳朵的一场SPA",
                List.of("calm", "comforting", "sad"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Nofish", "Nofish / 不吃鱼", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "friendly, casual, designer vibe", "不会翘舌音的设计师",
                List.of("casual", "happy"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Bella", "Bella / 萌宝", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "loli, cute, lively", "喝酒不打醉拳的小萝莉",
                List.of("happy", "excited", "casual"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Jennifer", "Jennifer / 詹妮弗", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "premium, cinematic, American English", "品牌级、电影质感美语女声",
                List.of("formal", "storytelling"),
                List.of("en-US")));

        register(new VoiceProfile(
                "Ryan", "Ryan / 甜茶", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "rhythmic, dramatic, immersive", "节奏拉满，戏感炸裂",
                List.of("excited", "storytelling", "happy"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Katerina", "Katerina / 卡捷琳娜", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "regal, elegant, rhythmic", "御姐音色，韵律回味十足",
                List.of("formal", "storytelling", "calm"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Aiden", "Aiden / 艾登", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "friendly, chef-like, American English", "精通厨艺的美语大男孩",
                List.of("casual", "happy"),
                List.of("en-US")));

        register(new VoiceProfile(
                "Eldric Sage", "Eldric Sage / 沧明子", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "calm, wise, elderly", "沉稳睿智的老者",
                List.of("calm", "storytelling", "formal"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Mia", "Mia / 乖小妹", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "gentle, obedient, soft", "温顺如春水，乖巧如初雪",
                List.of("sad", "calm", "comforting"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Mochi", "Mochi / 沙小弥", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "smart, precocious kid", "聪明伶俐的小大人",
                List.of("happy", "casual", "storytelling"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Bellona", "Bellona / 燕铮莺", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "loud, vibrant, vivid", "声音洪亮，人物鲜活",
                List.of("excited", "storytelling"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Vincent", "Vincent / 田叔", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "raspy, smoky, unique", "独特沙哑烟嗓",
                List.of("calm", "storytelling", "sad"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Bunny", "Bunny / 萌小姬", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "cute, moe, little girl", "萌属性爆棚的小萝莉",
                List.of("happy", "excited", "casual"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Neil", "Neil / 阿闻", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "professional, news anchor", "专业新闻主持人",
                List.of("formal", "calm"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Elias", "Elias / 墨讲师", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "rigorous, teaching style", "严谨教学风格",
                List.of("formal", "calm"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Arthur", "Arthur / 徐大爷", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "rustic, time-worn, sincere", "被岁月浸泡的质朴嗓音",
                List.of("calm", "storytelling", "sad"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Nini", "Nini / 邻家妹妹", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "soft, sticky, sweet", "糯米糍一样又软又黏",
                List.of("sad", "comforting", "casual", "calm"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Seren", "Seren / 小婉", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "gentle, soothing, sleep-aid", "温和舒缓，助眠声线",
                List.of("sad", "calm", "comforting"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Pip", "Pip / 顽屁小孩", VoiceGender.MALE, VoiceCategory.STANDARD_BILINGUAL,
                "naughty, childlike, playful", "调皮捣蛋充满童真",
                List.of("happy", "excited", "storytelling"),
                List.of("zh-CN")));

        register(new VoiceProfile(
                "Stella", "Stella / 少女阿月", VoiceGender.FEMALE, VoiceCategory.STANDARD_BILINGUAL,
                "sweet, dreamy, young girl", "甜美迷糊少女音",
                List.of("happy", "casual", "sad"),
                List.of("zh-CN")));

        // ===== Chinese Dialect Voices (中文方言) =====
        register(new VoiceProfile(
                "Dylan", "Dylan / 北京-晓东", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Beijing dialect, local youth", "胡同里长大的少年",
                List.of("casual", "happy", "storytelling"),
                List.of("zh-CN", "beijing")));

        register(new VoiceProfile(
                "Jada", "Jada / 上海-阿珍", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                "Shanghai/Wu dialect, spirited", "风风火火的沪上阿姐",
                List.of("casual", "happy", "excited"),
                List.of("zh-CN", "shanghai")));

        register(new VoiceProfile(
                "Li", "Li / 南京-老李", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Nanjing dialect, patient teacher", "耐心的瑜伽老师",
                List.of("calm", "formal", "storytelling"),
                List.of("zh-CN", "nanjing")));

        register(new VoiceProfile(
                "Sunny", "Sunny / 四川-晴儿", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                "Sichuan dialect, sweet", "甜到心里的川妹子",
                List.of("happy", "casual", "comforting"),
                List.of("zh-CN", "sichuan")));

        register(new VoiceProfile(
                "Eric", "Eric / 四川-程川", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Sichuan dialect, lively", "跳脱市井的成都男子",
                List.of("excited", "casual", "storytelling"),
                List.of("zh-CN", "sichuan")));

        register(new VoiceProfile(
                "Marcus", "Marcus / 陕西-秦川", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Shaanxi dialect, straightforward", "面宽话短，老陕的味道",
                List.of("casual", "storytelling", "calm"),
                List.of("zh-CN", "shaanxi")));

        register(new VoiceProfile(
                "Roy", "Roy / 闽南-阿杰", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Minnan/Taiwanese, humorous", "诙谐直爽的台湾哥仔",
                List.of("happy", "casual", "storytelling"),
                List.of("zh-CN", "minnan")));

        register(new VoiceProfile(
                "Peter", "Peter / 天津-李彼得", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Tianjin dialect, comedic", "天津相声，专业捧哏",
                List.of("happy", "excited", "casual"),
                List.of("zh-CN", "tianjin")));

        register(new VoiceProfile(
                "Rocky", "Rocky / 粤语-阿强", VoiceGender.MALE, VoiceCategory.CHINESE_DIALECT,
                "Cantonese, humorous", "幽默风趣粤语男，在线陪聊",
                List.of("happy", "casual", "storytelling"),
                List.of("zh-CN", "cantonese")));

        register(new VoiceProfile(
                "Kiki", "Kiki / 粤语-阿清", VoiceGender.FEMALE, VoiceCategory.CHINESE_DIALECT,
                "Cantonese, sweet Hong Kong girl", "甜美的港妹闺蜜",
                List.of("happy", "casual", "comforting"),
                List.of("zh-CN", "cantonese")));

        // ===== Foreign Language Character Voices (外语角色) =====
        register(new VoiceProfile(
                "Bodega", "Bodega / 博德加", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "passionate, Spanish uncle", "热情的西班牙大叔",
                List.of("excited", "happy", "storytelling"),
                List.of("es")));

        register(new VoiceProfile(
                "Sonrisa", "Sonrisa / 索尼莎", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                "warm, cheerful, Latin American", "热情开朗的拉美大姐",
                List.of("happy", "excited", "casual"),
                List.of("es")));

        register(new VoiceProfile(
                "Alek", "Alek / 阿列克", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "cool, warm, Russian", "战斗民族的冷与暖",
                List.of("calm", "formal", "storytelling"),
                List.of("ru")));

        register(new VoiceProfile(
                "Dolce", "Dolce / 多尔切", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "lazy, relaxed, Italian", "慵懒的意大利大叔",
                List.of("calm", "casual", "storytelling"),
                List.of("it")));

        register(new VoiceProfile(
                "Sohee", "Sohee / 素熙", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                "gentle, cheerful, Korean", "温柔开朗的韩国欧尼",
                List.of("happy", "casual", "comforting"),
                List.of("ko")));

        register(new VoiceProfile(
                "Ono Anna", "Ono Anna / 小野杏", VoiceGender.FEMALE, VoiceCategory.FOREIGN_CHARACTER,
                "playful, mischievous, Japanese", "鬼灵精怪的青梅竹马",
                List.of("happy", "excited", "casual"),
                List.of("ja")));

        register(new VoiceProfile(
                "Lenn", "Lenn / 莱恩", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "cool, post-punk, German", "西装也听后朋克的德国青年",
                List.of("calm", "casual", "formal"),
                List.of("de")));

        register(new VoiceProfile(
                "Emilien", "Emilien / 埃米尔安", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "romantic, French", "浪漫的法国大哥哥",
                List.of("calm", "storytelling", "comforting"),
                List.of("fr")));

        register(new VoiceProfile(
                "Andre", "Andre / 安德雷", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "magnetic, deep, steady", "声音磁性，沉稳男生",
                List.of("calm", "formal", "storytelling"),
                List.of("zh-CN", "en-US")));

        register(new VoiceProfile(
                "Radio Gol", "Radio Gol / 拉迪奥·戈尔", VoiceGender.MALE, VoiceCategory.FOREIGN_CHARACTER,
                "football commentator, poetic", "足球诗人解说",
                List.of("excited", "storytelling", "happy"),
                List.of("es")));
    }

    private void register(VoiceProfile profile) {
        profiles.put(profile.voiceId(), profile);
    }

    public VoiceProfile get(String voiceId) {
        return profiles.get(voiceId);
    }

    public Optional<VoiceProfile> findByMood(String mood) {
        if (mood == null || mood.isBlank()) {
            return Optional.empty();
        }
        String moodLower = mood.toLowerCase().trim();

        VoiceProfile best = null;
        for (VoiceProfile profile : profiles.values()) {
            for (String suitableMood : profile.suitableMoods()) {
                if (moodLower.equals(suitableMood)) {
                    return Optional.of(profile);
                }
                if (moodLower.contains(suitableMood) && best == null) {
                    best = profile;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public VoiceProfile defaultVoice() {
        return profiles.get("Cherry");
    }

    /**
     * Generates a compact voice list for injection into the orchestrator system prompt.
     */
    public String generateVoicePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Available TTS Voices (Qwen3-TTS-Flash) ---\n");
        sb.append("Default: Cherry (bright, friendly female — good for most situations)\n\n");

        sb.append("Standard female voices:\n");
        for (VoiceProfile v : byCategory(VoiceCategory.STANDARD_BILINGUAL)) {
            if (v.gender() == VoiceGender.FEMALE && !v.voiceId().equals("Cherry")) {
                sb.append("  ").append(voiceLine(v)).append("\n");
            }
        }

        sb.append("\nStandard male voices:\n");
        for (VoiceProfile v : byCategory(VoiceCategory.STANDARD_BILINGUAL)) {
            if (v.gender() == VoiceGender.MALE) {
                sb.append("  ").append(voiceLine(v)).append("\n");
            }
        }

        sb.append("\nChinese dialect voices:\n");
        for (VoiceProfile v : byCategory(VoiceCategory.CHINESE_DIALECT)) {
            sb.append("  ").append(voiceLine(v)).append("\n");
        }

        sb.append("\nForeign language character voices:\n");
        for (VoiceProfile v : byCategory(VoiceCategory.FOREIGN_CHARACTER)) {
            sb.append("  ").append(voiceLine(v)).append("\n");
        }

        sb.append("\n").append(generateMoodMappingPrompt());

        return sb.toString();
    }

    /**
     * Generates mood-to-voice mapping guidance for the orchestrator.
     */
    public String generateMoodMappingPrompt() {
        return """
                When selecting a voice for SPEECH_GEN:
                - User sounds sad/upset → pick a warm, gentle voice (Serena, Mia, Seren, Nini, Kai)
                - User sounds happy/excited → pick a bright, energetic voice (Cherry, Momo, Ethan, Ryan, Bunny)
                - User wants storytelling → pick a rich, narrative voice (Eldric Sage, Ethan, Vincent, Arthur, Katerina)
                - User sounds calm/neutral → use default or whatever fits content
                - User requests Cantonese/粤语/广东话 → use Rocky (humorous male) or Kiki (sweet female)
                - User requests other Chinese dialects → pick from dialect category (Dylan/Beijing, Jada/Shanghai, Sunny/Sichuan, etc.)
                - User requests foreign language → pick from foreign character category
                - User requests a specific voice name → use that exact voice ID
                - User requests a gender → filter by gender
                - User requests "read aloud" or "读一下" → consider the content mood when selecting

                Set the voice via "voice" parameter on SPEECH_GEN tasks.
                """;
    }

    private String voiceLine(VoiceProfile v) {
        return String.format("%s: %s, %s (%s)", v.voiceId(), v.tone(), v.gender().name().toLowerCase(), v.displayName());
    }

    private List<VoiceProfile> byCategory(VoiceCategory category) {
        List<VoiceProfile> result = new ArrayList<>();
        for (VoiceProfile v : profiles.values()) {
            if (v.category() == category) {
                result.add(v);
            }
        }
        return result;
    }

    public Map<String, VoiceProfile> all() {
        return Map.copyOf(profiles);
    }
}
