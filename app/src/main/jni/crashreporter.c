#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <string.h>
#include <unistd.h>

#define CRASH_REPORTER_TAG "CRASHREPORTER"
#define CRASH_SIGNAL_COUNT 5

static const int crash_signals[CRASH_SIGNAL_COUNT] = {
        SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE
};

static struct sigaction crash_previous_actions[CRASH_SIGNAL_COUNT];
static int crash_pipe_fds[2] = {-1, -1};
static bool crash_installed = false;
static bool crash_watcher_running = false;
static JavaVM *crash_jvm = NULL;
static jclass crash_callback_class = NULL;
static jmethodID crash_callback_method = NULL;
static pthread_t crash_watcher_thread;

JNIEXPORT void JNICALL
Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(
        JNIEnv *env, jclass clazz);

static void crash_restore_handlers(void) {
    for (int i = 0; i < CRASH_SIGNAL_COUNT; i++) {
        sigaction(crash_signals[i], &crash_previous_actions[i], NULL);
    }
}

static void crash_close_pipe(void) {
    if (crash_pipe_fds[0] >= 0) {
        close(crash_pipe_fds[0]);
        crash_pipe_fds[0] = -1;
    }
    if (crash_pipe_fds[1] >= 0) {
        close(crash_pipe_fds[1]);
        crash_pipe_fds[1] = -1;
    }
}

static void crash_signal_handler(int signo, siginfo_t *info, void *ucontext) {
    (void) info;
    (void) ucontext;

    if (crash_pipe_fds[1] >= 0) {
        const char marker = '1';
        TEMP_FAILURE_RETRY(write(crash_pipe_fds[1], &marker, sizeof(marker)));
    }

    crash_restore_handlers();
    raise(signo);
}

static void *crash_watcher_main(void *arg) {
    (void) arg;

    while (true) {
        char marker = '\0';
        ssize_t read_result = TEMP_FAILURE_RETRY(read(crash_pipe_fds[0], &marker, sizeof(marker)));
        if (read_result <= 0 || marker == 'q') {
            break;
        }

        if (marker != '1' || crash_jvm == NULL ||
                crash_callback_class == NULL ||
                crash_callback_method == NULL) {
            continue;
        }

        JNIEnv *env = NULL;
        bool attached = false;
        jint get_env_result = (*crash_jvm)->GetEnv(crash_jvm, (void **) &env, JNI_VERSION_1_6);
        if (get_env_result == JNI_EDETACHED) {
            if ((*crash_jvm)->AttachCurrentThread(crash_jvm, &env, NULL) != JNI_OK) {
                __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                                    "Failed to attach watcher thread to JVM.");
                continue;
            }
            attached = true;
        } else if (get_env_result != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                                "GetEnv failed in watcher thread.");
            continue;
        }

        __android_log_print(ANDROID_LOG_INFO, CRASH_REPORTER_TAG,
                            "Calling Java native crash callback.");
        (*env)->CallStaticVoidMethod(env, crash_callback_class, crash_callback_method);
        if ((*env)->ExceptionCheck(env)) {
            __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                                "Java native crash callback threw.");
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }

        if (attached) {
            (*crash_jvm)->DetachCurrentThread(crash_jvm);
        }
    }

    return NULL;
}

static bool crash_register_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = crash_signal_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;

    for (int i = 0; i < CRASH_SIGNAL_COUNT; i++) {
        if (sigaction(crash_signals[i], &action, &crash_previous_actions[i]) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                                "Failed to install signal handler for %d", crash_signals[i]);
            return false;
        }
    }
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_psiphon3_CrashReporter_nativeInstallCrashSignalNotifier(
        JNIEnv *env, jclass clazz) {
    if (crash_installed) {
        Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(env, clazz);
    }

    if ((*env)->GetJavaVM(env, &crash_jvm) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to get JavaVM.");
        return JNI_FALSE;
    }

    jclass callback_class = (*env)->FindClass(env, "com/psiphon3/CrashReporter");
    if (callback_class == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to find CrashReporter callback class.");
        return JNI_FALSE;
    }

    crash_callback_class = (*env)->NewGlobalRef(env, callback_class);
    (*env)->DeleteLocalRef(env, callback_class);
    if (crash_callback_class == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to create global ref for CrashReporter callback class.");
        return JNI_FALSE;
    }

    crash_callback_method = (*env)->GetStaticMethodID(
            env, crash_callback_class, "onNativeCrashSignal", "()V");
    if (crash_callback_method == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to resolve CrashReporter callback method.");
        (*env)->DeleteGlobalRef(env, crash_callback_class);
        crash_callback_class = NULL;
        return JNI_FALSE;
    }

    if (pipe(crash_pipe_fds) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to create crash notifier pipe.");
        Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(env, clazz);
        return JNI_FALSE;
    }

    if (pthread_create(&crash_watcher_thread, NULL, crash_watcher_main, NULL) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, CRASH_REPORTER_TAG,
                            "Failed to start crash notifier watcher thread.");
        Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(env, clazz);
        return JNI_FALSE;
    }
    crash_watcher_running = true;

    if (!crash_register_handlers()) {
        Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(env, clazz);
        return JNI_FALSE;
    }

    crash_installed = true;
    __android_log_print(ANDROID_LOG_INFO, CRASH_REPORTER_TAG,
                        "Installed crash signal notifier.");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_psiphon3_CrashReporter_nativeUninstallCrashSignalNotifier(
        JNIEnv *env, jclass clazz) {
    (void) clazz;

    if (crash_installed) {
        crash_restore_handlers();
        crash_installed = false;
    }

    if (crash_watcher_running && crash_pipe_fds[1] >= 0) {
        const char stop_marker = 'q';
        TEMP_FAILURE_RETRY(write(crash_pipe_fds[1], &stop_marker, sizeof(stop_marker)));
        pthread_join(crash_watcher_thread, NULL);
        crash_watcher_running = false;
    }

    crash_close_pipe();

    if (crash_callback_class != NULL) {
        (*env)->DeleteGlobalRef(env, crash_callback_class);
        crash_callback_class = NULL;
    }
    crash_callback_method = NULL;
    crash_jvm = NULL;

    __android_log_print(ANDROID_LOG_INFO, CRASH_REPORTER_TAG,
                        "Uninstalled crash signal notifier.");
}
