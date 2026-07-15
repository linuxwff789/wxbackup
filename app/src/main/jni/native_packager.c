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

    int argc = (*env)->GetArrayLength(env, args);
    const char **argv = (const char **)malloc((argc + 1) * sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i] = (*env)->GetStringUTFChars(env, jstr, NULL);
    }
    argv[argc] = NULL;

    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char **envp = NULL;
    if (envc > 0) {
        envp = (char **)malloc((envc + 1) * sizeof(char *));
        for (int i = 0; i < envc; i++) {
            jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            envp[i] = (char *)(*env)->GetStringUTFChars(env, jstr, NULL);
        }
        envp[envc] = NULL;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "exec: %s argc=%d", argv[0], argc);

    pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fork failed");
        for (int i = 0; i < argc; i++) { jstring j = (jstring)(*env)->GetObjectArrayElement(env, args, i); (*env)->ReleaseStringUTFChars(env, j, argv[i]); }
        free(argv);
        if (envp) { for (int i = 0; i < envc; i++) { jstring j = (jstring)(*env)->GetObjectArrayElement(env, envVars, i); (*env)->ReleaseStringUTFChars(env, j, envp[i]); } free(envp); }
        return -1;
    }

    if (pid == 0) {
        if (envp) execvp(argv[0], (char *const *)argv);
        else execvp(argv[0], (char *const *)argv);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "execvp failed: %s", argv[0]);
        _exit(127);
    }

    int status;
    waitpid(pid, &status, 0);

    for (int i = 0; i < argc; i++) { jstring j = (jstring)(*env)->GetObjectArrayElement(env, args, i); (*env)->ReleaseStringUTFChars(env, j, argv[i]); }
    free(argv);
    if (envp) { for (int i = 0; i < envc; i++) { jstring j = (jstring)(*env)->GetObjectArrayElement(env, envVars, i); (*env)->ReleaseStringUTFChars(env, j, envp[i]); } free(envp); }

    if (WIFEXITED(status)) { int ec = WEXITSTATUS(status); __android_log_print(ANDROID_LOG_INFO, TAG, "exit=%d", ec); return ec; }
    return -1;
}
