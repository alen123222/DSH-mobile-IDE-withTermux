/* DSH Pocket: atomic no-replace publication for Android (MIT). */
#define _GNU_SOURCE
#include <node_api.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

static char *path_argument(napi_env env, napi_value value) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, NULL, 0, &length) != napi_ok) return NULL;
    char *buffer = malloc(length + 1);
    if (!buffer) return NULL;
    if (napi_get_value_string_utf8(env, value, buffer, length + 1, &length) != napi_ok) {
        free(buffer); return NULL;
    }
    return buffer;
}

static napi_value publish(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2], result;
    if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc != 2) {
        napi_throw_type_error(env, NULL, "publish requires two paths"); return NULL;
    }
    char *source = path_argument(env, argv[0]);
    char *target = path_argument(env, argv[1]);
    if (!source || !target) {
        free(source); free(target);
        napi_throw_type_error(env, NULL, "paths must be strings"); return NULL;
    }
    /* RENAME_NOREPLACE=1: existing targets cause EEXIST, never replacement.
       Failure on an unsupported kernel/filesystem propagates; no weak fallback. */
    int error = syscall(SYS_renameat2, AT_FDCWD, source, AT_FDCWD, target, 1) == 0 ? 0 : errno;
    free(source); free(target);
    if (napi_create_int32(env, error, &result) != napi_ok) return NULL;
    return result;
}

static napi_value init(napi_env env, napi_value exports) {
    napi_value fn;
    if (napi_create_function(env, "publish", NAPI_AUTO_LENGTH, publish, NULL, &fn) != napi_ok) return NULL;
    if (napi_set_named_property(env, exports, "publish", fn) != napi_ok) return NULL;
    return exports;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, init)
