package com.oriontv.plus;

import android.os.Build;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** 拼音首字母工具：西游记 -> xyj */
public class Pinyin {

    private static final Map<Character, Character> CACHE = new HashMap<>();
    private static android.icu.text.Transliterator trans;

    private Pinyin() {
    }

    /** 取标题的拼音首字母串（非汉字字符原样保留字母/数字） */
    public static String initials(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            Character c = initialOf(title.charAt(i));
            if (c != null) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static synchronized Character initialOf(char ch) {
        if (ch >= 'a' && ch <= 'z') {
            return ch;
        }
        if (ch >= 'A' && ch <= 'Z') {
            return Character.toLowerCase(ch);
        }
        if (ch >= '0' && ch <= '9') {
            return ch;
        }
        if (ch < 0x4E00 || ch > 0x9FFF) {
            return null;
        }
        if (CACHE.containsKey(ch)) {
            return CACHE.get(ch);
        }
        Character out = null;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                if (trans == null) {
                    trans = android.icu.text.Transliterator.getInstance("Han-Latin");
                }
                String s = trans.transliterate(String.valueOf(ch));
                String norm = Normalizer.normalize(s, Normalizer.Form.NFD);
                for (int i = 0; i < norm.length(); i++) {
                    char c = Character.toLowerCase(norm.charAt(i));
                    if (c >= 'a' && c <= 'z') {
                        out = c;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        CACHE.put(ch, out);
        return out;
    }

    /** 查询串是否是纯字母（用于判定走拼音首字母匹配） */
    public static boolean isLetters(String q) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        for (int i = 0; i < q.length(); i++) {
            char c = Character.toLowerCase(q.charAt(i));
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}
