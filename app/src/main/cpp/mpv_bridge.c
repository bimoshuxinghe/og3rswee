/*
 * mpv_bridge.c — media-kit libmpv JNI 桥接层
 *
 * 目标：让 is.xyz.mpv.MPVLib（原 mpv-android 接口）无缝对接
 *       media-kit 官方 libmpv-android-video-build v1.1.7 的 libmpv.so。
 *
 * 要点：
 *   1. 事件循环：独立 pthread + mpv_wait_event，事件转发回 Java 静态回调。
 *   2. 渲染：EGL + OpenGL ES2，使用标准 mpv_render_context API
 *      （MPV_RENDER_API_TYPE_OPENGL），不依赖 mpv-android 私有扩展参数。
 *   3. 硬件解码：调用 media-kit 扩展 mpv_lavc_set_java_vm + av_jni_set_java_vm
 *      使 ffmpeg 的 mediacodec hwdec 可用。
 *
 * 编译：app/src/main/cpp/CMakeLists.txt 生成 libmpvkit.so
 */

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "mpv/client.h"
#include "mpv/render.h"
#include "mpv/render_gl.h"

#define LOG_TAG "mpvkit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---------- media-kit / ffmpeg 扩展（libmpv.so 已导出） ---------- */
/* mpv_lavc_set_java_vm：media-kit 定制，在 mpv_initialize 之前调用 */
extern void mpv_lavc_set_java_vm(mpv_handle *ctx, void *vm);
/* av_jni_set_java_vm：ffmpeg 标准 API，签名同 avcodec.h */
extern int av_jni_set_java_vm(void *vm, void *logctx);

/* ---------- 全局状态 ---------- */
static JavaVM *g_jvm = NULL;
static jobject g_appctx = NULL;      /* 全局引用，供 ffmpeg JNI 使用 */
static mpv_handle *g_mpv = NULL;

static pthread_t g_event_thread;
static volatile int g_event_running = 0;

static pthread_t g_render_thread;
static pthread_mutex_t g_render_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_render_cond = PTHREAD_COND_INITIALIZER;
static volatile int g_render_request = 0;
static volatile int g_render_running = 0;

static ANativeWindow *g_window = NULL;
static EGLDisplay g_egl_display = EGL_NO_DISPLAY;
static EGLContext g_egl_context = EGL_NO_CONTEXT;
static EGLSurface g_egl_surface = EGL_NO_SURFACE;
static mpv_render_context *g_render_ctx = NULL;

/* ---------- Java 回调缓存 ---------- */
static jclass g_mpv_class = NULL;
static jmethodID g_ev_prop_str;
static jmethodID g_ev_prop_long;
static jmethodID g_ev_prop_bool;
static jmethodID g_ev_prop_strval;
static jmethodID g_ev_prop_double;
static jmethodID g_ev_event;
static jmethodID g_ev_end_file;
static jmethodID g_ev_cmd_reply;
static jmethodID g_ev_log;

/* ---------- 工具函数 ---------- */

static JNIEnv *attach_env(void) {
    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    return env;
}

static void detach_env(void) {
    (*g_jvm)->DetachCurrentThread(g_jvm);
}

static jstring to_jstring(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    return (*env)->NewStringUTF(env, s);
}

static char *from_jstring(JNIEnv *env, jstring js) {
    if (!js) return NULL;
    const char *s = (*env)->GetStringUTFChars(env, js, NULL);
    if (!s) return NULL;
    char *copy = strdup(s);
    (*env)->ReleaseStringUTFChars(env, js, s);
    return copy;
}

static char **argv_from_java(JNIEnv *env, jobjectArray arr) {
    if (!arr) return NULL;
    jsize len = (*env)->GetArrayLength(env, arr);
    char **argv = calloc((size_t) len + 1, sizeof(char *));
    if (!argv) return NULL;
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        argv[i] = from_jstring(env, js);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[len] = NULL;
    return argv;
}

static void free_argv(char **argv) {
    if (!argv) return;
    for (int i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
}

/* ---------- 事件循环 ---------- */

static void handle_event(JNIEnv *env, mpv_event *ev) {
    switch (ev->event_id) {
        case MPV_EVENT_PROPERTY_CHANGE: {
            mpv_event_property *prop = (mpv_event_property *) ev->data;
            if (!prop || !prop->name) break;
            jstring name = to_jstring(env, prop->name);
            switch (prop->format) {
                case MPV_FORMAT_STRING:
                    (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_prop_strval, name,
                                                 to_jstring(env, prop->data ? *(char **) prop->data : ""));
                    break;
                case MPV_FORMAT_FLAG:
                    (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_prop_bool, name,
                                                 prop->data && *(int *) prop->data != 0);
                    break;
                case MPV_FORMAT_INT64:
                    (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_prop_long, name,
                                                 prop->data ? *(int64_t *) prop->data : 0);
                    break;
                case MPV_FORMAT_DOUBLE:
                    (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_prop_double, name,
                                                 prop->data ? *(double *) prop->data : 0.0);
                    break;
                default:
                    (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_prop_str, name);
                    break;
            }
            (*env)->DeleteLocalRef(env, name);
            break;
        }
        case MPV_EVENT_END_FILE: {
            mpv_event_end_file *ef = (mpv_event_end_file *) ev->data;
            if (!ef) break;
            jstring es = to_jstring(env, mpv_error_string(ef->error));
            (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_end_file,
                                         (jint) ef->reason, (jint) ef->error, es);
            (*env)->DeleteLocalRef(env, es);
            break;
        }
        case MPV_EVENT_COMMAND_REPLY: {
            /* userdata 在 ev->reply_userdata，result 在 ev->data（mpv_event_command.result） */
            (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_cmd_reply,
                                         (jlong) ev->reply_userdata, (jint) ev->error);
            break;
        }
        case MPV_EVENT_LOG_MESSAGE: {
            mpv_event_log_message *lm = (mpv_event_log_message *) ev->data;
            if (!lm) break;
            jstring prefix = to_jstring(env, lm->prefix);
            jstring text = to_jstring(env, lm->text ? lm->text : "");
            (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_log,
                                         prefix, (jint) lm->log_level, text);
            (*env)->DeleteLocalRef(env, prefix);
            (*env)->DeleteLocalRef(env, text);
            break;
        }
        case MPV_EVENT_SHUTDOWN:
            (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_event, (jint) ev->event_id);
            g_event_running = 0;
            break;
        default:
            (*env)->CallStaticVoidMethod(env, g_mpv_class, g_ev_event, (jint) ev->event_id);
            break;
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static void *event_thread_main(void *arg) {
    (void) arg;
    JNIEnv *env = attach_env();
    if (!env) return NULL;
    while (g_event_running) {
        mpv_event *ev = mpv_wait_event(g_mpv, 0.5);
        if (ev->event_id == MPV_EVENT_NONE) continue;
        handle_event(env, ev);
    }
    detach_env();
    return NULL;
}

/* ---------- 渲染 ---------- */

static void render_update_cb(void *ctx) {
    (void) ctx;
    pthread_mutex_lock(&g_render_mutex);
    g_render_request = 1;
    pthread_cond_signal(&g_render_cond);
    pthread_mutex_unlock(&g_render_mutex);
}

static void *render_thread_main(void *arg) {
    (void) arg;
    JNIEnv *env = attach_env();
    if (!env) return NULL;

    if (eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context) != EGL_TRUE) {
        LOGE("render: eglMakeCurrent failed");
        detach_env();
        return NULL;
    }

    while (g_render_running) {
        pthread_mutex_lock(&g_render_mutex);
        while (!g_render_request && g_render_running) {
            pthread_cond_wait(&g_render_cond, &g_render_mutex);
        }
        int do_render = g_render_request;
        g_render_request = 0;
        pthread_mutex_unlock(&g_render_mutex);

        if (!do_render || !g_render_ctx) continue;

        mpv_render_context_render(g_render_ctx, NULL);
        eglSwapBuffers(g_egl_display, g_egl_surface);
    }

    eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    detach_env();
    return NULL;
}

static int setup_egl_and_render(JNIEnv *env, jobject surface) {
    if (g_render_ctx) {
        LOGW("render: already active, skip");
        return 0;
    }

    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("render: ANativeWindow_fromSurface failed");
        return -1;
    }

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) {
        LOGE("render: eglInitialize failed");
        ANativeWindow_release(win);
        return -1;
    }

    const EGLint config_attribs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    EGLConfig config;
    EGLint num_configs = 0;
    if (!eglChooseConfig(display, config_attribs, &config, 1, &num_configs) || num_configs < 1) {
        LOGE("render: eglChooseConfig failed");
        eglTerminate(display);
        ANativeWindow_release(win);
        return -1;
    }

    const EGLint ctx_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("render: eglCreateContext failed");
        eglTerminate(display);
        ANativeWindow_release(win);
        return -1;
    }

    EGLSurface egl_surf = eglCreateWindowSurface(display, config, win, NULL);
    if (egl_surf == EGL_NO_SURFACE) {
        LOGE("render: eglCreateWindowSurface failed");
        eglDestroyContext(display, context);
        eglTerminate(display);
        ANativeWindow_release(win);
        return -1;
    }

    if (eglMakeCurrent(display, egl_surf, egl_surf, context) != EGL_TRUE) {
        LOGE("render: eglMakeCurrent failed");
        eglDestroySurface(display, egl_surf);
        eglDestroyContext(display, context);
        eglTerminate(display);
        ANativeWindow_release(win);
        return -1;
    }

    mpv_opengl_init_params gl_init = {
            .get_proc_address = (void *(*)(void *, const char *)) eglGetProcAddress,
            .get_proc_address_ctx = NULL,
    };
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, (void *) MPV_RENDER_API_TYPE_OPENGL},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
            {0, NULL}
    };

    mpv_render_context *rctx = NULL;
    if (mpv_render_context_create(&rctx, g_mpv, params) < 0 || !rctx) {
        LOGE("render: mpv_render_context_create failed");
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, egl_surf);
        eglDestroyContext(display, context);
        eglTerminate(display);
        ANativeWindow_release(win);
        return -1;
    }

    mpv_render_context_set_update_callback(rctx, render_update_cb, NULL);

    g_window = win;
    g_egl_display = display;
    g_egl_context = context;
    g_egl_surface = egl_surf;
    g_render_ctx = rctx;
    g_render_request = 1;
    g_render_running = 1;

    if (pthread_create(&g_render_thread, NULL, render_thread_main, NULL) != 0) {
        LOGE("render: pthread_create failed");
        g_render_running = 0;
        mpv_render_context_free(rctx);
        g_render_ctx = NULL;
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, egl_surf);
        eglDestroyContext(display, context);
        eglTerminate(display);
        ANativeWindow_release(win);
        g_window = NULL;
        g_egl_display = EGL_NO_DISPLAY;
        g_egl_context = EGL_NO_CONTEXT;
        g_egl_surface = EGL_NO_SURFACE;
        return -1;
    }

    return 0;
}

static void teardown_egl_and_render(void) {
    if (g_render_running) {
        g_render_running = 0;
        pthread_mutex_lock(&g_render_mutex);
        g_render_request = 1;
        pthread_cond_signal(&g_render_cond);
        pthread_mutex_unlock(&g_render_mutex);
        pthread_join(g_render_thread, NULL);
    }

    if (g_render_ctx) {
        mpv_render_context_free(g_render_ctx);
        g_render_ctx = NULL;
    }

    if (g_egl_display != EGL_NO_DISPLAY) {
        if (g_egl_surface != EGL_NO_SURFACE) {
            eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(g_egl_display, g_egl_surface);
            g_egl_surface = EGL_NO_SURFACE;
        }
        if (g_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_egl_display, g_egl_context);
            g_egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(g_egl_display);
        g_egl_display = EGL_NO_DISPLAY;
    }

    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
}

/* ---------- JNI: is.xyz.mpv.MPVLib ---------- */

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_create(JNIEnv *env, jclass clazz, jobject appctx) {
    if (g_mpv) {
        LOGW("create: already created");
        return;
    }

    (*env)->GetJavaVM(env, &g_jvm);
    g_appctx = (*env)->NewGlobalRef(env, appctx);

    /* 缓存 Java 静态回调 */
    g_mpv_class = (jclass) (*env)->NewGlobalRef(env, clazz);
    g_ev_prop_str = (*env)->GetStaticMethodID(env, g_mpv_class, "eventProperty", "(Ljava/lang/String;)V");
    g_ev_prop_long = (*env)->GetStaticMethodID(env, g_mpv_class, "eventProperty", "(Ljava/lang/String;J)V");
    g_ev_prop_bool = (*env)->GetStaticMethodID(env, g_mpv_class, "eventProperty", "(Ljava/lang/String;Z)V");
    g_ev_prop_strval = (*env)->GetStaticMethodID(env, g_mpv_class, "eventProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_ev_prop_double = (*env)->GetStaticMethodID(env, g_mpv_class, "eventProperty", "(Ljava/lang/String;D)V");
    g_ev_event = (*env)->GetStaticMethodID(env, g_mpv_class, "event", "(I)V");
    g_ev_end_file = (*env)->GetStaticMethodID(env, g_mpv_class, "eventEndFile", "(IILjava/lang/String;)V");
    g_ev_cmd_reply = (*env)->GetStaticMethodID(env, g_mpv_class, "eventCommandReply", "(JI)V");
    g_ev_log = (*env)->GetStaticMethodID(env, g_mpv_class, "logMessage", "(Ljava/lang/String;ILjava/lang/String;)V");

    g_mpv = mpv_create();
    if (!g_mpv) {
        LOGE("create: mpv_create failed");
        return;
    }

    /* media-kit 扩展：让 ffmpeg 的 mediacodec hwdec 拿到 JavaVM */
    mpv_lavc_set_java_vm(g_mpv, g_jvm);
    av_jni_set_java_vm(g_jvm, NULL);

    /* 与 mpv-android 对齐的基础选项 */
    mpv_set_option_string(g_mpv, "vo", "gpu");
    mpv_set_option_string(g_mpv, "hwdec", "mediacodec");

    if (mpv_initialize(g_mpv) < 0) {
        LOGE("create: mpv_initialize failed");
        mpv_destroy(g_mpv);
        g_mpv = NULL;
        return;
    }

    g_event_running = 1;
    if (pthread_create(&g_event_thread, NULL, event_thread_main, NULL) != 0) {
        LOGE("create: event thread failed");
        g_event_running = 0;
        mpv_destroy(g_mpv);
        g_mpv = NULL;
        return;
    }

    LOGI("create: mpv initialized (media-kit libmpv)");
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_init(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    /* create() 中已完成初始化，init 保持兼容空实现 */
}

JNIEXPORT jint JNICALL
Java_is_xyz_mpv_MPVLib_destroy(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    teardown_egl_and_render();

    if (g_mpv) {
        g_event_running = 0;
        mpv_destroy(g_mpv);
        pthread_join(g_event_thread, NULL);
        g_mpv = NULL;
    }

    if (g_mpv_class) {
        (*env)->DeleteGlobalRef(env, g_mpv_class);
        g_mpv_class = NULL;
    }
    if (g_appctx) {
        (*env)->DeleteGlobalRef(env, g_appctx);
        g_appctx = NULL;
    }
    LOGI("destroy: done");
    return 0;
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_attachSurface(JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    if (!g_mpv) return;
    if (setup_egl_and_render(env, surface) != 0) {
        LOGE("attachSurface: setup failed");
    }
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_detachSurface(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    teardown_egl_and_render();
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_attachOsdSurface(JNIEnv *env, jclass clazz, jobject surface) {
    (void) env;
    (void) clazz;
    (void) surface;
    /* OSD 走同一 render context，无需额外处理 */
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_detachOsdSurface(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_replaceSurface(JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    if (!g_mpv) return;
    teardown_egl_and_render();
    if (surface) {
        setup_egl_and_render(env, surface);
    }
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_replaceOsdSurface(JNIEnv *env, jclass clazz, jobject surface) {
    (void) env;
    (void) clazz;
    (void) surface;
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_command(JNIEnv *env, jclass clazz, jobjectArray cmd) {
    (void) clazz;
    if (!g_mpv) return;
    char **argv = argv_from_java(env, cmd);
    if (!argv) return;
    mpv_command(g_mpv, (const char *const *) argv);
    free_argv(argv);
}

JNIEXPORT jint JNICALL
Java_is_xyz_mpv_MPVLib_enqueueCommand(JNIEnv *env, jclass clazz, jlong userData, jobjectArray cmd) {
    (void) clazz;
    if (!g_mpv) return -1;
    char **argv = argv_from_java(env, cmd);
    if (!argv) return -1;
    int ret = mpv_command_async(g_mpv, (uint64_t) userData, (const char *const *) argv);
    free_argv(argv);
    return ret;
}

JNIEXPORT jint JNICALL
Java_is_xyz_mpv_MPVLib_setOptionString(JNIEnv *env, jclass clazz, jstring name, jstring value) {
    (void) clazz;
    if (!g_mpv) return -1;
    char *n = from_jstring(env, name);
    char *v = from_jstring(env, value);
    int ret = mpv_set_option_string(g_mpv, n, v);
    free(n);
    free(v);
    return ret;
}

JNIEXPORT jobject JNICALL
Java_is_xyz_mpv_MPVLib_grabThumbnail(JNIEnv *env, jclass clazz, jint dimension) {
    (void) env;
    (void) clazz;
    (void) dimension;
    /* MpvSimplePlayer 未使用该能力，返回 null */
    return NULL;
}

JNIEXPORT jobject JNICALL
Java_is_xyz_mpv_MPVLib_getPropertyInt(JNIEnv *env, jclass clazz, jstring property) {
    (void) clazz;
    if (!g_mpv) return NULL;
    char *p = from_jstring(env, property);
    int64_t v = 0;
    int ret = mpv_get_property(g_mpv, p, MPV_FORMAT_INT64, &v);
    free(p);
    if (ret < 0) return NULL;
    jclass box = (*env)->FindClass(env, "java/lang/Integer");
    jmethodID mid = (*env)->GetStaticMethodID(env, box, "valueOf", "(I)Ljava/lang/Integer;");
    return (*env)->CallStaticObjectMethod(env, box, mid, (jint) v);
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_setPropertyInt(JNIEnv *env, jclass clazz, jstring property, jint value) {
    (void) clazz;
    if (!g_mpv) return;
    char *p = from_jstring(env, property);
    int64_t v = value;
    mpv_set_property(g_mpv, p, MPV_FORMAT_INT64, &v);
    free(p);
}

JNIEXPORT jobject JNICALL
Java_is_xyz_mpv_MPVLib_getPropertyDouble(JNIEnv *env, jclass clazz, jstring property) {
    (void) clazz;
    if (!g_mpv) return NULL;
    char *p = from_jstring(env, property);
    double v = 0;
    int ret = mpv_get_property(g_mpv, p, MPV_FORMAT_DOUBLE, &v);
    free(p);
    if (ret < 0) return NULL;
    jclass box = (*env)->FindClass(env, "java/lang/Double");
    jmethodID mid = (*env)->GetStaticMethodID(env, box, "valueOf", "(D)Ljava/lang/Double;");
    return (*env)->CallStaticObjectMethod(env, box, mid, (jdouble) v);
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_setPropertyDouble(JNIEnv *env, jclass clazz, jstring property, jdouble value) {
    (void) clazz;
    if (!g_mpv) return;
    char *p = from_jstring(env, property);
    mpv_set_property(g_mpv, p, MPV_FORMAT_DOUBLE, &value);
    free(p);
}

JNIEXPORT jobject JNICALL
Java_is_xyz_mpv_MPVLib_getPropertyBoolean(JNIEnv *env, jclass clazz, jstring property) {
    (void) clazz;
    if (!g_mpv) return NULL;
    char *p = from_jstring(env, property);
    int v = 0;
    int ret = mpv_get_property(g_mpv, p, MPV_FORMAT_FLAG, &v);
    free(p);
    if (ret < 0) return NULL;
    jclass box = (*env)->FindClass(env, "java/lang/Boolean");
    jmethodID mid = (*env)->GetStaticMethodID(env, box, "valueOf", "(Z)Ljava/lang/Boolean;");
    return (*env)->CallStaticObjectMethod(env, box, mid, (jboolean) (v != 0));
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_setPropertyBoolean(JNIEnv *env, jclass clazz, jstring property, jboolean value) {
    (void) clazz;
    if (!g_mpv) return;
    char *p = from_jstring(env, property);
    int v = value ? 1 : 0;
    mpv_set_property(g_mpv, p, MPV_FORMAT_FLAG, &v);
    free(p);
}

JNIEXPORT jstring JNICALL
Java_is_xyz_mpv_MPVLib_getPropertyString(JNIEnv *env, jclass clazz, jstring property) {
    (void) clazz;
    if (!g_mpv) return NULL;
    char *p = from_jstring(env, property);
    char *v = mpv_get_property_string(g_mpv, p);
    free(p);
    if (!v) return NULL;
    jstring js = to_jstring(env, v);
    mpv_free(v);
    return js;
}

JNIEXPORT void JNICALL
Java_is_xyz_mpv_MPVLib_setPropertyString(JNIEnv *env, jclass clazz, jstring property, jstring value) {
    (void) clazz;
    if (!g_mpv) return;
    char *p = from_jstring(env, property);
    char *v = from_jstring(env, value);
    mpv_set_property_string(g_mpv, p, v);
    free(p);
    free(v);
}

JNIEXPORT jbyteArray JNICALL
Java_is_xyz_mpv_MPVLib_getPropertyByteArray(JNIEnv *env, jclass clazz, jstring property) {
    (void) clazz;
    if (!g_mpv) return NULL;
    char *p = from_jstring(env, property);
    mpv_byte_array arr = {0};
    int ret = mpv_get_property(g_mpv, p, MPV_FORMAT_BYTE_ARRAY, &arr);
    free(p);
    if (ret < 0 || !arr.data) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) arr.size);
    if (out) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) arr.size, (const jbyte *) arr.data);
    }
    mpv_free(arr.data);
    return out;
}

JNIEXPORT jint JNICALL
Java_is_xyz_mpv_MPVLib_observeProperty(JNIEnv *env, jclass clazz, jstring property, jint format) {
    (void) clazz;
    if (!g_mpv) return -1;
    char *p = from_jstring(env, property);
    int ret = mpv_observe_property(g_mpv, 0, p, (mpv_format) format);
    free(p);
    return ret;
}
