package com.oriontv.plus;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置影视名词库：安装即有、离线可用。
 * <p>
 * assets/title_dict.txt 按拼音首字母分桶（"#j" 开头的一段），每行都是
 * {@code 首字母<TAB>片名}，全部去重，随 APK 一起压缩。首次用到某个字母时
 * 才读该桶并缓存，避免启动开销和整库常驻内存。
 * <p>
 * 词库只负责把字母查询还原成中文片名，不作为搜索结果展示。
 */
public final class BuiltinTitles {

    private static final String ASSET = "title_dict.txt";
    /** 已加载的首字母桶 */
    private static final Map<Character, List<String[]>> BUCKETS = new HashMap<>();
    /** 词库文件里存在的首字母（不存在时不再重复读文件） */
    private static final Map<Character, Boolean> EMPTY = new HashMap<>();

    private BuiltinTitles() {
    }

    /** 按首字母找候选片名：完全相等 > 前缀 > 包含，与本地观察索引规则一致 */
    public static List<String> match(Context ctx, String key, int limit) {
        List<String> out = new ArrayList<>();
        if (ctx == null || key == null || key.isEmpty() || limit <= 0) {
            return out;
        }
        String k = key.toLowerCase(Locale.US);
        List<String[]> bucket = bucket(ctx, k.charAt(0));
        List<String> exact = new ArrayList<>();
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String[] e : bucket) {
            String ini = e[0];
            if (ini == null || ini.isEmpty()) {
                continue;
            }
            if (ini.equals(k)) {
                exact.add(e[1]);
            } else if (ini.startsWith(k)) {
                prefix.add(e[1]);
            } else if (k.length() >= 3 && ini.contains(k)) {
                // 3 个字母以内不做「包含」匹配，避免一两个字母带出大堆无关片名
                contains.add(e[1]);
            }
        }
        for (List<String> group : java.util.Arrays.asList(exact, prefix, contains)) {
            for (String t : group) {
                if (out.size() >= limit) {
                    return out;
                }
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static List<String[]> bucket(Context ctx, char letter) {
        synchronized (BUCKETS) {
            List<String[]> cached = BUCKETS.get(letter);
            if (cached != null) {
                return cached;
            }
            if (Boolean.TRUE.equals(EMPTY.get(letter))) {
                return new ArrayList<>();
            }
            List<String[]> list = new ArrayList<>();
            boolean hit = false;
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(
                        ctx.getAssets().open(ASSET), "UTF-8"));
                String line;
                boolean inBucket = false;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) {
                        inBucket = line.length() > 1 && line.charAt(1) == letter;
                        if (inBucket) {
                            hit = true;
                        }
                        continue;
                    }
                    if (!inBucket) {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab >= line.length() - 1) {
                        continue;
                    }
                    list.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            BUCKETS.put(letter, list);
            EMPTY.put(letter, hit);
            return list;
        }
    }
}
