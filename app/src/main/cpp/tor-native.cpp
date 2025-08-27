//
// Created by Олександр Некрутенко on 17.08.2025.
//

#include "tor-native.h"
#include <jni.h>
#include <string>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include <dlfcn.h>
#include <vector>
#include <sstream>   // для std::istringstream
#include <string>
#include <cstring>   // для strdup
#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <android/log.h>

#include <jni.h>
#include <unistd.h>
#include <signal.h>
#include <atomic>
#include <chrono>

#define TAG "TorNative"
#define log(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static JavaVM *jvm = nullptr;
static jobject torManagerInstance = nullptr;
static jmethodID logMethodID = nullptr;

static pthread_t gTorThread = 0;
static std::atomic<bool> torExited(false);

struct TorArgs {
    int argc;
    char **argv;
};

static bool running = false;

void log_message(const char *message) {
    if (jvm && torManagerInstance && logMethodID) {
        JNIEnv *env;
        jvm->AttachCurrentThread(&env, nullptr);
        jstring jMessage = env->NewStringUTF(message);
        env->CallVoidMethod(torManagerInstance, logMethodID, jMessage);
        env->DeleteLocalRef(jMessage);
    }
}

extern "C" JNIEXPORT jboolean
Java_onion_network_TorManager_nativeStartTor(
        JNIEnv *env,
        jobject thiz,
        jstring torrc_path,
        jstring data_dir
) {
    const char *torrc = env->GetStringUTFChars(torrc_path, nullptr);
    const char *data = env->GetStringUTFChars(data_dir, nullptr);

    env->GetJavaVM(&jvm);
    torManagerInstance = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    logMethodID = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");

    char **argv = new char *[5];
    argv[0] = strdup("tor");
    argv[1] = strdup("-f");
    argv[2] = strdup(torrc);
    argv[3] = strdup("--DataDirectory");
    argv[4] = strdup(data);

    TorArgs *args = new TorArgs();
    args->argc = 5;
    args->argv = argv;

    pthread_t thread;
    int result = pthread_create(&thread, nullptr, [](void *arg) -> void * {
        TorArgs *torArgs = static_cast<TorArgs *>(arg);

        int pipefd[2];
        if (pipe(pipefd) == -1) {
            log_message("❌ Failed to create pipe for logs");
            torExited.store(true);
            return nullptr;
        }

        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        running = true;

        pthread_t logThread;
        int readFd = pipefd[0];
        pthread_create(&logThread, nullptr, [](void *arg) -> void * {
            int fd = *(int *)arg;
            FILE *stream = fdopen(fd, "r");
            if (!stream) return nullptr;

            char buffer[512];
            while (running && fgets(buffer, sizeof(buffer), stream)) {
                buffer[strcspn(buffer, "\n")] = 0;
                log_message(buffer);
            }
            fclose(stream);
            return nullptr;
        }, &readFd);

        void *handle = dlopen("libtor.so", RTLD_NOW);
        if (!handle) {
            log_message("❌ Failed to open libtor.so");
            running = false;
            torExited.store(true);

            // чистимо аргументи
            for (int i = 0; i < torArgs->argc; i++) {
                free(torArgs->argv[i]);
            }
            delete[] torArgs->argv;
            delete torArgs;

            gTorThread = 0;
            return nullptr; // просто виходимо із lambda
        }

        typedef int (*TorMainFunc)(int, char **);
        TorMainFunc tor_main_ptr = (TorMainFunc)dlsym(handle, "tor_main");
        if (!tor_main_ptr) {
            log_message("❌ tor_main not found in libtor.so");
            dlclose(handle);
            running = false;
            torExited.store(true);
            goto cleanup_args;
        }

        tor_main_ptr(torArgs->argc, torArgs->argv);

        dlclose(handle);
        running = false;

        cleanup_args:
        for (int i = 0; i < torArgs->argc; i++) {
            free(torArgs->argv[i]);
        }
        delete[] torArgs->argv;
        delete torArgs;

        torExited.store(true);
        gTorThread = 0;

        return nullptr;
    }, args);

    if (result == 0) {
        gTorThread = thread;
        torExited.store(false);
    }

    env->ReleaseStringUTFChars(torrc_path, torrc);
    env->ReleaseStringUTFChars(data_dir, data);

    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_onion_network_TorManager_nativeStopTor(JNIEnv
                                            *env,
                                            jobject thiz
) {
    // 1) Сигнал для лог-потоку (щоб fread у лог-потоці завершився)
    running = false;

    // 2) Якщо tor тред ще запущений — шлемо SIGINT (Tor обробляє як graceful shutdown)
    if (gTorThread != 0) {
        log("⟲ Requesting Tor graceful shutdown (SIGINT)...");
        int kill_res = pthread_kill(gTorThread, SIGINT);
        if (kill_res != 0) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "pthread_kill failed: %d", kill_res);
        }

        // 3) Чекаємо на завершення треда з timeout-ом (наприклад 8 секунд)
        const int timeout_ms = 8000;
        int waited = 0;
        const int step = 100; // ms
        while (!torExited.load() && waited < timeout_ms) {
            usleep(step * 1000);
            waited += step;
        }

        if (!torExited.load()) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                                "Tor didn't exit within %d ms after SIGINT. Trying SIGTERM...", timeout_ms);
            // Спроба SIGTERM — деякі збірки можуть обробити
            pthread_kill(gTorThread, SIGTERM);

            // чекаємо ще трохи
            waited = 0;
            const int extra_ms = 3000;
            while (!torExited.load() && waited < extra_ms) {
                usleep(step * 1000);
                waited += step;
            }
        }

        if (!torExited.load()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "Tor did not stop gracefully. Avoid calling tor_cleanup() from another thread (would crash).");
            // не робимо SIGKILL або виклик tor_cleanup() тут — це небезпечно для in-process libtor
        } else {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Tor exited cleanly.");
        }

        // optional: pthread_join if torExited true and thread handle valid
        if (gTorThread != 0 && torExited.load()) {
            // спробуємо приєднатися, якщо pthread_join не викликається раніше
            // але не блокуємо довго — оскільки ми вже переконались, що torExited==true
            pthread_join(gTorThread, nullptr);
            gTorThread = 0;
        }
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Tor thread not running (gTorThread==0).");
    }

    // 4) Далі чистимо GlobalRef
    if (torManagerInstance) {
        env->DeleteGlobalRef(torManagerInstance);
        torManagerInstance = nullptr;
    }

    // 5) Закриваємо логи (вже running=false)
    torExited.store(true);
}


extern "C" JNIEXPORT jboolean
JNICALL
Java_onion_network_TorManager_nativeIsBootstrapped(
        JNIEnv *env,
        jobject
        thiz) {
// Тут можна реалізувати перевірку стану через контрольний порт
// Поки що просто повертаємо true
    return
            JNI_TRUE;
}

extern "C" JNIEXPORT jstring
JNICALL
Java_onion_network_TorManager_nativeGetHiddenServiceDomain(
        JNIEnv *env,
        jobject
        thiz,
        jstring hs_dir
) {
    const char *dir = env->GetStringUTFChars(hs_dir, nullptr);

    char hostname_path[512];
    snprintf(hostname_path,
             sizeof(hostname_path), "%s/hostname", dir);

    FILE *fp = fopen(hostname_path, "r");
    if (!fp) {
        env->
                ReleaseStringUTFChars(hs_dir, dir
        );
        return env->NewStringUTF("");
    }

    char onion_domain[128];
    fgets(onion_domain,
          sizeof(onion_domain), fp);
    fclose(fp);

// Видаляємо \n
    onion_domain[
            strcspn(onion_domain,
                    "\n")] = 0;

    env->
            ReleaseStringUTFChars(hs_dir, dir
    );
    return env->
            NewStringUTF(onion_domain);
}

extern "C" JNIEXPORT jint
JNICALL
Java_onion_network_TorManager_nativeGetSocksPort(JNIEnv *env, jobject
thiz) {
// Шукаємо у логах рядок виду "SocksPort listening on 127.0.0.1:9050"
    FILE *log = fopen("path_to_tor_log", "r");
    char line[256];
    while (
            fgets(line,
                  sizeof(line), log)) {
        if (
                strstr(line,
                       "SocksPort listening on")) {
            char *port_str = strrchr(line, ':') + 1;
            return
                    atoi(port_str);
        }
    }
    return 9050; // Fallback
}