#include <jni.h>
#include "jvmti_min.h"
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>

#define TAG "debroid-hotreload"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct ClassPayload {
    std::string binary_name;
    std::string dex_path;
};

void write_result(const std::string& path, const std::string& content) {
    FILE* fp = fopen(path.c_str(), "w");
    if (fp) {
        fputs(content.c_str(), fp);
        fclose(fp);
        LOGI("Wrote result to %s: %s", path.c_str(), content.c_str());
    } else {
        LOGE("Failed to open result file %s for writing", path.c_str());
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    (void)reserved;
    LOGI("Debroid JVMTI agent attaching, options: %s", options ? options : "(null)");

    std::string manifest_path = (options && strlen(options) > 0) ? options : "";
    std::string result_path = "result.txt";
    size_t last_slash = manifest_path.rfind('/');
    if (last_slash != std::string::npos) {
        result_path = manifest_path.substr(0, last_slash + 1) + "result.txt";
    }

    std::string combined_dex_path;
    std::vector<ClassPayload> class_payloads;

    std::ifstream manifest_file(manifest_path);
    if (!manifest_file.is_open()) {
        write_result(result_path, "status=ERROR code=MANIFEST_NOT_FOUND msg=Failed_to_open_manifest\n");
        return -1;
    }

    std::string line;
    while (std::getline(manifest_file, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) {
            line.pop_back();
        }
        if (line.rfind("combined_dex=", 0) == 0) {
            combined_dex_path = line.substr(13);
        } else if (line.rfind("class=", 0) == 0) {
            std::string class_info = line.substr(6);
            size_t pipe_pos = class_info.find('|');
            if (pipe_pos != std::string::npos) {
                std::string name = class_info.substr(0, pipe_pos);
                std::string path = class_info.substr(pipe_pos + 1);
                class_payloads.push_back({name, path});
            }
        }
    }
    manifest_file.close();

    jvmtiEnv* jvmti = nullptr;
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2);
    if (rc != JNI_OK || !jvmti) {
        write_result(result_path, "status=ERROR code=JVMTI_INIT_FAILED msg=GetEnv_failed\n");
        return -1;
    }

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_redefine_classes = 1;
    jvmtiError capErr = jvmti->AddCapabilities(&caps);
    if (capErr != JVMTI_ERROR_NONE) {
        LOGE("AddCapabilities can_redefine_classes returned: %d", capErr);
    }

    JNIEnv* env = nullptr;
    rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc != JNI_OK || !env) {
        rc = vm->AttachCurrentThread(&env, nullptr);
        if (rc != JNI_OK || !env) {
            write_result(result_path, "status=ERROR code=JNI_ATTACH_FAILED msg=AttachCurrentThread_failed\n");
            return -1;
        }
    }

    // Resolve Application
    jobject app_obj = nullptr;
    jclass activity_thread_cls = env->FindClass("android/app/ActivityThread");
    if (activity_thread_cls) {
        jmethodID current_app_mid = env->GetStaticMethodID(activity_thread_cls, "currentApplication", "()Landroid/app/Application;");
        if (current_app_mid) {
            app_obj = env->CallStaticObjectMethod(activity_thread_cls, current_app_mid);
        }
        if (!app_obj) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            jmethodID current_thread_mid = env->GetStaticMethodID(activity_thread_cls, "currentActivityThread", "()Landroid/app/ActivityThread;");
            if (current_thread_mid) {
                jobject act_thread = env->CallStaticObjectMethod(activity_thread_cls, current_thread_mid);
                if (act_thread) {
                    jmethodID get_app_mid = env->GetMethodID(activity_thread_cls, "getApplication", "()Landroid/app/Application;");
                    if (get_app_mid) {
                        app_obj = env->CallObjectMethod(act_thread, get_app_mid);
                    }
                    env->DeleteLocalRef(act_thread);
                }
            }
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    // Resolve ClassLoader
    jobject class_loader = nullptr;
    if (app_obj) {
        jclass context_cls = env->FindClass("android/content/Context");
        if (context_cls) {
            jmethodID get_cl_mid = env->GetMethodID(context_cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
            if (get_cl_mid) {
                class_loader = env->CallObjectMethod(app_obj, get_cl_mid);
            }
            env->DeleteLocalRef(context_cls);
        }
    }
    if (!class_loader) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        jclass thread_cls = env->FindClass("java/lang/Thread");
        if (thread_cls) {
            jmethodID curr_thread_mid = env->GetStaticMethodID(thread_cls, "currentThread", "()Ljava/lang/Thread;");
            if (curr_thread_mid) {
                jobject curr_thread = env->CallStaticObjectMethod(thread_cls, curr_thread_mid);
                if (curr_thread) {
                    jmethodID get_ccl_mid = env->GetMethodID(thread_cls, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
                    if (get_ccl_mid) {
                        class_loader = env->CallObjectMethod(curr_thread, get_ccl_mid);
                    }
                    env->DeleteLocalRef(curr_thread);
                }
            }
            env->DeleteLocalRef(thread_cls);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    // Partition classes via ClassLoader.findLoadedClass
    jclass cl_cls = env->FindClass("java/lang/ClassLoader");
    jmethodID find_loaded_class_mid = cl_cls ? env->GetMethodID(cl_cls, "findLoadedClass", "(Ljava/lang/String;)Ljava/lang/Class;") : nullptr;

    std::vector<std::pair<jclass, std::string>> loaded_classes;
    int new_classes_count = 0;

    for (const auto& payload : class_payloads) {
        jclass loaded_cls = nullptr;
        if (class_loader && find_loaded_class_mid) {
            jstring jname = env->NewStringUTF(payload.binary_name.c_str());
            loaded_cls = static_cast<jclass>(env->CallObjectMethod(class_loader, find_loaded_class_mid, jname));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                loaded_cls = nullptr;
            }
            env->DeleteLocalRef(jname);
        }

        if (loaded_cls != nullptr) {
            loaded_classes.push_back({loaded_cls, payload.dex_path});
        } else {
            new_classes_count++;
        }
    }

    // Add new classes dex to BaseDexClassLoader
    if (!combined_dex_path.empty() && class_loader && (new_classes_count > 0 || loaded_classes.empty())) {
        jclass base_dex_cl_cls = env->FindClass("dalvik/system/BaseDexClassLoader");
        if (base_dex_cl_cls) {
            jmethodID add_dex_path_mid = env->GetMethodID(base_dex_cl_cls, "addDexPath", "(Ljava/lang/String;)V");
            if (add_dex_path_mid) {
                jstring jdex = env->NewStringUTF(combined_dex_path.c_str());
                env->CallVoidMethod(class_loader, add_dex_path_mid, jdex);
                if (env->ExceptionCheck()) {
                    LOGE("addDexPath threw exception");
                    env->ExceptionClear();
                } else {
                    LOGI("addDexPath successfully called for: %s", combined_dex_path.c_str());
                }
                env->DeleteLocalRef(jdex);
            }
            env->DeleteLocalRef(base_dex_cl_cls);
        }
    }

    int redefined_count = 0;
    bool used_structural = false;

    for (auto& item : loaded_classes) {
        jclass klass = item.first;
        const std::string& dex_path = item.second;

        FILE* fp = fopen(dex_path.c_str(), "rb");
        if (!fp) {
            LOGE("Failed to open dex file: %s", dex_path.c_str());
            continue;
        }
        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        std::vector<unsigned char> dex_bytes(sz);
        fread(dex_bytes.data(), 1, sz, fp);
        fclose(fp);

        jvmtiClassDefinition def;
        def.klass = klass;
        def.class_byte_count = static_cast<jint>(dex_bytes.size());
        def.class_bytes = dex_bytes.data();

        jvmtiError redefErr = jvmti->RedefineClasses(1, &def);
        if (redefErr == JVMTI_ERROR_NONE) {
            redefined_count++;
        } else {
            LOGI("Standard RedefineClasses returned error: %d, attempting structural redefine", redefErr);
            jint ext_count = 0;
            jvmtiExtensionFunctionInfo* ext_funcs = nullptr;
            jvmtiError extErr = jvmti->GetExtensionFunctions(&ext_count, &ext_funcs);
            bool structuralApplied = false;

            if (extErr == JVMTI_ERROR_NONE && ext_funcs) {
                for (int i = 0; i < ext_count; i++) {
                    if (ext_funcs[i].id && strcmp(ext_funcs[i].id, "com.android.art.class.structural_redefine_classes") == 0) {
                        typedef jvmtiError (JNICALL *ArtStructuralRedefineFunc)(jvmtiEnv*, jint, const jvmtiClassDefinition*);
                        ArtStructuralRedefineFunc sfunc = reinterpret_cast<ArtStructuralRedefineFunc>(ext_funcs[i].func);
                        jvmtiError sErr = sfunc(jvmti, 1, &def);
                        if (sErr == JVMTI_ERROR_NONE) {
                            structuralApplied = true;
                            used_structural = true;
                            redefined_count++;
                            LOGI("Structural redefinition succeeded!");
                        } else {
                            LOGE("Structural redefinition failed with error: %d", sErr);
                        }
                        break;
                    }
                }
                jvmti->Deallocate(reinterpret_cast<unsigned char*>(ext_funcs));
            }

            if (!structuralApplied) {
                char buffer[128];
                snprintf(buffer, sizeof(buffer), "status=STRUCTURAL_INCOMPATIBLE reason=JVMTI_ERROR_%d\n", redefErr);
                write_result(result_path, buffer);
                for (auto& pair : loaded_classes) {
                    env->DeleteLocalRef(pair.first);
                }
                if (activity_thread_cls) env->DeleteLocalRef(activity_thread_cls);
                if (cl_cls) env->DeleteLocalRef(cl_cls);
                if (class_loader) env->DeleteLocalRef(class_loader);
                if (app_obj) env->DeleteLocalRef(app_obj);
                return 0;
            }
        }
    }

    // UI Refresh
    std::string ui_mode = "NONE";
    bool composeSuccess = false;
    jclass hotReloaderClass = env->FindClass("androidx/compose/runtime/HotReloader");
    if (!hotReloaderClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        hotReloaderClass = env->FindClass("org/jetbrains/compose/reload/jvm/HotReloader");
    }
    if (hotReloaderClass) {
        jfieldID compField = env->GetStaticFieldID(hotReloaderClass, "Companion", "Landroidx/compose/runtime/HotReloader$Companion;");
        if (compField) {
            jobject compObj = env->GetStaticObjectField(hotReloaderClass, compField);
            if (compObj) {
                jclass compClass = env->GetObjectClass(compObj);
                jmethodID simMethod = env->GetMethodID(compClass, "simulateHotReload", "(Ljava/lang/Object;)V");
                if (simMethod) {
                    env->CallVoidMethod(compObj, simMethod, app_obj);
                    if (!env->ExceptionCheck()) {
                        composeSuccess = true;
                        ui_mode = "COMPOSE_HOT_RELOAD";
                    } else {
                        env->ExceptionClear();
                    }
                }
                env->DeleteLocalRef(compClass);
                env->DeleteLocalRef(compObj);
            }
        }
        env->DeleteLocalRef(hotReloaderClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    if (!composeSuccess && app_obj) {
        jclass looperClass = env->FindClass("android/os/Looper");
        jmethodID getMainLooperMid = looperClass ? env->GetStaticMethodID(looperClass, "getMainLooper", "()Landroid/os/Looper;") : nullptr;
        jobject mainLooper = getMainLooperMid ? env->CallStaticObjectMethod(looperClass, getMainLooperMid) : nullptr;
        if (mainLooper) {
            ui_mode = "ACTIVITY_RECREATE";
            env->DeleteLocalRef(mainLooper);
        }
        if (looperClass) env->DeleteLocalRef(looperClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    const char* mode_str = used_structural ? "JVMTI_STRUCTURAL_SWAP" : "JVMTI_HOT_SWAP";
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "status=OK mode=%s redefined=%d new=%d ui=%s\n",
             mode_str, redefined_count, new_classes_count, ui_mode.c_str());
    write_result(result_path, buffer);

    for (auto& pair : loaded_classes) {
        env->DeleteLocalRef(pair.first);
    }
    if (activity_thread_cls) env->DeleteLocalRef(activity_thread_cls);
    if (cl_cls) env->DeleteLocalRef(cl_cls);
    if (class_loader) env->DeleteLocalRef(class_loader);
    if (app_obj) env->DeleteLocalRef(app_obj);

    return 0;
}
