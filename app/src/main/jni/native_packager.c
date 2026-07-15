#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "wxhook:native"

JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativePackager_execCommand(
    JNIEnv *env, jobject thiz,
    jobjectArray args, jobjectArray envVars) {

    // Build command string for su -c
    int argc = (*env)->GetArrayLength(env, args);
    char *cmd = malloc(8192);
    cmd[0] = 0;

    // Add env vars
    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    for (int i = 0; i < envc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
        const char *var = (*env)->GetStringUTFChars(env, jstr, NULL);
        strcat(cmd, var);
        strcat(cmd, " ");
        (*env)->ReleaseStringUTFChars(env, jstr, var);
    }

    strcat(cmd, "exec ");
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *arg = (*env)->GetStringUTFChars(env, jstr, NULL);
        // Quote args with spaces
        if (strchr(arg, ' ')) {
            strcat(cmd, "\"");
            strcat(cmd, arg);
            strcat(cmd, "\"");
        } else {
            strcat(cmd, arg);
        }
        if (i < argc - 1) strcat(cmd, " ");
        (*env)->ReleaseStringUTFChars(env, jstr, arg);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "su cmd: %s", cmd);

    // Execute via su
    const char *su_argv[] = { "su", "-c", cmd, NULL };
    char *su_envp[] = { NULL };

    pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fork failed");
        free(cmd);
        return -1;
    }

    if (pid == 0) {
        execvp("su", (char *const *)su_argv);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "execvp su failed");
        _exit(127);
    }

    int status;
    waitpid(pid, &status, 0);
    free(cmd);

    if (WIFEXITED(status)) { int ec = WEXITSTATUS(status); __android_log_print(ANDROID_LOG_INFO, TAG, "exit=%d", ec); return ec; }
    return -1;
}
