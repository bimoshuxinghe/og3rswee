package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;

public class Pinyin {

    private static HanyuPinyinOutputFormat format;

    private static HanyuPinyinOutputFormat getFormat() {
        if (format == null) {
            format = new HanyuPinyinOutputFormat();
            format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        }
        return format;
    }

    public static String getInitials(String input) {
        if (TextUtils.isEmpty(input)) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, getFormat());
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        sb.append(pinyinArray[0].charAt(0));
                    }
                } catch (Exception e) {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase();
    }
}
