#include <jni.h>
#include "jvmti_min.h"
#include <android/log.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <utility>
#include <vector>
#include <fstream>
#include <sstream>
#include <thread>
#include <unistd.h>

#define TAG "debroid-hotreload"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct ClassPayload {
    std::string binary_name;
    std::string dex_path;
};

void write_result(const std::string& path, const std::string& content) {
    std::string tmp_path = path + ".tmp";
    FILE* fp = fopen(tmp_path.c_str(), "w");
    if (fp) {
        fputs(content.c_str(), fp);
        fflush(fp);
        fclose(fp);
        rename(tmp_path.c_str(), path.c_str());
        LOGI("Wrote result to %s: %s", path.c_str(), content.c_str());
    } else {
        LOGE("Failed to open result file %s for writing", path.c_str());
    }
}

jclass load_app_class(JNIEnv* env, jobject class_loader, jmethodID load_class_mid, const char* dot_name) {
    if (!class_loader || !load_class_mid) return nullptr;
    jstring jname = env->NewStringUTF(dot_name);
    jclass cls = static_cast<jclass>(env->CallObjectMethod(class_loader, load_class_mid, jname));
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return cls;
}

bool matches_any_prefix(const char* cname, const std::vector<std::string>& prefixes) {
    if (!cname) return false;
    if (prefixes.empty()) return true;
    for (const auto& prefix : prefixes) {
        size_t plen = prefix.size();
        if (strncmp(cname, prefix.c_str(), plen) == 0) {
            char next = cname[plen];
            if (next == '\0' || next == '$') {
                return true;
            }
        }
    }
    return false;
}

bool is_framework_class_name(const char* cname) {
    if (!cname) return true;
    return (strncmp(cname, "androidx.", 9) == 0) ||
           (strncmp(cname, "android.", 8) == 0) ||
           (strncmp(cname, "java.", 5) == 0) ||
           (strncmp(cname, "javax.", 6) == 0) ||
           (strncmp(cname, "kotlin.", 7) == 0) ||
           (strncmp(cname, "kotlinx.", 8) == 0) ||
           (strncmp(cname, "dalvik.", 7) == 0) ||
           (strncmp(cname, "com.android.", 12) == 0) ||
           (strncmp(cname, "sun.", 4) == 0);
}

std::string sanitize_single_line(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    bool last_was_space = true;
    for (char c : raw) {
        if (c == '\r' || c == '\n' || c == '\t') {
            if (!last_was_space) {
                out.push_back(' ');
                last_was_space = true;
            }
        } else {
            out.push_back(c);
            last_was_space = (c == ' ');
        }
    }
    while (!out.empty() && out.back() == ' ') {
        out.pop_back();
    }
    return out;
}

std::string jstring_to_std_string(JNIEnv* env, jstring jstr) {
    if (!env || !jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string res(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return res;
}

std::string format_java_throwable(
    JNIEnv* env,
    jthrowable throwable,
    const std::vector<std::string>& redefined_prefixes
) {
    if (!env || !throwable) return "";

    jclass throwable_cls = env->FindClass("java/lang/Throwable");
    jclass class_cls = env->FindClass("java/lang/Class");
    jclass ste_cls = env->FindClass("java/lang/StackTraceElement");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!throwable_cls || !class_cls || !ste_cls) {
        if (throwable_cls) env->DeleteLocalRef(throwable_cls);
        if (class_cls) env->DeleteLocalRef(class_cls);
        if (ste_cls) env->DeleteLocalRef(ste_cls);
        return "";
    }

    jmethodID get_cause_mid = env->GetMethodID(throwable_cls, "getCause", "()Ljava/lang/Throwable;");
    jmethodID get_msg_mid = env->GetMethodID(throwable_cls, "getMessage", "()Ljava/lang/String;");
    jmethodID get_stack_mid = env->GetMethodID(throwable_cls, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    jmethodID get_simple_name_mid = env->GetMethodID(class_cls, "getSimpleName", "()Ljava/lang/String;");
    jmethodID get_name_mid = env->GetMethodID(class_cls, "getName", "()Ljava/lang/String;");
    jmethodID ste_class_mid = env->GetMethodID(ste_cls, "getClassName", "()Ljava/lang/String;");
    jmethodID ste_file_mid = env->GetMethodID(ste_cls, "getFileName", "()Ljava/lang/String;");
    jmethodID ste_line_mid = env->GetMethodID(ste_cls, "getLineNumber", "()I");
    if (env->ExceptionCheck()) env->ExceptionClear();

    jthrowable current = static_cast<jthrowable>(env->NewLocalRef(throwable));
    for (int depth = 0; depth < 4 && get_cause_mid && current; depth++) {
        jthrowable cause = static_cast<jthrowable>(env->CallObjectMethod(current, get_cause_mid));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            cause = nullptr;
        }
        if (!cause || env->IsSameObject(cause, current)) {
            if (cause) env->DeleteLocalRef(cause);
            break;
        }
        env->DeleteLocalRef(current);
        current = cause;
    }

    jclass exc_cls = env->GetObjectClass(current);
    std::string exc_name;
    if (exc_cls) {
        if (get_simple_name_mid) {
            jstring jsimple = static_cast<jstring>(env->CallObjectMethod(exc_cls, get_simple_name_mid));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                jsimple = nullptr;
            }
            if (jsimple) {
                exc_name = jstring_to_std_string(env, jsimple);
                env->DeleteLocalRef(jsimple);
            }
        }
        if (exc_name.empty() && get_name_mid) {
            jstring jfull = static_cast<jstring>(env->CallObjectMethod(exc_cls, get_name_mid));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                jfull = nullptr;
            }
            if (jfull) {
                std::string full = jstring_to_std_string(env, jfull);
                env->DeleteLocalRef(jfull);
                size_t sep = full.find_last_of(".$");
                exc_name = (sep != std::string::npos) ? full.substr(sep + 1) : full;
            }
        }
        env->DeleteLocalRef(exc_cls);
    }
    if (exc_name.empty()) {
        exc_name = "RuntimeException";
    }

    std::string exc_msg;
    if (get_msg_mid) {
        jstring jmsg = static_cast<jstring>(env->CallObjectMethod(current, get_msg_mid));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            jmsg = nullptr;
        }
        if (jmsg) {
            exc_msg = sanitize_single_line(jstring_to_std_string(env, jmsg));
            env->DeleteLocalRef(jmsg);
        }
    }

    std::string best_redefined_loc;
    std::string best_app_loc;
    std::string fallback_loc;

    if (get_stack_mid && ste_class_mid && ste_file_mid && ste_line_mid) {
        jobjectArray frames = static_cast<jobjectArray>(env->CallObjectMethod(current, get_stack_mid));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            frames = nullptr;
        }
        if (frames) {
            jsize len = env->GetArrayLength(frames);
            for (jsize i = 0; i < len; i++) {
                jobject frame = env->GetObjectArrayElement(frames, i);
                if (env->ExceptionCheck() || !frame) {
                    env->ExceptionClear();
                    continue;
                }
                jstring jcname = static_cast<jstring>(env->CallObjectMethod(frame, ste_class_mid));
                if (env->ExceptionCheck()) { env->ExceptionClear(); jcname = nullptr; }
                jstring jfname = static_cast<jstring>(env->CallObjectMethod(frame, ste_file_mid));
                if (env->ExceptionCheck()) { env->ExceptionClear(); jfname = nullptr; }
                jint line = env->CallIntMethod(frame, ste_line_mid);
                if (env->ExceptionCheck()) { env->ExceptionClear(); line = -1; }

                std::string cname = jstring_to_std_string(env, jcname);
                std::string fname = jstring_to_std_string(env, jfname);
                if (jcname) env->DeleteLocalRef(jcname);
                if (jfname) env->DeleteLocalRef(jfname);
                env->DeleteLocalRef(frame);

                if (!fname.empty() && line > 0) {
                    std::string loc = fname + ":" + std::to_string(line);
                    if (fallback_loc.empty()) {
                        fallback_loc = loc;
                    }
                    if (!redefined_prefixes.empty() &&
                        matches_any_prefix(cname.c_str(), redefined_prefixes) &&
                        best_redefined_loc.empty()) {
                        best_redefined_loc = loc;
                        break;
                    }
                    if (!is_framework_class_name(cname.c_str()) && best_app_loc.empty()) {
                        best_app_loc = loc;
                    }
                }
            }
            env->DeleteLocalRef(frames);
        }
    }

    env->DeleteLocalRef(current);
    env->DeleteLocalRef(throwable_cls);
    env->DeleteLocalRef(class_cls);
    env->DeleteLocalRef(ste_cls);

    std::string chosen_loc = !best_redefined_loc.empty()
        ? best_redefined_loc
        : (!best_app_loc.empty() ? best_app_loc : fallback_loc);

    std::string formatted = exc_msg.empty() ? exc_name : (exc_name + ": " + exc_msg);
    if (!chosen_loc.empty()) {
        formatted += " (" + chosen_loc + ")";
    }
    return sanitize_single_line(formatted);
}

bool inspect_app_cached_lambda(
    JNIEnv* env,
    jobject elem,
    jclass class_cls,
    jmethodID get_name_mid,
    jclass composable_lambda_cls,
    jfieldID block_fid,
    jclass kotlin_func_cls,
    const std::vector<std::string>& redefined_prefixes,
    bool* out_matches_redefined
) {
    if (out_matches_redefined) *out_matches_redefined = false;
    if (!elem || !class_cls || !get_name_mid) return false;

    jobject target = elem;
    bool is_composable_lambda = false;
    if (composable_lambda_cls && env->IsInstanceOf(elem, composable_lambda_cls)) {
        is_composable_lambda = true;
        if (block_fid) {
            target = env->GetObjectField(elem, block_fid);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                target = nullptr;
            }
        }
        if (!target) {
            if (out_matches_redefined) *out_matches_redefined = true;
            return true;
        }
    }

    bool is_kotlin_func = kotlin_func_cls && env->IsInstanceOf(target, kotlin_func_cls);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        is_kotlin_func = false;
    }

    jclass target_cls = env->GetObjectClass(target);
    if (target != elem) {
        env->DeleteLocalRef(target);
    }
    if (!target_cls) return false;

    jstring jname = static_cast<jstring>(env->CallObjectMethod(target_cls, get_name_mid));
    env->DeleteLocalRef(target_cls);
    if (env->ExceptionCheck() || !jname) {
        env->ExceptionClear();
        return false;
    }

    const char* cname = env->GetStringUTFChars(jname, nullptr);
    bool is_app_lambda = false;
    if (cname) {
        bool is_framework = (strncmp(cname, "androidx.", 9) == 0) ||
                            (strncmp(cname, "java.", 5) == 0) ||
                            (strncmp(cname, "kotlin.", 7) == 0) ||
                            (strncmp(cname, "kotlinx.", 8) == 0) ||
                            (strncmp(cname, "android.", 8) == 0);
        if (!is_framework) {
            if (is_composable_lambda ||
                is_kotlin_func ||
                strstr(cname, "$$ExternalSynthetic") != nullptr ||
                strstr(cname, "$lambda") != nullptr ||
                strstr(cname, "$Lambda") != nullptr) {
                is_app_lambda = true;
                if (out_matches_redefined) {
                    *out_matches_redefined = matches_any_prefix(cname, redefined_prefixes);
                }
            }
        }
        env->ReleaseStringUTFChars(jname, cname);
    }
    env->DeleteLocalRef(jname);
    return is_app_lambda;
}

jint resolve_anchor_group_index(
    JNIEnv* env,
    jobject anc,
    jobject slot_table,
    jmethodID st_anchor_index_mid,
    jmethodID to_index_mid,
    jint groups_size
) {
    if (!anc) return -1;
    if (st_anchor_index_mid && slot_table) {
        jint idx = env->CallIntMethod(slot_table, st_anchor_index_mid, anc);
        if (!env->ExceptionCheck()) {
            return idx;
        }
        env->ExceptionClear();
    }
    if (to_index_mid && slot_table) {
        jint idx = env->CallIntMethod(anc, to_index_mid, slot_table);
        if (!env->ExceptionCheck()) {
            return idx;
        }
        env->ExceptionClear();
    }
    jclass anc_runtime_cls = env->GetObjectClass(anc);
    if (!anc_runtime_cls) return -1;
    jfieldID loc_fid = env->GetFieldID(anc_runtime_cls, "location", "I");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jint idx = -1;
    if (loc_fid) {
        jint loc = env->GetIntField(anc, loc_fid);
        if (!env->ExceptionCheck() && loc != INT32_MIN) {
            idx = (loc >= 0) ? loc : (groups_size + loc);
        } else {
            env->ExceptionClear();
        }
    }
    env->DeleteLocalRef(anc_runtime_cls);
    return idx;
}

bool is_preserved_scope_slot(
    JNIEnv* env,
    jobject elem,
    jobject empty_slot_obj,
    jclass state_cls,
    jclass remember_obs_cls,
    jclass remember_holder_cls,
    jclass recompose_scope_cls,
    jclass comp_local_map_cls
) {
    if (!elem) return true;
    if (empty_slot_obj && env->IsSameObject(elem, empty_slot_obj)) return true;
    if (state_cls && env->IsInstanceOf(elem, state_cls)) return true;
    if (remember_obs_cls && env->IsInstanceOf(elem, remember_obs_cls)) return true;
    if (remember_holder_cls && env->IsInstanceOf(elem, remember_holder_cls)) return true;
    if (recompose_scope_cls && env->IsInstanceOf(elem, recompose_scope_cls)) return true;
    if (comp_local_map_cls && env->IsInstanceOf(elem, comp_local_map_cls)) return true;
    return false;
}

int clear_stale_lambda_slots(
    JNIEnv* env,
    jobject class_loader,
    jmethodID load_class_mid,
    jobject comp,
    jclass comp_cls,
    jobject empty_slot_obj,
    jclass class_cls,
    jmethodID get_name_mid,
    jclass composable_lambda_cls,
    jfieldID block_fid,
    jclass kotlin_func_cls,
    const std::vector<std::string>& redefined_prefixes,
    bool allow_fallback,
    bool* out_comp_matched
) {
    if (out_comp_matched) *out_comp_matched = false;
    if (!comp || !comp_cls || !empty_slot_obj) return 0;

    jfieldID slot_table_fid = env->GetFieldID(comp_cls, "slotTable", "Landroidx/compose/runtime/SlotTable;");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!slot_table_fid) {
        slot_table_fid = env->GetFieldID(comp_cls, "slotStorage", "Landroidx/compose/runtime/SlotStorage;");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (!slot_table_fid) return 0;

    jobject slot_table = env->GetObjectField(comp, slot_table_fid);
    if (env->ExceptionCheck() || !slot_table) {
        env->ExceptionClear();
        return 0;
    }

    jclass st_cls = env->GetObjectClass(slot_table);
    jmethodID get_slots_mid = st_cls ? env->GetMethodID(st_cls, "getSlots", "()[Ljava/lang/Object;") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jobjectArray slots = get_slots_mid
        ? static_cast<jobjectArray>(env->CallObjectMethod(slot_table, get_slots_mid))
        : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        slots = nullptr;
    }
    if (!slots && st_cls) {
        jfieldID slots_fid = env->GetFieldID(st_cls, "slots", "[Ljava/lang/Object;");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (slots_fid) {
            slots = static_cast<jobjectArray>(env->GetObjectField(slot_table, slots_fid));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                slots = nullptr;
            }
        }
    }

    jfieldID groups_fid = st_cls ? env->GetFieldID(st_cls, "groups", "[I") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jfieldID groups_size_fid = st_cls ? env->GetFieldID(st_cls, "groupsSize", "I") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jfieldID slots_size_fid = st_cls ? env->GetFieldID(st_cls, "slotsSize", "I") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID st_anchor_index_mid = st_cls
        ? env->GetMethodID(st_cls, "anchorIndex", "(Landroidx/compose/runtime/Anchor;)I")
        : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jint groups_size = (groups_size_fid && slot_table) ? env->GetIntField(slot_table, groups_size_fid) : 0;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        groups_size = 0;
    }
    jint slots_size = (slots_size_fid && slot_table) ? env->GetIntField(slot_table, slots_size_fid) : 0;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        slots_size = 0;
    }

    jclass recompose_scope_cls = load_app_class(
        env,
        class_loader,
        load_class_mid,
        "androidx.compose.runtime.RecomposeScopeImpl"
    );
    jfieldID scope_block_fid = recompose_scope_cls
        ? env->GetFieldID(recompose_scope_cls, "block", "Lkotlin/jvm/functions/Function2;")
        : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jfieldID scope_anchor_fid = recompose_scope_cls
        ? env->GetFieldID(recompose_scope_cls, "anchor", "Landroidx/compose/runtime/Anchor;")
        : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass anchor_cls = load_app_class(
        env,
        class_loader,
        load_class_mid,
        "androidx.compose.runtime.Anchor"
    );
    jmethodID to_index_mid = anchor_cls
        ? env->GetMethodID(anchor_cls, "toIndexFor", "(Landroidx/compose/runtime/SlotTable;)I")
        : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    std::vector<jint> matched_scope_groups;
    std::vector<jint> all_app_scope_groups;
    std::vector<std::pair<jint, jsize>> scope_slot_indices;
    std::vector<jsize> matched_lambda_slots;
    std::vector<jsize> all_app_lambda_slots;

    if (slots) {
        jsize len = env->GetArrayLength(slots);
        if (slots_size <= 0 || slots_size > len) {
            slots_size = len;
        }
        for (jsize k = 0; k < len; k++) {
            jobject elem = env->GetObjectArrayElement(slots, k);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                continue;
            }
            if (!elem) continue;
            if (recompose_scope_cls && scope_block_fid && scope_anchor_fid &&
                env->IsInstanceOf(elem, recompose_scope_cls)) {
                jobject blk = env->GetObjectField(elem, scope_block_fid);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    blk = nullptr;
                }
                bool matches_redefined = false;
                if (blk && inspect_app_cached_lambda(
                        env, blk, class_cls, get_name_mid, nullptr, nullptr, kotlin_func_cls,
                        redefined_prefixes, &matches_redefined)) {
                    jobject anc = env->GetObjectField(elem, scope_anchor_fid);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        anc = nullptr;
                    }
                    if (anc) {
                        jint g_idx = resolve_anchor_group_index(
                            env, anc, slot_table, st_anchor_index_mid, to_index_mid, groups_size
                        );
                        if (g_idx >= 0) {
                            all_app_scope_groups.push_back(g_idx);
                            scope_slot_indices.push_back({g_idx, k});
                            if (matches_redefined) {
                                matched_scope_groups.push_back(g_idx);
                            }
                        }
                        env->DeleteLocalRef(anc);
                    }
                }
                if (blk) env->DeleteLocalRef(blk);
            } else {
                bool matches_redefined = false;
                if (inspect_app_cached_lambda(
                        env, elem, class_cls, get_name_mid, composable_lambda_cls, block_fid,
                        kotlin_func_cls, redefined_prefixes, &matches_redefined)) {
                    all_app_lambda_slots.push_back(k);
                    if (matches_redefined) {
                        matched_lambda_slots.push_back(k);
                    }
                }
            }
            env->DeleteLocalRef(elem);
        }
    }

    bool comp_matched = !matched_scope_groups.empty() || !matched_lambda_slots.empty();
    if (out_comp_matched) {
        *out_comp_matched = comp_matched;
    }

    if (!comp_matched && !allow_fallback) {
        if (slots) env->DeleteLocalRef(slots);
        if (recompose_scope_cls) env->DeleteLocalRef(recompose_scope_cls);
        if (anchor_cls) env->DeleteLocalRef(anchor_cls);
        if (st_cls) env->DeleteLocalRef(st_cls);
        env->DeleteLocalRef(slot_table);
        return 0;
    }

    const std::vector<jsize>& slots_to_clear =
        comp_matched ? matched_lambda_slots : all_app_lambda_slots;
    if (slots) {
        for (jsize slot_idx : slots_to_clear) {
            env->SetObjectArrayElement(slots, slot_idx, empty_slot_obj);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    const std::vector<jint>& target_scope_groups =
        !matched_scope_groups.empty() ? matched_scope_groups : all_app_scope_groups;

    int extra_cleared_slots = 0;
    if (!target_scope_groups.empty() && groups_fid && groups_size > 0) {
        jintArray groups_arr = static_cast<jintArray>(env->GetObjectField(slot_table, groups_fid));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            groups_arr = nullptr;
        }
        if (groups_arr) {
            jint* g_ptr = env->GetIntArrayElements(groups_arr, nullptr);
            if (g_ptr) {
                if (slots) {
                    jsize len = env->GetArrayLength(slots);
                    jclass state_cls = load_app_class(
                        env, class_loader, load_class_mid, "androidx.compose.runtime.State"
                    );
                    jclass remember_obs_cls = load_app_class(
                        env, class_loader, load_class_mid, "androidx.compose.runtime.RememberObserver"
                    );
                    jclass remember_holder_cls = load_app_class(
                        env, class_loader, load_class_mid, "androidx.compose.runtime.RememberObserverHolder"
                    );
                    jclass comp_local_map_cls = load_app_class(
                        env, class_loader, load_class_mid, "androidx.compose.runtime.CompositionLocalMap"
                    );

                    for (jint scope_g : target_scope_groups) {
                        if (scope_g < 0 || scope_g >= groups_size) continue;
                        jint slot_start = g_ptr[scope_g * 5 + 4];
                        jint slot_end = (scope_g + 1 < groups_size) ? g_ptr[(scope_g + 1) * 5 + 4] : slots_size;
                        for (const auto& pair : scope_slot_indices) {
                            if (pair.first == scope_g && pair.second + 1 > slot_start) {
                                slot_start = pair.second + 1;
                                break;
                            }
                        }
                        if (slot_start < 0) slot_start = 0;
                        if (slot_end > len) slot_end = len;
                        for (jint s = slot_start; s < slot_end; s++) {
                            jobject elem = env->GetObjectArrayElement(slots, s);
                            if (env->ExceptionCheck()) {
                                env->ExceptionClear();
                                continue;
                            }
                            if (!elem) continue;
                            bool preserve = is_preserved_scope_slot(
                                env, elem, empty_slot_obj, state_cls, remember_obs_cls,
                                remember_holder_cls, recompose_scope_cls, comp_local_map_cls
                            );
                            env->DeleteLocalRef(elem);
                            if (!preserve) {
                                env->SetObjectArrayElement(slots, s, empty_slot_obj);
                                if (env->ExceptionCheck()) env->ExceptionClear();
                                extra_cleared_slots++;
                            }
                        }
                    }

                    if (state_cls) env->DeleteLocalRef(state_cls);
                    if (remember_obs_cls) env->DeleteLocalRef(remember_obs_cls);
                    if (remember_holder_cls) env->DeleteLocalRef(remember_holder_cls);
                    if (comp_local_map_cls) env->DeleteLocalRef(comp_local_map_cls);
                }

                int bashed = 0;
                for (jint g = 0; g < groups_size; g++) {
                    jint key = g_ptr[g * 5 + 0];
                    jint info = g_ptr[g * 5 + 1];
                    jint parent = g_ptr[g * 5 + 2];
                    jint g_size = g_ptr[g * 5 + 3];
                    bool is_node_group = (key == 125 || (info & 0x40000000) != 0);

                    bool skip_group = false;
                    for (jint app_scope_g : all_app_scope_groups) {
                        if (g == app_scope_g || (!is_node_group && app_scope_g > g && app_scope_g < g + g_size)) {
                            skip_group = true;
                            break;
                        }
                    }
                    if (skip_group) continue;

                    // Bash any direct UI child group (ReusableComposeNode key==125 or any group with LayoutNodes)
                    // while keeping top-level non-UI state hook groups (nodeCount == 0) and child restart scopes intact.
                    if (is_node_group || (info & 0x43FFFFFF) != 0) {
                        for (jint scope_g : target_scope_groups) {
                            if (parent == scope_g) {
                                g_ptr[g * 5 + 0] = -3;
                                bashed++;
                                break;
                            }
                        }
                    }
                }
                env->ReleaseIntArrayElements(groups_arr, g_ptr, 0);
                if (bashed > 0) {
                    LOGI("Bashed %d UI child group(s) in SlotTable.groups", bashed);
                }
            }
            env->DeleteLocalRef(groups_arr);
        }
    }

    if (slots) env->DeleteLocalRef(slots);
    if (recompose_scope_cls) env->DeleteLocalRef(recompose_scope_cls);
    if (anchor_cls) env->DeleteLocalRef(anchor_cls);

    if (st_cls) env->DeleteLocalRef(st_cls);
    env->DeleteLocalRef(slot_table);
    int base_cleared = static_cast<int>(slots_to_clear.size());
    return base_cleared + extra_cleared_slots;
}

std::string extract_compose_recomposer_error(
    JNIEnv* env,
    jobject class_loader,
    jmethodID load_class_mid,
    const std::vector<std::string>& redefined_prefixes
) {
    if (!env || !class_loader || !load_class_mid) return "";

    jclass list_cls = env->FindClass("java/util/List");
    jmethodID size_mid = list_cls ? env->GetMethodID(list_cls, "size", "()I") : nullptr;
    jmethodID get_mid = list_cls ? env->GetMethodID(list_cls, "get", "(I)Ljava/lang/Object;") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!list_cls || !size_mid || !get_mid) {
        if (list_cls) env->DeleteLocalRef(list_cls);
        return "";
    }

    std::string formatted;

    // 1. Check HotReloaderKt.getCurrentCompositionErrors() / currentCompositionErrors() -> List<Pair<Throwable, Boolean>>
    jclass hot_reloader_kt = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.HotReloaderKt");
    if (hot_reloader_kt) {
        jmethodID get_errs_mid = env->GetStaticMethodID(hot_reloader_kt, "getCurrentCompositionErrors", "()Ljava/util/List;");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (!get_errs_mid) {
            get_errs_mid = env->GetStaticMethodID(hot_reloader_kt, "currentCompositionErrors", "()Ljava/util/List;");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (get_errs_mid) {
            jobject errs_list = env->CallStaticObjectMethod(hot_reloader_kt, get_errs_mid);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                errs_list = nullptr;
            }
            if (errs_list) {
                jint sz = env->CallIntMethod(errs_list, size_mid);
                if (!env->ExceptionCheck() && sz > 0) {
                    jobject pair_obj = env->CallObjectMethod(errs_list, get_mid, 0);
                    if (!env->ExceptionCheck() && pair_obj) {
                        jclass pair_cls = env->GetObjectClass(pair_obj);
                        jmethodID first_mid = pair_cls ? env->GetMethodID(pair_cls, "getFirst", "()Ljava/lang/Object;") : nullptr;
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        jobject cause_obj = first_mid ? env->CallObjectMethod(pair_obj, first_mid) : nullptr;
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                            cause_obj = nullptr;
                        }
                        if (cause_obj) {
                            formatted = format_java_throwable(env, static_cast<jthrowable>(cause_obj), redefined_prefixes);
                            env->DeleteLocalRef(cause_obj);
                        }
                        if (pair_cls) env->DeleteLocalRef(pair_cls);
                        env->DeleteLocalRef(pair_obj);
                    } else {
                        env->ExceptionClear();
                    }
                } else {
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(errs_list);
            }
        }
        env->DeleteLocalRef(hot_reloader_kt);
    }

    // 2. Fallback: Check HotReloader.Companion.getRecomposerErrors() / getCurrentErrors()
    if (formatted.empty()) {
        jclass hot_reloader_cls = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.HotReloader");
        if (hot_reloader_cls) {
            jfieldID companion_fid = env->GetStaticFieldID(
                hot_reloader_cls,
                "Companion",
                "Landroidx/compose/runtime/HotReloader$Companion;"
            );
            if (env->ExceptionCheck()) env->ExceptionClear();
            jobject companion_obj = companion_fid ? env->GetStaticObjectField(hot_reloader_cls, companion_fid) : nullptr;
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                companion_obj = nullptr;
            }
            if (companion_obj) {
                jclass comp_cls = env->GetObjectClass(companion_obj);
                jmethodID get_rec_errs_mid = comp_cls
                    ? env->GetMethodID(comp_cls, "getRecomposerErrors", "()Ljava/util/List;")
                    : nullptr;
                if (env->ExceptionCheck()) env->ExceptionClear();
                if (!get_rec_errs_mid && comp_cls) {
                    get_rec_errs_mid = env->GetMethodID(comp_cls, "getCurrentErrors", "()Ljava/util/List;");
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
                if (get_rec_errs_mid) {
                    jobject errs_list = env->CallObjectMethod(companion_obj, get_rec_errs_mid);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        errs_list = nullptr;
                    }
                    if (errs_list) {
                        jint sz = env->CallIntMethod(errs_list, size_mid);
                        if (!env->ExceptionCheck() && sz > 0) {
                            jobject err_info = env->CallObjectMethod(errs_list, get_mid, 0);
                            if (!env->ExceptionCheck() && err_info) {
                                jclass info_cls = env->GetObjectClass(err_info);
                                jmethodID cause_mid = info_cls
                                    ? env->GetMethodID(info_cls, "getCause", "()Ljava/lang/Throwable;")
                                    : nullptr;
                                if (env->ExceptionCheck()) env->ExceptionClear();
                                if (!cause_mid && info_cls) {
                                    cause_mid = env->GetMethodID(info_cls, "getCause", "()Ljava/lang/Exception;");
                                    if (env->ExceptionCheck()) env->ExceptionClear();
                                }
                                jobject cause_obj = cause_mid ? env->CallObjectMethod(err_info, cause_mid) : nullptr;
                                if (env->ExceptionCheck()) {
                                    env->ExceptionClear();
                                    cause_obj = nullptr;
                                }
                                if (cause_obj) {
                                    formatted = format_java_throwable(
                                        env,
                                        static_cast<jthrowable>(cause_obj),
                                        redefined_prefixes
                                    );
                                    env->DeleteLocalRef(cause_obj);
                                }
                                if (info_cls) env->DeleteLocalRef(info_cls);
                                env->DeleteLocalRef(err_info);
                            } else {
                                env->ExceptionClear();
                            }
                        } else {
                            env->ExceptionClear();
                        }
                        env->DeleteLocalRef(errs_list);
                    }
                }
                if (comp_cls) env->DeleteLocalRef(comp_cls);
                env->DeleteLocalRef(companion_obj);
            }
            env->DeleteLocalRef(hot_reloader_cls);
        }
    }

    env->DeleteLocalRef(list_cls);
    return formatted;
}

bool query_recomposer_work_state(
    JNIEnv* env,
    jobject class_loader,
    jmethodID load_class_mid,
    jlong* out_total_change_count
) {
    if (out_total_change_count) *out_total_change_count = 0;
    if (!env || !class_loader || !load_class_mid) return false;

    jclass recomposer_cls = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.Recomposer");
    if (!recomposer_cls) return false;

    jmethodID get_running_mid = env->GetStaticMethodID(
        recomposer_cls,
        "access$get_runningRecomposers$cp",
        "()Lkotlinx/coroutines/flow/MutableStateFlow;"
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
    jobject state_flow = get_running_mid ? env->CallStaticObjectMethod(recomposer_cls, get_running_mid) : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        state_flow = nullptr;
    }

    bool any_pending = false;
    jlong total_changes = 0;

    if (state_flow) {
        jclass sf_cls = env->GetObjectClass(state_flow);
        jmethodID get_val_mid = sf_cls ? env->GetMethodID(sf_cls, "getValue", "()Ljava/lang/Object;") : nullptr;
        if (env->ExceptionCheck()) env->ExceptionClear();
        jobject recomposer_set = get_val_mid ? env->CallObjectMethod(state_flow, get_val_mid) : nullptr;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            recomposer_set = nullptr;
        }
        if (recomposer_set) {
            jclass iterable_cls = env->FindClass("java/lang/Iterable");
            jclass iterator_cls = env->FindClass("java/util/Iterator");
            jmethodID iterator_mid = iterable_cls ? env->GetMethodID(iterable_cls, "iterator", "()Ljava/util/Iterator;") : nullptr;
            jmethodID has_next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "hasNext", "()Z") : nullptr;
            jmethodID next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "next", "()Ljava/lang/Object;") : nullptr;

            jobject iter = iterator_mid ? env->CallObjectMethod(recomposer_set, iterator_mid) : nullptr;
            while (iter && has_next_mid && env->CallBooleanMethod(iter, has_next_mid)) {
                jobject info_obj = env->CallObjectMethod(iter, next_mid);
                if (!info_obj) continue;
                jclass info_cls = env->GetObjectClass(info_obj);
                if (info_cls) {
                    jmethodID pending_mid = env->GetMethodID(info_cls, "getHasPendingWork", "()Z");
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    if (pending_mid) {
                        jboolean pending = env->CallBooleanMethod(info_obj, pending_mid);
                        if (!env->ExceptionCheck() && pending) {
                            any_pending = true;
                        } else if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        }
                    }
                    jmethodID changes_mid = env->GetMethodID(info_cls, "getChangeCount", "()J");
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    if (changes_mid) {
                        jlong count = env->CallLongMethod(info_obj, changes_mid);
                        if (!env->ExceptionCheck()) {
                            total_changes += count;
                        } else {
                            env->ExceptionClear();
                        }
                    }
                    env->DeleteLocalRef(info_cls);
                }
                env->DeleteLocalRef(info_obj);
            }
            if (iter) env->DeleteLocalRef(iter);
            if (iterable_cls) env->DeleteLocalRef(iterable_cls);
            if (iterator_cls) env->DeleteLocalRef(iterator_cls);
            env->DeleteLocalRef(recomposer_set);
        }
        if (sf_cls) env->DeleteLocalRef(sf_cls);
        env->DeleteLocalRef(state_flow);
    }
    env->DeleteLocalRef(recomposer_cls);

    if (out_total_change_count) *out_total_change_count = total_changes;
    return any_pending;
}

std::string await_compose_recomposition_error(
    JNIEnv* env,
    jobject class_loader,
    jmethodID load_class_mid,
    const std::vector<std::string>& redefined_prefixes,
    jlong initial_change_count
) {
    bool saw_pending = false;
    for (int attempt = 0; attempt < 10; attempt++) {
        usleep(15000); // 15ms per tick (~1 Choreographer frame)
        std::string err = extract_compose_recomposer_error(env, class_loader, load_class_mid, redefined_prefixes);
        if (!err.empty()) {
            return err;
        }
        jlong current_change_count = 0;
        bool has_pending = query_recomposer_work_state(env, class_loader, load_class_mid, &current_change_count);
        if (has_pending) {
            saw_pending = true;
        }
        if (current_change_count > initial_change_count || (saw_pending && !has_pending) || (attempt >= 5 && !has_pending)) {
            return extract_compose_recomposer_error(env, class_loader, load_class_mid, redefined_prefixes);
        }
    }
    return extract_compose_recomposer_error(env, class_loader, load_class_mid, redefined_prefixes);
}

void invalidate_single_composition(
    JNIEnv* env,
    jobject comp,
    jclass comp_cls,
    bool* out_triggered
) {
    jfieldID composer_fid = env->GetFieldID(comp_cls, "composer", "Landroidx/compose/runtime/ComposerImpl;");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!composer_fid) {
        composer_fid = env->GetFieldID(comp_cls, "composer", "Landroidx/compose/runtime/InternalComposer;");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (composer_fid) {
        jobject composer_obj = env->GetObjectField(comp, composer_fid);
        if (composer_obj) {
            jclass composer_cls = env->GetObjectClass(composer_obj);
            jmethodID force_mid = env->GetMethodID(composer_cls, "forceRecomposeScopes$runtime", "()Z");
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (!force_mid) {
                force_mid = env->GetMethodID(composer_cls, "forceRecomposeScopes", "()Z");
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            if (force_mid) {
                env->CallBooleanMethod(composer_obj, force_mid);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            env->DeleteLocalRef(composer_cls);
            env->DeleteLocalRef(composer_obj);
        }
    }
    jmethodID inv_all_mid = env->GetMethodID(comp_cls, "invalidateAll", "()V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (inv_all_mid) {
        env->CallVoidMethod(comp, inv_all_mid);
        if (!env->ExceptionCheck()) {
            if (out_triggered) *out_triggered = true;
            LOGI("Called CompositionImpl.invalidateAll()");
        } else {
            env->ExceptionClear();
        }
    }
}

bool trigger_compose_hot_reload(
    JNIEnv* env,
    jobject class_loader,
    jmethodID load_class_mid,
    const std::vector<std::string>& redefined_prefixes,
    bool* out_matched_any_scope,
    jlong* out_initial_change_count
) {
    bool triggered = false;
    if (out_matched_any_scope) *out_matched_any_scope = false;

    jlong initial_change_count = 0;
    query_recomposer_work_state(env, class_loader, load_class_mid, &initial_change_count);
    if (out_initial_change_count) *out_initial_change_count = initial_change_count;

    jclass hot_reloader_kt = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.HotReloaderKt");
    if (hot_reloader_kt) {
        jmethodID clear_err_mid = env->GetStaticMethodID(hot_reloader_kt, "clearCompositionErrors", "()V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (clear_err_mid) {
            env->CallStaticVoidMethod(hot_reloader_kt, clear_err_mid);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        jmethodID inv_key_mid = env->GetStaticMethodID(hot_reloader_kt, "invalidateGroupsWithKey", "(I)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (inv_key_mid) {
            // Switch Recomposer into hot-reload mode (_hotReloadEnabled = true) before invalidating scopes
            // so any recomposition error on the main thread is captured in Recomposer.errorState.
            env->CallStaticVoidMethod(hot_reloader_kt, inv_key_mid, static_cast<jint>(-3));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    jobject empty_slot_obj = nullptr;
    jclass composer_itf = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.Composer");
    if (composer_itf) {
        jfieldID comp_companion_fid = env->GetStaticFieldID(
            composer_itf,
            "Companion",
            "Landroidx/compose/runtime/Composer$Companion;"
        );
        if (env->ExceptionCheck()) env->ExceptionClear();
        jobject comp_companion = comp_companion_fid
            ? env->GetStaticObjectField(composer_itf, comp_companion_fid)
            : nullptr;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            comp_companion = nullptr;
        }
        if (comp_companion) {
            jclass cc_cls = env->GetObjectClass(comp_companion);
            jmethodID get_empty_mid = cc_cls ? env->GetMethodID(cc_cls, "getEmpty", "()Ljava/lang/Object;") : nullptr;
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (get_empty_mid) {
                empty_slot_obj = env->CallObjectMethod(comp_companion, get_empty_mid);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    empty_slot_obj = nullptr;
                }
            }
            if (cc_cls) env->DeleteLocalRef(cc_cls);
            env->DeleteLocalRef(comp_companion);
        }
        env->DeleteLocalRef(composer_itf);
    }

    jclass composable_lambda_cls = load_app_class(
        env,
        class_loader,
        load_class_mid,
        "androidx.compose.runtime.internal.ComposableLambdaImpl"
    );
    jfieldID block_fid = composable_lambda_cls
        ? env->GetFieldID(composable_lambda_cls, "_block", "Ljava/lang/Object;")
        : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass kotlin_func_cls = load_app_class(env, class_loader, load_class_mid, "kotlin.Function");
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass class_cls = env->FindClass("java/lang/Class");
    jmethodID get_name_mid = class_cls ? env->GetMethodID(class_cls, "getName", "()Ljava/lang/String;") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass recomposer_cls = load_app_class(env, class_loader, load_class_mid, "androidx.compose.runtime.Recomposer");
    if (recomposer_cls) {
        jmethodID get_running_mid = env->GetStaticMethodID(
            recomposer_cls,
            "access$get_runningRecomposers$cp",
            "()Lkotlinx/coroutines/flow/MutableStateFlow;"
        );
        if (env->ExceptionCheck()) env->ExceptionClear();
        jmethodID known_comps_mid = env->GetStaticMethodID(
            recomposer_cls,
            "access$knownCompositions",
            "(Landroidx/compose/runtime/Recomposer;)Ljava/util/List;"
        );
        if (env->ExceptionCheck()) env->ExceptionClear();

        jobject state_flow = get_running_mid ? env->CallStaticObjectMethod(recomposer_cls, get_running_mid) : nullptr;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            state_flow = nullptr;
        }
        if (state_flow) {
            jclass sf_cls = env->GetObjectClass(state_flow);
            jmethodID get_val_mid = sf_cls ? env->GetMethodID(sf_cls, "getValue", "()Ljava/lang/Object;") : nullptr;
            if (env->ExceptionCheck()) env->ExceptionClear();
            jobject recomposer_set = get_val_mid ? env->CallObjectMethod(state_flow, get_val_mid) : nullptr;
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                recomposer_set = nullptr;
            }
            if (recomposer_set && known_comps_mid) {
                jclass iterable_cls = env->FindClass("java/lang/Iterable");
                jclass iterator_cls = env->FindClass("java/util/Iterator");
                jclass list_cls = env->FindClass("java/util/List");
                jmethodID iterator_mid = iterable_cls ? env->GetMethodID(iterable_cls, "iterator", "()Ljava/util/Iterator;") : nullptr;
                jmethodID has_next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "hasNext", "()Z") : nullptr;
                jmethodID next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "next", "()Ljava/lang/Object;") : nullptr;
                jmethodID size_mid = list_cls ? env->GetMethodID(list_cls, "size", "()I") : nullptr;
                jmethodID get_mid = list_cls ? env->GetMethodID(list_cls, "get", "(I)Ljava/lang/Object;") : nullptr;

                jobject iter = iterator_mid ? env->CallObjectMethod(recomposer_set, iterator_mid) : nullptr;
                while (iter && has_next_mid && env->CallBooleanMethod(iter, has_next_mid)) {
                    jobject info_obj = env->CallObjectMethod(iter, next_mid);
                    if (!info_obj) continue;
                    jclass info_cls = env->GetObjectClass(info_obj);
                    jfieldID this0_fid = info_cls ? env->GetFieldID(info_cls, "this$0", "Landroidx/compose/runtime/Recomposer;") : nullptr;
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    jobject recomposer_obj = this0_fid ? env->GetObjectField(info_obj, this0_fid) : nullptr;
                    if (recomposer_obj) {
                        jobject comps_list = env->CallStaticObjectMethod(recomposer_cls, known_comps_mid, recomposer_obj);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                            comps_list = nullptr;
                        }
                        if (comps_list && size_mid && get_mid) {
                            jint count = env->CallIntMethod(comps_list, size_mid);
                            bool any_comp_matched = false;

                            // Pass 1: Only clear and invalidate compositions that directly contain redefined scopes/lambdas.
                            for (jint i = 0; i < count; i++) {
                                jobject comp = env->CallObjectMethod(comps_list, get_mid, i);
                                if (!comp) continue;
                                jclass comp_cls = env->GetObjectClass(comp);
                                bool comp_matched = false;

                                int cleared_slots = clear_stale_lambda_slots(
                                    env,
                                    class_loader,
                                    load_class_mid,
                                    comp,
                                    comp_cls,
                                    empty_slot_obj,
                                    class_cls,
                                    get_name_mid,
                                    composable_lambda_cls,
                                    block_fid,
                                    kotlin_func_cls,
                                    redefined_prefixes,
                                    false,
                                    &comp_matched
                                );
                                if (cleared_slots > 0) {
                                    LOGI("Cleared %d stale cached slot(s) in CompositionImpl", cleared_slots);
                                }
                                if (comp_matched) {
                                    any_comp_matched = true;
                                    if (out_matched_any_scope) *out_matched_any_scope = true;
                                    invalidate_single_composition(env, comp, comp_cls, &triggered);
                                }
                                env->DeleteLocalRef(comp_cls);
                                env->DeleteLocalRef(comp);
                            }

                            // Pass 2 (Fallback): If no composition matched directly (e.g., helper class edited),
                            // fall back to clearing app scopes across compositions.
                            if (!any_comp_matched) {
                                for (jint i = 0; i < count; i++) {
                                    jobject comp = env->CallObjectMethod(comps_list, get_mid, i);
                                    if (!comp) continue;
                                    jclass comp_cls = env->GetObjectClass(comp);
                                    bool comp_matched = false;

                                    int cleared_slots = clear_stale_lambda_slots(
                                        env,
                                        class_loader,
                                        load_class_mid,
                                        comp,
                                        comp_cls,
                                        empty_slot_obj,
                                        class_cls,
                                        get_name_mid,
                                        composable_lambda_cls,
                                        block_fid,
                                        kotlin_func_cls,
                                        redefined_prefixes,
                                        true,
                                        &comp_matched
                                    );
                                    if (cleared_slots > 0) {
                                        LOGI("Cleared %d stale cached slot(s) in CompositionImpl (fallback)", cleared_slots);
                                    }
                                    invalidate_single_composition(env, comp, comp_cls, &triggered);
                                    env->DeleteLocalRef(comp_cls);
                                    env->DeleteLocalRef(comp);
                                }
                            }
                            env->DeleteLocalRef(comps_list);
                        }
                        env->DeleteLocalRef(recomposer_obj);
                    }
                    if (info_cls) {
                        jmethodID retry_mid = env->GetMethodID(info_cls, "retryFailedCompositions", "()V");
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        if (retry_mid) {
                            env->CallVoidMethod(info_obj, retry_mid);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                        }
                        env->DeleteLocalRef(info_cls);
                    }
                    env->DeleteLocalRef(info_obj);
                }
                if (iter) env->DeleteLocalRef(iter);
                if (iterable_cls) env->DeleteLocalRef(iterable_cls);
                if (iterator_cls) env->DeleteLocalRef(iterator_cls);
                if (list_cls) env->DeleteLocalRef(list_cls);
                env->DeleteLocalRef(recomposer_set);
            }
            if (sf_cls) env->DeleteLocalRef(sf_cls);
            env->DeleteLocalRef(state_flow);
        }
        env->DeleteLocalRef(recomposer_cls);
    }

    if (hot_reloader_kt) {
        jmethodID inv_key_mid = env->GetStaticMethodID(hot_reloader_kt, "invalidateGroupsWithKey", "(I)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (inv_key_mid) {
            env->CallStaticVoidMethod(hot_reloader_kt, inv_key_mid, static_cast<jint>(-3));
            if (!env->ExceptionCheck()) {
                triggered = true;
                LOGI("Called HotReloaderKt.invalidateGroupsWithKey(-3)");
            } else {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(hot_reloader_kt);
    }

    if (empty_slot_obj) env->DeleteLocalRef(empty_slot_obj);
    if (composable_lambda_cls) env->DeleteLocalRef(composable_lambda_cls);
    if (kotlin_func_cls) env->DeleteLocalRef(kotlin_func_cls);
    if (class_cls) env->DeleteLocalRef(class_cls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return triggered;
}

bool trigger_activity_recreate(JNIEnv* env, jclass activity_thread_cls) {
    if (!activity_thread_cls) return false;
    jmethodID current_thread_mid = env->GetStaticMethodID(
        activity_thread_cls,
        "currentActivityThread",
        "()Landroid/app/ActivityThread;"
    );
    if (env->ExceptionCheck() || !current_thread_mid) {
        env->ExceptionClear();
        return false;
    }
    jobject act_thread = env->CallStaticObjectMethod(activity_thread_cls, current_thread_mid);
    if (env->ExceptionCheck() || !act_thread) {
        env->ExceptionClear();
        return false;
    }

    jfieldID activities_fid = env->GetFieldID(activity_thread_cls, "mActivities", "Landroid/util/ArrayMap;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        activities_fid = env->GetFieldID(activity_thread_cls, "mActivities", "Ljava/util/Map;");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    jmethodID sched_relaunch_mid = env->GetMethodID(
        activity_thread_cls,
        "scheduleRelaunchActivity",
        "(Landroid/os/IBinder;)V"
    );
    if (env->ExceptionCheck()) env->ExceptionClear();

    bool recreated = false;
    if (activities_fid && sched_relaunch_mid) {
        jobject activities_map = env->GetObjectField(act_thread, activities_fid);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            activities_map = nullptr;
        }
        if (activities_map) {
            jclass map_cls = env->FindClass("java/util/Map");
            jmethodID values_mid = map_cls ? env->GetMethodID(map_cls, "values", "()Ljava/util/Collection;") : nullptr;
            if (env->ExceptionCheck()) env->ExceptionClear();
            jobject values_col = values_mid ? env->CallObjectMethod(activities_map, values_mid) : nullptr;
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                values_col = nullptr;
            }
            if (values_col) {
                jclass iterable_cls = env->FindClass("java/lang/Iterable");
                jclass iterator_cls = env->FindClass("java/util/Iterator");
                jmethodID iter_mid = iterable_cls ? env->GetMethodID(iterable_cls, "iterator", "()Ljava/util/Iterator;") : nullptr;
                jmethodID has_next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "hasNext", "()Z") : nullptr;
                jmethodID next_mid = iterator_cls ? env->GetMethodID(iterator_cls, "next", "()Ljava/lang/Object;") : nullptr;

                jobject iter = iter_mid ? env->CallObjectMethod(values_col, iter_mid) : nullptr;
                while (iter && has_next_mid && env->CallBooleanMethod(iter, has_next_mid)) {
                    jobject record = env->CallObjectMethod(iter, next_mid);
                    if (!record) continue;
                    jclass rec_cls = env->GetObjectClass(record);
                    jfieldID stopped_fid = rec_cls ? env->GetFieldID(rec_cls, "stopped", "Z") : nullptr;
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    jfieldID token_fid = rec_cls ? env->GetFieldID(rec_cls, "token", "Landroid/os/IBinder;") : nullptr;
                    if (env->ExceptionCheck()) env->ExceptionClear();

                    jboolean is_stopped = stopped_fid ? env->GetBooleanField(record, stopped_fid) : JNI_FALSE;
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    jobject token = (!is_stopped && token_fid) ? env->GetObjectField(record, token_fid) : nullptr;
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        token = nullptr;
                    }
                    if (token) {
                        env->CallVoidMethod(act_thread, sched_relaunch_mid, token);
                        if (!env->ExceptionCheck()) {
                            recreated = true;
                            LOGI("Scheduled activity relaunch via ActivityThread.scheduleRelaunchActivity");
                        } else {
                            env->ExceptionClear();
                        }
                        env->DeleteLocalRef(token);
                    }
                    if (rec_cls) env->DeleteLocalRef(rec_cls);
                    env->DeleteLocalRef(record);
                }
                if (iter) env->DeleteLocalRef(iter);
                if (iterable_cls) env->DeleteLocalRef(iterable_cls);
                if (iterator_cls) env->DeleteLocalRef(iterator_cls);
                env->DeleteLocalRef(values_col);
            }
            if (map_cls) env->DeleteLocalRef(map_cls);
            env->DeleteLocalRef(activities_map);
        }
    }
    env->DeleteLocalRef(act_thread);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return recreated;
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
    jmethodID load_class_mid = cl_cls ? env->GetMethodID(cl_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") : nullptr;

    struct LoadedClassEntry {
        jclass klass;
        std::string binary_name;
        std::string dex_path;
    };
    std::vector<LoadedClassEntry> loaded_classes;
    int new_classes_count = 0;

    for (const auto& payload : class_payloads) {
        if (payload.dex_path.empty()) {
            new_classes_count++;
            continue;
        }
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
            loaded_classes.push_back({loaded_cls, payload.binary_name, payload.dex_path});
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

    // Resolve structural redefinition extension function once
    typedef jvmtiError (JNICALL *ArtStructuralRedefineFunc)(jvmtiEnv*, jint, const jvmtiClassDefinition*);
    ArtStructuralRedefineFunc structural_redefine_fn = nullptr;
    {
        jint ext_count = 0;
        jvmtiExtensionFunctionInfo* ext_funcs = nullptr;
        if (jvmti->GetExtensionFunctions(&ext_count, &ext_funcs) == JVMTI_ERROR_NONE && ext_funcs) {
            for (int i = 0; i < ext_count; i++) {
                if (ext_funcs[i].id &&
                    (strcmp(ext_funcs[i].id, "com.android.art.class.structurally_redefine_classes") == 0 ||
                     strcmp(ext_funcs[i].id, "com.android.art.class.structural_redefine_classes") == 0)) {
                    structural_redefine_fn = reinterpret_cast<ArtStructuralRedefineFunc>(ext_funcs[i].func);
                    break;
                }
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(ext_funcs));
        }
    }

    int redefined_count = 0;
    bool used_structural = false;

    for (auto& item : loaded_classes) {
        jclass klass = item.klass;
        const std::string& dex_path = item.dex_path;

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
            LOGI("RedefineClasses succeeded for %s", item.binary_name.c_str());
        } else {
            LOGI("Standard RedefineClasses for %s returned error: %d, attempting structural redefine",
                 item.binary_name.c_str(), redefErr);
            bool structuralApplied = false;
            if (structural_redefine_fn) {
                jvmtiError sErr = structural_redefine_fn(jvmti, 1, &def);
                if (sErr == JVMTI_ERROR_NONE) {
                    structuralApplied = true;
                    used_structural = true;
                    redefined_count++;
                    LOGI("Structural redefinition succeeded for %s!", item.binary_name.c_str());
                } else {
                    LOGE("Structural redefinition for %s failed with error: %d", item.binary_name.c_str(), sErr);
                }
            }

            if (!structuralApplied) {
                char buffer[128];
                snprintf(buffer, sizeof(buffer), "status=STRUCTURAL_INCOMPATIBLE reason=JVMTI_ERROR_%d\n", redefErr);
                write_result(result_path, buffer);
                for (auto& entry : loaded_classes) {
                    env->DeleteLocalRef(entry.klass);
                }
                if (activity_thread_cls) env->DeleteLocalRef(activity_thread_cls);
                if (cl_cls) env->DeleteLocalRef(cl_cls);
                if (class_loader) env->DeleteLocalRef(class_loader);
                if (app_obj) env->DeleteLocalRef(app_obj);
                return 0;
            }
        }
    }

    jclass activity_base_cls = env->FindClass("android/app/Activity");
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass view_base_cls = env->FindClass("android/view/View");
    if (env->ExceptionCheck()) env->ExceptionClear();

    bool activity_or_view_edited = false;
    bool has_composable_singleton_edit = false;
    std::vector<std::string> redefined_prefixes;
    std::string runtime_error;

    // Re-run <clinit> on any redefined ComposableSingletons$ classes so new and updated
    // lambda$ static fields hold fresh ComposableLambda instances.
    for (auto& item : loaded_classes) {
        redefined_prefixes.push_back(item.binary_name);
        size_t cs_pos = item.binary_name.find("ComposableSingletons$");
        if (cs_pos != std::string::npos) {
            has_composable_singleton_edit = true;
            std::string outer_prefix = item.binary_name.substr(0, cs_pos) +
                                       item.binary_name.substr(cs_pos + strlen("ComposableSingletons$"));
            redefined_prefixes.push_back(outer_prefix);

            jclass target_cls = load_app_class(env, class_loader, load_class_mid, item.binary_name.c_str());
            if (!target_cls) target_cls = item.klass;
            jmethodID clinit_mid = env->GetStaticMethodID(target_cls, "<clinit>", "()V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (clinit_mid) {
                env->CallStaticVoidMethod(target_cls, clinit_mid);
                if (env->ExceptionCheck()) {
                    jthrowable clinit_exc = env->ExceptionOccurred();
                    env->ExceptionClear();
                    if (clinit_exc) {
                        if (runtime_error.empty()) {
                            runtime_error = format_java_throwable(env, clinit_exc, redefined_prefixes);
                        }
                        env->DeleteLocalRef(clinit_exc);
                    }
                    LOGE("Exception re-running <clinit> on %s", item.binary_name.c_str());
                } else {
                    LOGI("Re-initialized <clinit> on %s", item.binary_name.c_str());
                }
            }
            if (target_cls != item.klass) {
                env->DeleteLocalRef(target_cls);
            }
        }
        if ((activity_base_cls && env->IsAssignableFrom(item.klass, activity_base_cls)) ||
            (view_base_cls && env->IsAssignableFrom(item.klass, view_base_cls))) {
            activity_or_view_edited = true;
        }
    }
    if (activity_base_cls) env->DeleteLocalRef(activity_base_cls);
    if (view_base_cls) env->DeleteLocalRef(view_base_cls);

    // UI Refresh
    std::string ui_mode = "NONE";
    bool matched_compose_scope = false;
    jlong initial_change_count = 0;
    bool composeSuccess = trigger_compose_hot_reload(
        env,
        class_loader,
        load_class_mid,
        redefined_prefixes,
        &matched_compose_scope,
        &initial_change_count
    );

    if (activity_or_view_edited) {
        if (trigger_activity_recreate(env, activity_thread_cls)) {
            ui_mode = "ACTIVITY_RECREATE";
        } else if (composeSuccess) {
            ui_mode = "COMPOSE_HOT_RELOAD";
        }
    } else if (composeSuccess) {
        ui_mode = "COMPOSE_HOT_RELOAD";
    } else if (trigger_activity_recreate(env, activity_thread_cls)) {
        ui_mode = "ACTIVITY_RECREATE";
    } else if (app_obj) {
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
    std::string base_result(buffer);

    for (auto& entry : loaded_classes) {
        env->DeleteLocalRef(entry.klass);
    }
    if (activity_thread_cls) env->DeleteLocalRef(activity_thread_cls);
    if (cl_cls) env->DeleteLocalRef(cl_cls);
    if (app_obj) env->DeleteLocalRef(app_obj);

    // Agent_OnAttach is invoked on the main UI thread (ActivityThread.handleAttachAgent).
    // Free the main thread immediately so Choreographer.doFrame can execute the recomposition pass,
    // while a detached worker thread awaits the recomposition result and writes result.txt.
    if (ui_mode == "COMPOSE_HOT_RELOAD" && runtime_error.empty() && class_loader != nullptr) {
        jobject global_class_loader = env->NewGlobalRef(class_loader);
        env->DeleteLocalRef(class_loader);
        std::thread([vm, global_class_loader, load_class_mid, redefined_prefixes, initial_change_count, result_path, base_result]() {
            JNIEnv* thread_env = nullptr;
            std::string async_error;
            if (vm->AttachCurrentThreadAsDaemon(&thread_env, nullptr) == JNI_OK && thread_env != nullptr) {
                async_error = await_compose_recomposition_error(
                    thread_env,
                    global_class_loader,
                    load_class_mid,
                    redefined_prefixes,
                    initial_change_count
                );
                if (!async_error.empty()) {
                    LOGE("Captured Compose recomposition error: %s", async_error.c_str());
                }
                if (global_class_loader) {
                    thread_env->DeleteGlobalRef(global_class_loader);
                }
                vm->DetachCurrentThread();
            }
            std::string final_content = base_result;
            if (!async_error.empty()) {
                final_content += "runtime_error=" + async_error + "\n";
            }
            write_result(result_path, final_content);
        }).detach();
        return 0;
    }

    if (class_loader) env->DeleteLocalRef(class_loader);
    if (!runtime_error.empty()) {
        base_result += "runtime_error=" + runtime_error + "\n";
    }
    write_result(result_path, base_result);

    return 0;
}
