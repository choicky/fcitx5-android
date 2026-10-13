// PoC-only JNI facade for the Fusion Voice local text post-processor (task Gate B).
// llama.cpp v0.6.0 (d8123504) CPU-only, built statically into this one .so, arm64.
// The generation pipeline mirrors the host probe (raw-results/poc-probe.cpp) so device
// evidence and host evidence measure the same code path: mmap model load -> jinja chat
// template with enable_thinking=false -> greedy decode -> single-flight abort + deadline.
#include "llama.h"
#include "chat.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaPostProc", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "LlamaPostProc", __VA_ARGS__)

namespace {

double now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

long vm_hwm_kb() {
    FILE *f = fopen("/proc/self/status", "re");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof(line), f))
        if (strncmp(line, "VmHWM:", 6) == 0) { sscanf(line + 6, "%ld", &kb); break; }
    fclose(f);
    return kb;
}

struct Runtime {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    common_chat_templates_ptr templates;
    llama_sampler *sampler = nullptr;
    bool load_failed = false;
};

Runtime g_rt;

// Single-flight cancellation: nativeCancel(seq) aborts the generation currently running
// under that sequence id (a newer session's final superseded it); the wall-clock deadline
// bounds every generation independently of cancellation.
std::atomic<long> g_running_seq{-1};
std::atomic<long> g_cancel_seq{-1};
std::atomic<uint64_t> g_deadline_ms{0};

bool abort_cb(void *) {
    if (g_cancel_seq.load(std::memory_order_relaxed) == g_running_seq.load(std::memory_order_relaxed))
        return true;
    uint64_t d = g_deadline_ms.load(std::memory_order_relaxed);
    return d != 0 && (uint64_t) now_ms() > d;
}

void release_runtime() {
    if (g_rt.sampler) llama_sampler_free(g_rt.sampler);
    if (g_rt.ctx) llama_free(g_rt.ctx);
    if (g_rt.model) llama_model_free(g_rt.model);
    g_rt.sampler = nullptr;
    g_rt.ctx = nullptr;
    g_rt.model = nullptr;
    g_rt.templates.reset();
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LlamaPostProcessor_nativeLoad(
        JNIEnv *env, jobject, jstring model_path_j, jint threads, jint n_ctx) {
    if (g_rt.model) return JNI_TRUE;
    if (g_rt.load_failed) return JNI_FALSE;

    const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);
    std::string path(model_path);
    env->ReleaseStringUTFChars(model_path_j, model_path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;              // CPU only, by task contract
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // app-private mmap, as the preflight established

    double t0 = now_ms();
    g_rt.model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_rt.model) {
        LOGW("model load failed: %s", path.c_str());
        g_rt.load_failed = true;
        return JNI_FALSE;
    }
    double load_ms = now_ms() - t0;

    const llama_vocab *vocab = llama_model_get_vocab(g_rt.model);
    const char *tmpl_c = llama_model_chat_template(g_rt.model, nullptr);
    if (!tmpl_c) {
        LOGW("model has no chat template: %s", path.c_str());
        release_runtime();
        g_rt.load_failed = true;
        return JNI_FALSE;
    }
    g_rt.templates = common_chat_templates_init(g_rt.model, tmpl_c);

    // corpus items are 20-300 chars; a long final plus the frozen prompt exceeds 512 tokens
    // in one prefill batch, and llama_decode rejects a batch larger than n_batch
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 1024;
    cp.n_ubatch = 1024;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g_rt.ctx = llama_init_from_model(g_rt.model, cp);
    if (!g_rt.ctx) {
        LOGW("context init failed (ctx=%d threads=%d)", n_ctx, threads);
        release_runtime();
        g_rt.load_failed = true;
        return JNI_FALSE;
    }
    llama_set_abort_callback(g_rt.ctx, abort_cb, nullptr);

    g_rt.sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_rt.sampler, llama_sampler_init_greedy());

    LOGI("loaded %s in %.0f ms | threads=%d ctx=%d | VmHWM %ld kB",
         path.c_str(), load_ms, threads, n_ctx, vm_hwm_kb());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LlamaPostProcessor_nativeCancel(
        JNIEnv *, jobject, jlong seq) {
    g_cancel_seq.store(seq, std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_input_voice_LlamaPostProcessor_nativeProcess(
        JNIEnv *env, jobject, jlong seq, jstring raw_j, jstring system_prompt_j,
        jint max_tokens, jint deadline_ms) {
    if (!g_rt.model || !g_rt.ctx || !g_rt.sampler) return nullptr;

    const char *raw_c = env->GetStringUTFChars(raw_j, nullptr);
    std::string raw(raw_c);
    env->ReleaseStringUTFChars(raw_j, raw_c);
    const char *sys_c = env->GetStringUTFChars(system_prompt_j, nullptr);
    std::string sys(sys_c);
    env->ReleaseStringUTFChars(system_prompt_j, sys_c);
    const llama_vocab *vocab = llama_model_get_vocab(g_rt.model);

    std::vector<common_chat_msg> msgs(2);
    msgs[0].role = "system";
    msgs[0].content = sys;
    msgs[1].role = "user";
    msgs[1].content = raw;
    common_chat_templates_inputs inputs;
    inputs.messages = msgs;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.add_generation_prompt = true;
    common_chat_params cparams = common_chat_templates_apply(g_rt.templates.get(), inputs);

    std::vector<llama_token> toks(cparams.prompt.size() + raw.size() * 4 + 4096);
    int32_t n_tok = llama_tokenize(vocab, cparams.prompt.c_str(),
                                   (int32_t) cparams.prompt.size(), toks.data(),
                                   (int32_t) toks.size(), false, true);
    if (n_tok < 0) {
        LOGW("tokenize failed (seq %ld)", (long) seq);
        return nullptr;
    }

    g_running_seq.store(seq, std::memory_order_relaxed);
    g_cancel_seq.store(-1, std::memory_order_relaxed);
    g_deadline_ms.store((uint64_t) (now_ms() + deadline_ms), std::memory_order_relaxed);

    double t0 = now_ms();
    llama_batch batch = llama_batch_get_one(toks.data(), n_tok);
    if (llama_decode(g_rt.ctx, batch) != 0) {
        // a failed prefill must not poison later turns: reset the memory state
        llama_memory_seq_rm(llama_get_memory(g_rt.ctx), 0, -1, -1);
        g_deadline_ms.store(0, std::memory_order_relaxed);
        LOGI("seq %ld aborted during prefill after %.0f ms", (long) seq, now_ms() - t0);
        return nullptr;
    }
    double prefill_ms = now_ms() - t0;

    std::string text;
    double ttfo_ms = -1.0;
    bool eos = false, aborted = false;
    int generated = 0;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token tok = llama_sampler_sample(g_rt.sampler, g_rt.ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) { eos = true; break; }
        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) {
            if (ttfo_ms < 0) { text.assign(piece, n); ttfo_ms = now_ms() - t0; }
            else text.append(piece, n);
        }
        generated++;
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_rt.ctx, next) != 0) { aborted = true; break; }
    }
    double total_ms = now_ms() - t0;
    llama_memory_seq_rm(llama_get_memory(g_rt.ctx), 0, -1, -1);
    g_deadline_ms.store(0, std::memory_order_relaxed);

    LOGI("seq %ld | in %zu ch out %zu ch | prefill %.0f ttfo %.0f total %.0f ms | "
         "tok_out %d eos=%d aborted=%d | VmHWM %ld kB",
         (long) seq, raw.size(), text.size(), prefill_ms, ttfo_ms, total_ms,
         generated, eos, aborted, vm_hwm_kb());

    if (text.empty()) return nullptr;
    return env->NewStringUTF(text.c_str());
}

} // extern "C"
