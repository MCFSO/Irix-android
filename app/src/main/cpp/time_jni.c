#include <jni.h>
#include <stddef.h>
#include <stdio.h>

// Rust `xmc_time` C ABI (see rust/time/src/ffi.rs).
extern char *time_request(const char *op, const char *args_json);
extern void xmc_time_free_string(char *s);
extern long long xmc_time_now_unix_ns(void);

static jstring error_json(JNIEnv *env, const char *message) {
    char buf[512];
    // Keep the JNI shim dependency-free: the message is a fixed developer string.
    snprintf(buf, sizeof(buf), "{\"ok\":false,\"error\":\"%s\"}", message);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_org_mcfso_irix_gnss_TimeNative_request(JNIEnv *env, jobject thiz,
                                            jstring op, jstring args) {
    (void) thiz;
    if (op == NULL || args == NULL) {
        return error_json(env, "op/args is null");
    }
    const char *c_op = (*env)->GetStringUTFChars(env, op, NULL);
    const char *c_args = (*env)->GetStringUTFChars(env, args, NULL);
    if (c_op == NULL || c_args == NULL) {
        if (c_op != NULL) (*env)->ReleaseStringUTFChars(env, op, c_op);
        if (c_args != NULL) (*env)->ReleaseStringUTFChars(env, args, c_args);
        return error_json(env, "GetStringUTFChars failed");
    }

    char *result = time_request(c_op, c_args);
    jstring out = result != NULL
        ? (*env)->NewStringUTF(env, result)
        : error_json(env, "time_request returned null");

    if (result != NULL) {
        xmc_time_free_string(result);
    }
    (*env)->ReleaseStringUTFChars(env, op, c_op);
    (*env)->ReleaseStringUTFChars(env, args, c_args);
    return out;
}

JNIEXPORT jlong JNICALL
Java_org_mcfso_irix_gnss_TimeNative_nowUnixNs(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return (jlong) xmc_time_now_unix_ns();
}
