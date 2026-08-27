package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MPVLib {

    static {
        // 参考播放器 mpv v0.41.0-dev（libplacebo + Vulkan + gpu-next，支持 Dolby Vision P5）
        System.loadLibrary("mpv");
        // 参考播放器 JNI 封装（libplayer.so，导出全部 Java_is_xyz_mpv_MPVLib_* 方法）
        System.loadLibrary("player");
    }

    private static final List<EventObserver> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final List<LogObserver> LOG_OBSERVERS = new CopyOnWriteArrayList<>();

    private MPVLib() {
    }

    public static native void create(Context appctx);

    public static native void init();

    public static native int destroy();

    public static native void attachSurface(Surface surface);

    public static native void detachSurface();

    public static native void attachOsdSurface(Surface surface);

    public static native void detachOsdSurface();

    public static native void replaceSurface(Surface surface);

    public static native void replaceOsdSurface(Surface surface);

    public static native void command(String[] cmd);

    public static native int enqueueCommand(long userData, String[] cmd);

    public static native int setOptionString(String name, String value);

    public static native Bitmap grabThumbnail(int dimension);

    public static native Integer getPropertyInt(String property);

    public static native void setPropertyInt(String property, int value);

    public static native Double getPropertyDouble(String property);

    public static native void setPropertyDouble(String property, double value);

    public static native Boolean getPropertyBoolean(String property);

    public static native void setPropertyBoolean(String property, boolean value);

    public static native String getPropertyString(String property);

    public static native void setPropertyString(String property, String value);

    public static native byte[] getPropertyByteArray(String property);

    public static native int observeProperty(String property, int format);

    public static void addObserver(EventObserver observer) {
        OBSERVERS.add(observer);
    }

    public static void removeObserver(EventObserver observer) {
        OBSERVERS.remove(observer);
    }

    public static void addLogObserver(LogObserver observer) {
        LOG_OBSERVERS.add(observer);
    }

    public static void removeLogObserver(LogObserver observer) {
        LOG_OBSERVERS.remove(observer);
    }

    public static void eventProperty(String property) {
        for (EventObserver observer : OBSERVERS) observer.eventProperty(property);
    }

    public static void eventProperty(String property, long value) {
        for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
    }

    public static void eventProperty(String property, boolean value) {
        for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
    }

    public static void eventProperty(String property, String value) {
        for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
    }

    public static void eventProperty(String property, double value) {
        for (EventObserver observer : OBSERVERS) observer.eventProperty(property, value);
    }

    public static void event(int eventId) {
        for (EventObserver observer : OBSERVERS) observer.event(eventId);
    }

    public static void eventEndFile(int reason, int error, String errorString) {
        for (EventObserver observer : OBSERVERS) observer.eventEndFile(reason, error, errorString);
    }

    public static void eventCommandReply(long userData, int error) {
        for (EventObserver observer : OBSERVERS) observer.eventCommandReply(userData, error);
    }

    public static void logMessage(String prefix, int level, String text) {
        for (LogObserver observer : LOG_OBSERVERS) observer.logMessage(prefix, level, text);
    }

    public interface EventObserver {

        void eventProperty(String property);

        void eventProperty(String property, long value);

        void eventProperty(String property, boolean value);

        void eventProperty(String property, String value);

        void eventProperty(String property, double value);

        void event(int eventId);

        default void eventEndFile(int reason, int error, String errorString) {
        }

        default void eventCommandReply(long userData, int error) {
        }
    }

    public interface LogObserver {

        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvFormat {

        public static final int MPV_FORMAT_NONE = 0;
        public static final int MPV_FORMAT_STRING = 1;
        public static final int MPV_FORMAT_OSD_STRING = 2;
        public static final int MPV_FORMAT_FLAG = 3;
        public static final int MPV_FORMAT_INT64 = 4;
        public static final int MPV_FORMAT_DOUBLE = 5;
        public static final int MPV_FORMAT_NODE = 6;
        public static final int MPV_FORMAT_NODE_ARRAY = 7;
        public static final int MPV_FORMAT_NODE_MAP = 8;
        public static final int MPV_FORMAT_BYTE_ARRAY = 9;

        private MpvFormat() {
        }
    }

    public static final class MpvEvent {

        public static final int MPV_EVENT_NONE = 0;
        public static final int MPV_EVENT_SHUTDOWN = 1;
        public static final int MPV_EVENT_LOG_MESSAGE = 2;
        public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
        public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
        public static final int MPV_EVENT_COMMAND_REPLY = 5;
        public static final int MPV_EVENT_START_FILE = 6;
        public static final int MPV_EVENT_END_FILE = 7;
        public static final int MPV_EVENT_FILE_LOADED = 8;
        public static final int MPV_EVENT_IDLE = 11;
        public static final int MPV_EVENT_TICK = 14;
        public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
        public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
        public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
        public static final int MPV_EVENT_SEEK = 20;
        public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
        public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
        public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
        public static final int MPV_EVENT_HOOK = 25;

        private MpvEvent() {
        }
    }

    public static final class MpvEndFileReason {

        public static final int MPV_END_FILE_REASON_EOF = 0;
        public static final int MPV_END_FILE_REASON_STOP = 2;
        public static final int MPV_END_FILE_REASON_QUIT = 3;
        public static final int MPV_END_FILE_REASON_ERROR = 4;
        public static final int MPV_END_FILE_REASON_REDIRECT = 5;

        private MpvEndFileReason() {
        }
    }
}
