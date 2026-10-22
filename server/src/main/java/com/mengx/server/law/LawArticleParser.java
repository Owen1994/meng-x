package com.mengx.server.law;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LawRefBook/Laws markdown 解析器（数据源 github.com/LawRefBook/Laws）。
 * 实测格式规约：
 * 「# 法律全名」「# 编名」（大法分编多文件，目录下一编一 .md；普通法单文件无编），
 * INFO 段（通过/施行日期，&lt;!-- INFO END --&gt; 结束），正文：
 * 「## 第X章」章、「### 第X节」节、条文为普通段落「第X条 内容…」（续段归入前条）。
 * 一条 = 一个向量块（天然边界不切分）。
 */
@Component
public class LawArticleParser {

    private static final Logger log = LoggerFactory.getLogger(LawArticleParser.class);

    /** 特殊空白（LawRefBook 标题里用 EN SPACE / 全角空格，Java \s 不覆盖） */
    private static final String BLANK = "[\\s\\u00A0\\u2002\\u3000]";
    /** 条文行：第X条 开头（可带「之一」后缀），其余为同行内容 */
    private static final Pattern ARTICLE_LINE = Pattern.compile(
            "^(第[一二三四五六七八九十百千零〇两]+条[之一二三四五六七八九十]*)" + BLANK + "*(.*)$");
    /** H2 级结构标题（## 第X编 / 第X分编 / 第X章）：重置章路径 */
    private static final Pattern STRUCTURE_LINE = Pattern.compile(
            "^##" + BLANK + "+(第[一二三四五六七八九十百千零〇两\\d]+(编|分编|章).*)$");
    /** H3 级结构标题（### 第X章 / 第X节，层级因书而异）：编入章路径 */
    private static final Pattern SUBSTRUCTURE_LINE = Pattern.compile(
            "^###" + BLANK + "+(第[一二三四五六七八九十百千零〇两\\d]+(章|节).*)$");
    /** INFO 段内的施行日期（2021年1月1日 施行） */
    private static final Pattern EFFECTIVE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日" + BLANK + "*施行");
    /** 文件名尾日期（(2018-08-31)，作为无施行标记时的兜底） */
    private static final Pattern FILE_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /** 解析指定法律全部条文：lawType=statute 法条 / interpretation 司法解释 */
    public List<LawArticleEntity> parse(String dataDir, String lawType, String law) {
        Path root = Path.of(dataDir, "Laws");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("法条数据目录不存在：" + root + "（需 git clone LawRefBook/Laws 到该位置）");
        }
        List<Path> files = locate(root, lawType, law);
        if (files.isEmpty()) {
            throw new IllegalStateException("未找到目标数据文件：type=" + lawType + " law=" + law + "（在 " + root + " 下按名称匹配）");
        }
        log.info("解析目标文件 {} 个：{}", files.size(), files);
        List<LawArticleEntity> result = new ArrayList<>();
        String lawName = null;
        LocalDate effectiveDate = null;
        for (Path file : files) {
            ParsedFile pf = parseFile(file, law, lawType, lawName);
            if (lawName == null) {
                lawName = pf.lawName();
            }
            if (effectiveDate == null && pf.effectiveDate() != null) {
                effectiveDate = pf.effectiveDate();
            }
            result.addAll(pf.articles());
        }
        if (lawName == null) {
            lawName = law;
        }
        LocalDate date = effectiveDate;
        result.forEach(e -> e.setEffectiveDate(date));
        log.info("解析 {} 完成：{} 条，施行日期 {}", law, result.size(), date);
        return result;
    }

    /** 定位数据文件：大法优先取同名目录（民法典/刑法），刑法类只吃与目录同名的本体 .md；
     *  其余按文件名匹配；statute 排除司法解释目录，interpretation 仅在司法解释目录。 */
    private List<Path> locate(Path root, String lawType, String law) {
        // 大法目录：目录名 == law（如 民法典/、刑法/，规范化比对容错全称/简称）
        Path dir = null;
        try (Stream<Path> paths = Files.list(root)) {
            dir = paths.filter(Files::isDirectory)
                    .filter(p -> normalizeKey(p.getFileName().toString()).equals(normalizeKey(law)))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException("遍历数据根目录失败：" + root, e);
        }
        if (dir != null) {
            Path sameName = dir.resolve(dir.getFileName() + ".md");
            if (Files.exists(sameName)) {
                return List.of(sameName); // 刑法：只吃本体，修正案按名称单独导入
            }
            List<Path> parts = new ArrayList<>();
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().startsWith("_"))
                        .sorted().forEach(parts::add);
            } catch (Exception e) {
                throw new IllegalStateException("遍历大法目录失败：" + dir, e);
            }
            return parts; // 民法典：全部分编 + 附则
        }
        // 单文件匹配：精确名（去日期）优先取日期最新版本（公司法 2018/2023 等并存场景），
        // 无精确名再模糊 contains（名称最短优先）
        String lawKey = normalizeKey(law);
        List<Path> candidates;
        try (Stream<Path> paths = Files.walk(root)) {
            candidates = paths
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> normalizeKey(p.getFileName().toString()).contains(lawKey))
                    .filter(p -> {
                        boolean inInterpretation = p.toString().contains("司法解释");
                        return LawArticleEntity.TYPE_INTERPRETATION.equals(lawType) == inInterpretation;
                    })
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("遍历数据目录失败：" + root, e);
        }
        return candidates.stream()
                .min((a, b) -> {
                    boolean aExact = exactName(a).equals(lawKey);
                    boolean bExact = exactName(b).equals(lawKey);
                    if (aExact != bExact) {
                        return aExact ? -1 : 1; // 精确名优先
                    }
                    if (aExact) {
                        int byDate = fileDate(b).compareTo(fileDate(a)); // 同名多版本：日期最新优先
                        if (byDate != 0) {
                            return byDate;
                        }
                    }
                    int byLen = Integer.compare(a.getFileName().toString().length(), b.getFileName().toString().length());
                    if (byLen != 0) {
                        return byLen;
                    }
                    return fileDate(b).compareTo(fileDate(a));
                })
                .map(List::of)
                .orElse(List.of());
    }

    /** 文件名去日期与标点后的精确名（如「公司法(2023-12-29)」→「公司法」） */
    private static String exactName(Path p) {
        return normalizeKey(p.getFileName().toString().replaceFirst("[（(][^）)]*[）)]\\.(md|MD)$", ""));
    }

    /** 文件名尾日期（YYYY-MM-DD），无日期返回 LocalDate.MIN */
    private static LocalDate fileDate(Path p) {
        Matcher m = FILE_DATE.matcher(p.getFileName().toString());
        return m.find()
                ? LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))
                : LocalDate.MIN;
    }

    /** 标题/字段规范化：EN SPACE 等特殊空白转普通空格、连续空白合一 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\u00A0\\u2002\\u3000]", " ").replaceAll(" {2,}", " ").strip();
    }

    /** 匹配键规范化：只保留中文与字母数字（去书名号、括号、空格） */
    private static String normalizeKey(String s) {
        return s == null ? "" : s.replaceAll("[^一-龥a-zA-Z0-9]", "");
    }

    /** 单文件解析结果 */
    private record ParsedFile(String lawName, LocalDate effectiveDate, List<LawArticleEntity> articles) {}

    /** 逐行解析一个分编/单法文件 */
    private ParsedFile parseFile(Path file, String lawCode, String lawType, String knownLawName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取法条文件失败：" + file, e);
        }

        List<LawArticleEntity> articles = new ArrayList<>();
        String lawName = knownLawName;
        String book = "";
        String chapter = "";
        LocalDate effective = null;
        StringBuilder content = null;
        String articleNo = null;
        boolean infoEnded = false;
        int h1Seen = 0; // 文件内 H1 计数：第 1 个 = 法律全名，第 2 个 = 编名

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("<!--")) {
                if (line.contains("INFO END")) {
                    infoEnded = true; // 正文开始标记
                }
                continue; // 其他注释行跳过
            }
            if (line.startsWith("#")) {
                // 任何标题：收口上一条
                addIfPresent(articles, flush(lawCode, lawName, lawType, book, chapter, articleNo, content));
                articleNo = null;
                content = null;
                String text = normalize(line.replaceFirst("^#+", ""));
                if (text.isEmpty()) {
                    continue;
                }
                if (line.startsWith("# ") && !line.startsWith("## ")) {
                    if (++h1Seen == 1) {
                        if (lawName == null) {
                            lawName = text; // 第一个 H1 = 法律全名
                        }
                    } else if (h1Seen == 2 && book.isEmpty()) {
                        book = text; // 第二个 H1 = 编名（总则/物权编…）
                    }
                    continue;
                }
                Matcher structure = STRUCTURE_LINE.matcher(line);
                Matcher substructure = SUBSTRUCTURE_LINE.matcher(line);
                if (structure.matches()) {
                    chapter = normalize(structure.group(1)); // 编/分编/章（H2 级）：重置章路径
                } else if (substructure.matches()) {
                    String heading = normalize(substructure.group(1));
                    if ("节".equals(substructure.group(2))) {
                        chapter = chapter.isEmpty() ? heading : chapter + " / " + heading; // 节：并入章路径
                    } else {
                        chapter = heading; // H3 级的章（物权编分编下的章）：重置
                    }
                }
                continue;
            }
            if (!infoEnded) {
                // INFO 段：提取施行日期，遇到标记后进入正文
                Matcher eff = EFFECTIVE.matcher(line);
                if (eff.find()) {
                    effective = LocalDate.of(
                            Integer.parseInt(eff.group(1)), Integer.parseInt(eff.group(2)), Integer.parseInt(eff.group(3)));
                }
                if (line.contains("INFO END")) {
                    infoEnded = true;
                }
                continue;
            }
            Matcher articleM = ARTICLE_LINE.matcher(line);
            if (articleM.matches() && !articleM.group(1).isEmpty()) {
                addIfPresent(articles, flush(lawCode, lawName, lawType, book, chapter, articleNo, content));
                articleNo = articleM.group(1);
                content = new StringBuilder();
                String rest = articleM.group(2);
                if (!rest.isEmpty()) {
                    content.append(rest).append('\n');
                }
                continue;
            }
            if (articleNo != null) {
                content.append(line).append('\n');
            }
        }
        addIfPresent(articles, flush(lawCode, lawName, lawType, book, chapter, articleNo, content));

        if (effective == null) {
            Matcher fd = FILE_DATE.matcher(file.getFileName().toString());
            if (fd.find()) {
                effective = LocalDate.of(
                        Integer.parseInt(fd.group(1)), Integer.parseInt(fd.group(2)), Integer.parseInt(fd.group(3)));
            }
        }
        return new ParsedFile(lawName, effective, articles);
    }

    /** 收口结果入列表（空跳过） */
    private static void addIfPresent(List<LawArticleEntity> list, LawArticleEntity e) {
        if (e != null) {
            list.add(e);
        }
    }

    /** 收口一条条文（无内容时返回 null，不入列表） */
    private LawArticleEntity flush(
            String lawCode, String lawName, String lawType,
            String book, String chapter, String articleNo, StringBuilder content) {
        if (articleNo == null || content == null) {
            return null;
        }
        String text = content.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        String hash = sha256(lawCode + "|" + articleNo + "|" + text);
        return new LawArticleEntity(
                lawCode,
                lawName == null ? lawCode : lawName,
                lawType,
                book == null ? "" : book,
                chapter == null ? "" : chapter,
                articleNo, ChineseNumeral.parse(articleNo),
                "", text, hash);
    }

    /** SHA-256 内容哈希（Hex 小写），幂等锚点 */
    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
