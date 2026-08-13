package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class MpvAudioPassthrough {

    private static final String[] ALL_FORMATS = {"ac3", "eac3", "dts", "dts-hd", "truehd"};
    private static final String[] DOLBY_FORMATS = {"ac3", "eac3", "truehd"};

    private MpvAudioPassthrough() {
    }

    static String getSupportedFormats(Context context, boolean audio, boolean dolby) {
        if (!audio && !dolby) return "";
        String[] requested = audio ? ALL_FORMATS : DOLBY_FORMATS;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return TextUtils.join(",", requested);
        Set<String> supported = getDeviceFormats(context);
        if (supported.isEmpty()) return "";
        Set<String> enabled = new LinkedHashSet<>();
        for (String format : requested) if (supported.contains(format)) enabled.add(format);
        return TextUtils.join(",", enabled);
    }

    static boolean isFailureLog(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String value = text.toLowerCase(Locale.ROOT);
        boolean audio = value.contains("audiotrack") || value.contains("audio output") || value.contains("ao/") || value.contains("spdif") || value.contains("passthrough");
        boolean failure = value.contains("fail") || value.contains("error") || value.contains("unable") || value.contains("could not") || value.contains("couldn't") || value.contains("unsupported") || value.contains("invalid");
        return audio && failure;
    }

    private static Set<String> getDeviceFormats(Context context) {
        Set<String> formats = new LinkedHashSet<>();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return formats;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!isPassthroughDevice(device)) continue;
            for (int encoding : device.getEncodings()) {
                String format = toMpvFormat(encoding);
                if (!TextUtils.isEmpty(format)) formats.add(format);
            }
        }
        return formats;
    }

    private static boolean isPassthroughDevice(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC || type == getDeviceType("TYPE_HDMI_EARC");
    }

    private static int getDeviceType(String name) {
        try {
            return AudioDeviceInfo.class.getField(name).getInt(null);
        } catch (Throwable e) {
            return -1;
        }
    }

    @Nullable
    private static String toMpvFormat(int encoding) {
        if (encoding == AudioFormat.ENCODING_AC3) return "ac3";
        if (encoding == AudioFormat.ENCODING_E_AC3 || encoding == getEncoding("ENCODING_E_AC3_JOC")) return "eac3";
        if (encoding == getEncoding("ENCODING_DTS")) return "dts";
        if (encoding == getEncoding("ENCODING_DTS_HD")) return "dts-hd";
        if (encoding == getEncoding("ENCODING_DOLBY_TRUEHD")) return "truehd";
        return null;
    }

    private static int getEncoding(String name) {
        try {
            return AudioFormat.class.getField(name).getInt(null);
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }
}
