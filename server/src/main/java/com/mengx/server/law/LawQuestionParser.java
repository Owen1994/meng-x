package com.mengx.server.law;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 法考历年真题解析器（数据源：本地真题库 markdown，客观题目录多年代格式混合）。
 * 格式实录：
 * - 2002-2013：分卷单文件、题干选项跨行硬换行。答案来源三种：2002-2006 尾部「参 考 答 案」
 *   一行一题「1、D」；2007-2008 尾部标题行（2008 为单独「答案」二字）一行多题「1. D  2. B」；
 *   2009-2013 逐题内嵌「【答案】C +【解析】…」（解析续行收进内嵌缓冲，编号枚举不误开新题）；
 * - 2014-2017：DOCX 转换干净行级格式（三卷合并单文件，偶有段落错位）；
 * - 2018-2022 回忆版：OCR 文本（2018-2021 双栏乱序垃圾，靠文件级质量门禁整文件跳过）；
 * - 2023-2024：OCR 段式（题干+选项同段、标点混杂、页码噪声）；
 * - 2025：干净行级格式，卷一/卷二分文件，独立解析文件。
 * 统一策略：行级状态机（题号行开题、其余行并入缓冲）→ 缓冲全文按序定位 A→B→C→D
 * 选项标记（从左往右，错位年份保住前面的完整题）→ 结构校验（题干长度/中文占比/四选项齐全）
 * → 独立解析文件 / 自带答案区 / 内嵌【答案】按（卷序, 题号）配对答案与解析。
 */
@Component
public class LawQuestionParser {

    private static final Logger log = LoggerFactory.getLogger(LawQuestionParser.class);

    /** 卷标记行（试卷一 / 试  卷  一）：限短行防题干误触 */
    private static final Pattern PAPER_LINE = Pattern.compile("试\\s*卷\\s*([一二三])");
    /** 分节行：一、单项选择题。… / 二、多项选择题… / 三、不定项选择题… → 题型 */
    private static final Pattern SECTION_LINE = Pattern.compile("^\\s*[一二三四五六]\\s*[、.．]\\s*(单项选择题|多项选择题|不定项)");
    /** 答案条目（单行一题、多题混排、空格分隔多字母均可）：1、D / 1. D / 32、B C D / 51. ABD */
    private static final Pattern ANSWER_ITEM = Pattern.compile("(\\d{1,3})\\s*[、.．]\\s*((?:[A-Da-d]\\s*){1,4})");
    /** 范围式答案行（2008 卷一）：1---5、CDCCD / 6---10、CBBCD → 按题号区间逐题展开 */
    private static final Pattern ANSWER_RANGE = Pattern.compile("(\\d{1,3})\\s*[-—–~]+\\s*(\\d{1,3})\\s*[、.．]\\s*((?:[A-Da-d]\\s*){1,10})");
    /** 内嵌答案行（2009-2013 逐题跟答）：【答案】C / 【答案】AD（本题答案：AD，原答案为 ABCD） */
    private static final Pattern INLINE_ANSWER = Pattern.compile("^【?\\s*答\\s*案\\s*】?\\s*((?:[A-Da-d]\\s*){1,4})");
    /** 题号行：1. 题干 / 2 题干（OCR 无标点）/ 3。题干 / 100. 题干 */
    private static final Pattern Q_LINE = Pattern.compile("^(\\d{1,3})\\s*(?:[.、．。:：,，]\\s*|\\s+)(.+)$");
    /** 段首噪声中的内嵌题号（OCR 分节说明与题 1 同段：…共50分c 1. 以人民为中心…） */
    private static final Pattern INNER_Q = Pattern.compile("(\\d{1,3})\\s*[.、．。](?=\\s*[\\u4e00-\\u9fa5A-Za-z])");
    /** 选项标记：独立 A-D 字母 + 分隔标点或空白（A./B。/c。/C:/D 2004） */
    private static final Pattern OPT_MARKER = Pattern.compile("(?<![A-Za-z0-9])([A-Da-d])(?=[.、．。:：,，;、]|\\s)");
    /** 纯页码行（OCR 页脚数字） */
    private static final Pattern PAGE_NO = Pattern.compile("^\\d{1,3}$");
    /** 文件名年份前缀 */
    private static final Pattern YEAR_PREFIX = Pattern.compile("^(\\d{4})年客观题");

    private static final String[] LETTERS = {"A", "B", "C", "D"};

    private final ObjectMapper objectMapper;

    public LawQuestionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 答案 + 解析配对结果 */
    private record Ans(String answer, String analysis) {}

    /** 缓冲提取结果：题干 + 四选项 */
    private record Extracted(String stem, String[] options) {}

    /** 解析中的原始题（卷序、题号、题型快照、全文缓冲） */
    private record Raw(int paper, int no, String sectionType, StringBuilder text) {}

    /** 内嵌答案收集状态：当前题（卷序,题号）+ 已提取答案 + 解析续行缓冲 */
    private record Own(int paper, int no, String answer, StringBuilder analysis) {}

    /** 解析指定年份全部客观题：客观题/{year}年客观题*.md + 客观题-答案解析/{year}年客观题*.md */
    public List<LawQuestionEntity> parse(String questionDir, String year) {
        Path qDir = Path.of(questionDir, "客观题");
        Path aDir = Path.of(questionDir, "客观题-答案解析");
        if (!Files.isDirectory(qDir)) {
            throw new IllegalStateException("真题库目录不存在：" + qDir + "（需配置 mengx.law.question-dir 指向真题库根目录）");
        }
        List<Path> qFiles = filesOfYear(qDir, year);
        if (qFiles.isEmpty()) {
            throw new IllegalStateException("未找到 " + year + " 年真题文件（在 " + qDir + " 下按「" + year + "年客观题」前缀匹配）");
        }
        List<Path> aFiles = Files.isDirectory(aDir) ? filesOfYear(aDir, year) : List.of();
        log.info("真题 {} 解析开始：题目文件 {} 个，解析文件 {} 个", year, qFiles.size(), aFiles.size());

        // 年份级答案配对表：key = (卷序, 题号)
        Map<Long, Ans> ansMap = new HashMap<>();
        for (Path f : aFiles) {
            parseAnalysisFile(f, ansMap);
        }
        log.info("真题 {} 答案配对表：{} 条", year, ansMap.size());

        List<LawQuestionEntity> result = new ArrayList<>();
        Set<String> seenHash = new HashSet<>();
        int dropped = 0;
        int answered = 0;
        int dup = 0;
        for (Path f : qFiles) {
            int[] st = parseQuestionFile(f, year, ansMap, result, seenHash);
            dropped += st[0];
            answered += st[1];
            dup += st[2];
        }
        log.info("真题 {} 解析完成：入库 {} 题（配对答案 {} 题，残缺丢弃 {} 题，重复跳过 {} 题）",
                year, result.size(), answered, dropped, dup);
        if (result.isEmpty()) {
            log.warn("真题 {} 未解析出任何完整题目（乱序损坏文件已按质量门禁跳过），该年份按 0 题处理", year);
        }
        return result;
    }

    /** 真题库可用年份（客观题目录文件名提取，升序） */
    public List<String> availableYears(String questionDir) {
        Path qDir = Path.of(questionDir, "客观题");
        if (!Files.isDirectory(qDir)) {
            throw new IllegalStateException("真题库目录不存在：" + qDir + "（需配置 mengx.law.question-dir 指向真题库根目录）");
        }
        Set<String> years = new HashSet<>();
        try (Stream<Path> ps = Files.list(qDir)) {
            ps.map(p -> p.getFileName().toString()).forEach(name -> {
                Matcher m = YEAR_PREFIX.matcher(name);
                if (m.find() && name.endsWith(".md")) {
                    years.add(m.group(1));
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("遍历真题目录失败：" + qDir, e);
        }
        return years.stream().sorted().toList();
    }

    /** 单个题目文件状态机：题目缓冲收集 → 组装实体（含自带答案区） */
    private int[] parseQuestionFile(Path file, String year, Map<Long, Ans> ansMap,
            List<LawQuestionEntity> out, Set<String> seenHash) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取真题文件失败：" + file, e);
        }

        int paper = paperFromName(file.getFileName().toString());
        String sectionType = null;
        boolean answerArea = false;
        Map<Long, Ans> ownAnss = new HashMap<>(); // 文件自带答案/解析（尾部答案区 + 逐题内嵌）
        Own pendingOwn = null;                    // 内嵌【答案】后的解析收集状态
        List<Raw> raws = new ArrayList<>();
        Raw cur = null;

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("---")) {
                continue;
            }
            if (PAGE_NO.matcher(line).matches()) {
                continue;
            }
            // 广告/水印短行（题库来源站点推广文本，常嵌在题干中间）：直接丢弃
            if (line.length() <= 80 && (line.contains("www.") || line.contains("http")
                    || line.contains("加群") || line.contains("法考资料"))) {
                continue;
            }
            // 自带答案区标记：仅在已解析到题目后生效（防文件头标题「…及参考答案」误触）；
            // 2002-2006 答案区标题为「参 考 答 案」（字符间带空格），2008 为单独「答案」二字，须压缩空白后比对
            String compactLine = line.replace(" ", "").replace("\u3000", "");
            if (!raws.isEmpty() && !answerArea && compactLine.length() <= 30
                    && (compactLine.contains("参考答案") || compactLine.equals("答案"))) {
                cur = null;
                pendingOwn = flushOwn(ownAnss, pendingOwn);
                answerArea = true;
                continue;
            }
            if (answerArea) {
                // 范围式优先（1---5、CDCCD），命中则不再按单题模式扫该行（防止「5、CDCC」误配）
                Matcher ar = ANSWER_RANGE.matcher(line);
                boolean ranged = false;
                while (ar.find()) {
                    int from = Integer.parseInt(ar.group(1));
                    int to = Integer.parseInt(ar.group(2));
                    String letters = ar.group(3).replaceAll("\\s+", "").toUpperCase();
                    if (to >= from && to - from + 1 == letters.length()) {
                        for (int i = 0; i < letters.length(); i++) {
                            ownAnss.put(key(paper, from + i), new Ans(String.valueOf(letters.charAt(i)), null));
                        }
                        ranged = true;
                    }
                }
                if (!ranged) {
                    Matcher am = ANSWER_ITEM.matcher(line);
                    while (am.find()) {
                        ownAnss.put(key(paper, Integer.parseInt(am.group(1))),
                                new Ans(am.group(2).replaceAll("\\s+", "").toUpperCase(), null));
                    }
                }
                continue;
            }
            Matcher pm = PAPER_LINE.matcher(line);
            if (pm.find() && line.length() <= 60) {
                pendingOwn = flushOwn(ownAnss, pendingOwn);
                cur = null;
                paper = paperIdx(pm.group(1));
                sectionType = null;
                continue;
            }
            Matcher sm = SECTION_LINE.matcher(line);
            if (sm.find()) {
                pendingOwn = flushOwn(ownAnss, pendingOwn);
                cur = null;
                sectionType = mapType(sm.group(1));
                continue;
            }
            Matcher qm = Q_LINE.matcher(line);
            if (qm.matches()) {
                int qNo = Integer.parseInt(qm.group(1));
                // 内嵌解析期间的编号行（解析枚举如「1.某某」）：题号未前进则并入解析，不误开新题
                if (pendingOwn != null && cur != null && qNo <= cur.no()) {
                    pendingOwn.analysis().append(line);
                    continue;
                }
                pendingOwn = flushOwn(ownAnss, pendingOwn);
                cur = new Raw(paper, qNo, sectionType, new StringBuilder(qm.group(2)));
                raws.add(cur);
                continue;
            }
            // 逐题内嵌答案行（2009-2013）：挂到当前题，其后各行转为解析收集
            Matcher ia = INLINE_ANSWER.matcher(line);
            if (ia.find() && cur != null) {
                pendingOwn = flushOwn(ownAnss, pendingOwn);
                pendingOwn = new Own(cur.paper(), cur.no(),
                        ia.group(1).replaceAll("\\s+", "").toUpperCase(), new StringBuilder());
                continue;
            }
            if (pendingOwn != null) {
                // 解析正文续行（剥离「解析」标记头）
                String t = line.replaceFirst("^【?\\s*解\\s*析\\s*】?[:：]?", "").strip();
                if (!t.isEmpty()) {
                    pendingOwn.analysis().append(t);
                }
                continue;
            }
            if (cur != null) {
                cur.text().append(line);
                continue;
            }
            // 段首噪声含内嵌题号（OCR 分节说明与题 1 同段）：切出题号段重新处理，前段噪声丢弃
            Matcher iq = INNER_Q.matcher(line);
            if (iq.find()) {
                Matcher qm2 = Q_LINE.matcher(line.substring(iq.start()));
                if (qm2.matches()) {
                    cur = new Raw(paper, Integer.parseInt(qm2.group(1)), sectionType,
                            new StringBuilder(qm2.group(2)));
                    raws.add(cur);
                }
            }
        }
        flushOwn(ownAnss, pendingOwn);

        // 文件级质量门禁：缓冲题量过少且无任何自带答案 → 乱序损坏文件（回忆版双栏 OCR），整文件跳过
        if (raws.size() < 30 && ownAnss.isEmpty()) {
            log.warn("{} 缓冲仅 {} 题且无自带答案，判定为乱序损坏文件，整文件跳过",
                    file.getFileName(), raws.size());
            return new int[]{0, 0, 0};
        }

        int dropped = 0;
        int answered = 0;
        int dup = 0;
        int beforeOut = out.size();
        for (Raw r : raws) {
            Extracted e = extract(r.text().toString());
            if (e == null) {
                dropped++;
                continue;
            }
            long k = key(r.paper(), r.no());
            Ans ans = ansMap.get(k);   // 独立解析文件优先
            Ans own = ownAnss.get(k);  // 文件自带（尾部答案区 / 内嵌【答案】【解析】）
            String answer = ans != null && ans.answer() != null ? ans.answer()
                    : (own != null ? own.answer() : null);
            String analysis = ans != null && ans.analysis() != null ? ans.analysis()
                    : (own != null ? own.analysis() : null);
            if (answer != null) {
                answered++;
            }
            String type = r.sectionType() != null ? r.sectionType()
                    : (answer != null && answer.length() > 1 ? "多选" : "单选");
            try {
                List<Map<String, String>> options = new ArrayList<>(4);
                for (int i = 0; i < 4; i++) {
                    Map<String, String> o = new LinkedHashMap<>();
                    o.put("key", LETTERS[i]);
                    o.put("text", e.options()[i]);
                    options.add(o);
                }
                String optionsJson = objectMapper.writeValueAsString(options);
                String hash = sha256(year + "|" + e.stem() + "|" + optionsJson);
                if (!seenHash.add(hash)) {
                    dup++;
                    continue;
                }
                out.add(new LawQuestionEntity(
                        LawQuestionEntity.SOURCE_ORIGIN, Integer.parseInt(year), "综合", type, null,
                        new String[0], e.stem(), optionsJson, answer, analysis, hash));
            } catch (Exception ex) {
                log.warn("真题实体组装失败 {} 卷{} 第{}题：{}", year, r.paper() + 1, r.no(), ex.getMessage());
                dropped++;
            }
        }
        log.info("{} 解析：缓冲 {} 题 → 入库 {} 题（丢弃 {}），自带答案/解析 {} 条",
                file.getFileName(), raws.size(), out.size() - beforeOut, dropped, ownAnss.size());
        return new int[]{dropped, answered, dup};
    }

    /** 单个解析文件：逐题缓冲，提取「答案/解析」进年份配对表（key 同题目侧） */
    private void parseAnalysisFile(Path file, Map<Long, Ans> ansMap) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取解析文件失败：" + file, e);
        }
        int paper = paperFromName(file.getFileName().toString());
        int no = -1;
        StringBuilder buf = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("---")) {
                continue;
            }
            if (PAGE_NO.matcher(line).matches()) {
                continue;
            }
            Matcher pm = PAPER_LINE.matcher(line);
            if (pm.find() && line.length() <= 60) {
                flushAns(ansMap, paper, no, buf);
                paper = paperIdx(pm.group(1));
                no = -1;
                continue;
            }
            Matcher qm = Q_LINE.matcher(line);
            if (qm.matches()) {
                flushAns(ansMap, paper, no, buf);
                no = Integer.parseInt(qm.group(1));
                buf.setLength(0);
                buf.append(qm.group(2));
                continue;
            }
            if (no >= 0) {
                buf.append(line);
            }
        }
        flushAns(ansMap, paper, no, buf);
        log.info("{} 解析配对完成，累计 {} 条", file.getFileName(), ansMap.size());
    }

    private void flushAns(Map<Long, Ans> map, int paper, int no, StringBuilder buf) {
        if (no < 0) {
            return;
        }
        String text = buf.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        String answer = extractAnswer(text);
        String analysis = extractAnalysis(text);
        if (answer == null && analysis == null) {
            return;
        }
        map.put(key(paper, no), new Ans(answer, analysis));
    }

    /** 内嵌答案收口：写入文件级自带配对表（解析超长截断），返回 null 表示收集状态已清空 */
    private static Own flushOwn(Map<Long, Ans> map, Own p) {
        if (p != null) {
            String text = p.analysis().toString().strip();
            String analysis = text.isEmpty() ? null : (text.length() > 600 ? text.substring(0, 600) : text);
            map.put(key(p.paper(), p.no()), new Ans(p.answer(), analysis));
        }
        return null;
    }

    /** 缓冲全文提取题干与四选项：从左往右按序定位 A→B→C→D 标记，结构不完整返回 null */
    private static Extracted extract(String text) {
        int[] pos = new int[4];
        Arrays.fill(pos, -1);
        Matcher m = OPT_MARKER.matcher(text);
        int want = 0;
        while (m.find()) {
            int letter = Character.toUpperCase(m.group(1).charAt(0)) - 'A';
            if (letter == want) {
                pos[letter] = m.start();
                if (++want == 4) {
                    break;
                }
            }
        }
        if (want < 4) {
            return null; // 四选项未按序找齐（乱序/残缺题，自然过滤）
        }
        String stem = text.substring(0, pos[0]).strip();
        String[] options = new String[4];
        for (int i = 0; i < 4; i++) {
            int end = i < 3 ? pos[i + 1] : text.length();
            options[i] = stripOption(text.substring(pos[i], end));
        }
        if (stem.length() < 8 || chineseRatio(stem) < 0.3) {
            return null; // 题干过短或乱码（中文占比过低）
        }
        for (String o : options) {
            if (o.isEmpty()) {
                return null;
            }
        }
        return new Extracted(stem, options);
    }

    /** 去掉选项字母标记与分隔符 */
    private static String stripOption(String raw) {
        return raw.substring(1).replaceFirst("^[.、．。:：,，;\\s]+", "").strip();
    }

    /** 提取答案：① 缓冲首字母（2014+ 解析格式「1.B【解析】…」，题号已被行匹配消费）
     *  ② 「答案」标记后短窗口内提取（容错 OCR 括号/冒号/数字噪声） */
    private static String extractAnswer(String text) {
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            char c0 = stripped.charAt(0);
            if ((c0 >= 'A' && c0 <= 'D') || (c0 >= 'a' && c0 <= 'd')) {
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (; i < Math.min(4, stripped.length()); i++) {
                    char ch = stripped.charAt(i);
                    if ((ch >= 'A' && ch <= 'D') || (ch >= 'a' && ch <= 'd')) {
                        sb.append(Character.toUpperCase(ch));
                    } else if (sb.length() > 0 && (ch == ' ' || ch == '\u3000')) {
                        continue; // 字母间空白
                    } else {
                        break;
                    }
                }
                // 字母串后须为解析标记/括号/标点（B【解析】/ C  【解析】/ D(解析)），防正文误判
                if (sb.length() > 0 && i < stripped.length()
                        && "【[（(。.．:：、,，;； ".indexOf(stripped.charAt(i)) >= 0) {
                    return sb.toString();
                }
            }
        }
        int idx = text.indexOf("答案");
        if (idx < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int end = Math.min(idx + 14, text.length());
        for (int i = idx + 2; i < end && sb.length() < 4; i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'D') || (c >= 'a' && c <= 'd')) {
                sb.append(Character.toUpperCase(c));
            } else if (sb.length() == 0 && "）)]:：、.．,，1l(（ ".indexOf(c) >= 0) {
                continue; // 前导噪声跳过
            } else if (sb.length() > 0 && (c == ' ' || c == '\u3000')) {
                continue; // 字母间空白跳过
            } else {
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 「解析」标记后取解析正文（截 600 字） */
    private static String extractAnalysis(String text) {
        int idx = text.indexOf("解析");
        if (idx < 0) {
            return null;
        }
        String rest = text.substring(idx + 2)
                .replaceFirst("^[\\s:：、,，.．()（）\\[\\]{}「」〔〕【】]*", "")
                .strip();
        if (rest.isEmpty()) {
            return null;
        }
        return rest.length() > 600 ? rest.substring(0, 600) : rest;
    }

    /** 分节标题 → 题型 */
    private static String mapType(String section) {
        return switch (section) {
            case "单项选择题" -> "单选";
            case "多项选择题" -> "多选";
            default -> "不定项";
        };
    }

    /** 文件名卷别 → 卷序（卷一=0/卷二=1/卷三=2，无卷别=0） */
    private static int paperFromName(String name) {
        if (name.contains("卷三")) {
            return 2;
        }
        return name.contains("卷二") ? 1 : 0;
    }

    /** 卷标记中文数字 → 卷序 */
    private static int paperIdx(String cn) {
        return switch (cn) {
            case "一" -> 0;
            case "二" -> 1;
            default -> 2;
        };
    }

    /** (卷序, 题号) → 配对键 */
    private static long key(int paper, int no) {
        return paper * 10000L + no;
    }

    /** 中文字符占比（防 OCR 乱码题干） */
    private static double chineseRatio(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        long cjk = s.chars().filter(c -> c >= 0x4E00 && c <= 0x9FA5).count();
        return (double) cjk / s.length();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Long.toHexString(s.hashCode()) + Long.toHexString(System.nanoTime());
        }
    }

    /** 目录下指定年份前缀的 md 文件（文件名升序，卷一在前卷二在后） */
    private static List<Path> filesOfYear(Path dir, String year) {
        String prefix = year + "年客观题";
        try (Stream<Path> ps = Files.list(dir)) {
            return ps.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("遍历真题目录失败：" + dir, e);
        }
    }
}
