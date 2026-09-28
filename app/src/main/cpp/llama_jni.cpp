// llama_jni.cpp — JNI bridge between Room Browser and the vendored llama.cpp
// (app/src/main/cpp/llamacpp, trimmed @ 4364bf7, CPU-only).
//
// Exports (mirrored 1:1 by com.roombrowser.localai.engine.LlamaBridge):
//   jstring  nativeVersion()
//   jlong    nativeLoad(jstring path, jint contextTokens, jint threads)   // 0 = failure
//   void     nativeFree(jlong handle)
//   jstring  nativeGenerate(jlong handle, jstring prompt, jint maxTokens,
//                           jfloat temperature, jfloat topP)             // null = ERROR
//   void     nativeCancel(jlong handle)
//   jstring  nativeApplyChatTemplate(jlong handle, jobjectArray roles,
//                                    jobjectArray contents)              // null = no template
//
// Threading / lifetime model (relies on Kotlin-side discipline in LlamaEngine):
//  - LlamaEngine runs at most ONE nativeGenerate per handle at a time on a
//    single-lane dispatcher, and never frees a handle while a generate runs.
//  - Therefore nativeGenerate may RESET the per-handle cancel flag at entry
//    without racing a live generation, which keeps cancellation self-contained
//    (a stale `true` from an aborted previous run can never poison the next).
//  - The C++ std::mutex below only guards the handle map itself (lookup /
//    insert / erase); the long-running decode loop runs WITHOUT it, so
//    nativeCancel can always take the mutex and set the flag immediately.
//  - llama_backend_init() runs exactly once per process (std::once_flag);
//    llama_backend_free() is intentionally NEVER called — process lifetime.
//
// JNI string caveat: JNI strings are modified UTF-8 (CESU-8). Supplementary-
// plane characters (e.g. emoji) are encoded as surrogate pairs and may be
// mis-tokenized by the model's BPE tokenizer. Accepted honest limitation for
// v1 — the smoke-test path (plain ASCII stories) is unaffected.
//
// API deviations vs. older llama.cpp (adapted to the vendored 4364bf7 headers,
// verified against include/llama.h and ggml/include/ggml.h):
//  - llama_batch_get_one() now takes (tokens, n_tokens) ONLY: positions and
//    sequence ids are tracked automatically by llama_decode (compat layer in
//    src/llama-batch.cpp), so no manual `pos` bookkeeping is needed.
//  - ggml_abort_callback is `bool (*)(void*)` and is installed via
//    llama_set_abort_callback(ctx, cb, data).
//  - llama_chat_apply_template() no longer reads the model's template from a
//    model parameter; passing tmpl=nullptr now means "chatml". To honour "use
//    the model's built-in template" we first read the GGUF key
//    `tokenizer.chat_template` (fallback: `chat_template`) with
//    llama_model_meta_val_str() and pass THAT string as tmpl. No key → we
//    return null so Kotlin falls back to plain "role: content" formatting.
//    Jinja-style templates are not understood without LLAMA_LLGUIDANCE →
//    llama_chat_apply_template returns -1 → we also return null (honest).
//  - llama_model_params has no use_mmap field in this version (load_mode
//    enum instead) — we keep llama_model_default_params() untouched.
//  - llama_decode() return semantics: 0 = success, 1 = no KV slot (prompt
//    exceeds the context), 2 = ABORTED (our cancel callback fired — not a
//    negative!), <0 = fatal. We therefore treat ANY non-zero as stop; the
//    cancel flag then distinguishes "" (cancelled) from null (error) on the
//    prompt path, and returns partial text on the continuation path.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// One loaded engine: model + context + a cancel flag that the ggml abort
// callback polls while llama_decode is computing.
struct EngineCtx {
    llama_model *          model  = nullptr;
    llama_context *        ctx    = nullptr;
    std::atomic<bool>      cancel{false};
};

std::mutex               g_mutex;
std::map<jlong, EngineCtx> g_engines;   // std::map nodes are address-stable
jlong                    g_next_handle = 0;
std::once_flag           g_backend_once;

// ggml_abort_callback: return true → abort the running llama_decode.
bool abort_check(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load(std::memory_order_relaxed);
}

// Look up an engine under the mutex and copy out the raw pointers we need for
// a long-running call (the map entry itself stays alive and address-stable
// because Kotlin never frees a handle while a generate is in flight).
struct EngineRef {
    llama_model *         model;
    llama_context *       ctx;
    std::atomic<bool> *   cancel;
};

bool engine_lookup(jlong handle, EngineRef * out) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_engines.find(handle);
    if (it == g_engines.end()) {
        return false;
    }
    out->model  = it->second.model;
    out->ctx    = it->second.ctx;
    out->cancel = &it->second.cancel;
    return true;
}

// Read a GGUF string-valued metadata key from the model ("two-call"
// snprintf pattern: the first call with buf=nullptr yields the length).
bool model_meta_string(const llama_model * model, const char * key, std::string * out) {
    const int32_t len = llama_model_meta_val_str(model, key, nullptr, 0);
    if (len <= 0) {
        return false;
    }
    std::vector<char> buf(static_cast<size_t>(len) + 1);
    const int32_t len2 = llama_model_meta_val_str(model, key, buf.data(), buf.size());
    if (len2 <= 0) {
        return false;
    }
    out->assign(buf.data(), static_cast<size_t>(len2));
    return true;
}

// Release the UTF char buffers + local refs collected while building the
// llama_chat_message array (only the first `count` entries are live).
void release_chat_strings(JNIEnv * env,
                          const std::vector<jstring> & jroles,
                          const std::vector<jstring> & jcontents,
                          const std::vector<const char *> & croles,
                          const std::vector<const char *> & ccontents,
                          jsize count) {
    for (jsize i = 0; i < count && i < static_cast<jsize>(jroles.size()); ++i) {
        const jstring r = jroles[static_cast<size_t>(i)];
        const jstring c = jcontents[static_cast<size_t>(i)];
        if (croles[static_cast<size_t>(i)] != nullptr && r != nullptr) {
            env->ReleaseStringUTFChars(r, croles[static_cast<size_t>(i)]);
        }
        if (ccontents[static_cast<size_t>(i)] != nullptr && c != nullptr) {
            env->ReleaseStringUTFChars(c, ccontents[static_cast<size_t>(i)]);
        }
        if (r != nullptr) {
            env->DeleteLocalRef(r);
        }
        if (c != nullptr) {
            env->DeleteLocalRef(c);
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeVersion(JNIEnv * env, jclass /*clazz*/) {
    return env->NewStringUTF(llama_version());
}

JNIEXPORT jlong JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeLoad(
        JNIEnv * env, jclass /*clazz*/, jstring jpath, jint contextTokens, jint threads) {
    if (jpath == nullptr) {
        return 0;
    }

    // Backend init exactly once per process (never freed — process lifetime).
    std::call_once(g_backend_once, []() { llama_backend_init(); });

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return 0; // OOM / pending exception
    }

    // Model params: keep defaults (mmap-friendly load mode, no GPU offload —
    // this build is CPU-only).
    llama_model_params mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = contextTokens > 0 ? static_cast<uint32_t>(contextTokens) : 0; // 0 = model default
    cparams.n_batch         = 512;
    cparams.n_threads       = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    // Register the handle FIRST (std::map nodes are address-stable), then
    // point the abort callback at the registered cancel flag.
    jlong handle = 0;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        handle = ++g_next_handle;
        EngineCtx & e = g_engines[handle]; // default-constructed: cancel=false
        e.model = model;
        e.ctx   = ctx;
        llama_set_abort_callback(e.ctx, abort_check, &e.cancel);
    }
    return handle;
}

JNIEXPORT void JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeFree(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    llama_context * ctx = nullptr;
    llama_model * model = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_engines.find(handle);
        if (it == g_engines.end()) {
            return; // never registered or already freed — idempotent no-op
        }
        ctx   = it->second.ctx;
        model = it->second.model;
        g_engines.erase(it);
    }
    // Freed outside the map lock so a slow free never blocks cancel/lookup.
    // Safe against a concurrent nativeGenerate ONLY because Kotlin's single
    // generation lane never frees while a generate is in flight.
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (model != nullptr) {
        llama_model_free(model);
    }
    // NOTE: llama_backend_free() deliberately NOT called (process lifetime).
}

JNIEXPORT jstring JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass /*clazz*/, jlong handle, jstring jprompt, jint maxTokens,
        jfloat temperature, jfloat topP) {
    if (jprompt == nullptr) {
        return nullptr;
    }

    EngineRef ref;
    if (!engine_lookup(handle, &ref)) {
        return nullptr; // invalid handle → ERROR (null)
    }

    // Reset the cancel flag at entry. The Kotlin side guarantees at most one
    // nativeGenerate per handle at a time (single lane + mutex), so this can
    // never race a live generation — it only clears a stale flag left by an
    // aborted previous run.
    ref.cancel->store(false, std::memory_order_release);

    const llama_vocab * vocab = llama_model_get_vocab(ref.model);

    // --- Tokenize the prompt (two-call pattern: negative → needed count) ---
    const char * text = env->GetStringUTFChars(jprompt, nullptr);
    if (text == nullptr) {
        return nullptr;
    }
    std::string prompt(text); // copy so the JNI chars can be released now
    env->ReleaseStringUTFChars(jprompt, text);

    int32_t n_req = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                   nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_req == std::numeric_limits<int32_t>::min()) {
        return nullptr; // tokenization overflow → ERROR
    }
    if (n_req < 0) {
        n_req = -n_req;
    }
    if (n_req <= 0) {
        // Nothing to condition on (empty prompt + model adds no BOS).
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_req));
    const int32_t n_tok = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                         tokens.data(), static_cast<int32_t>(tokens.size()),
                                         /*add_special=*/true, /*parse_special=*/true);
    if (n_tok <= 0) {
        return nullptr; // second pass failed → ERROR
    }
    tokens.resize(static_cast<size_t>(n_tok));

    // --- Sampler chain ---
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (smpl == nullptr) {
        return nullptr;
    }
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(
                (topP > 0.0f && topP < 1.0f) ? topP : 0.95f, /*min_keep=*/1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        // CRITICAL: the chain must END with a token-SELECTING sampler. top_p and
        // temp only FILTER candidates — they never set cur_p.selected, so
        // llama_sampler_sample()'s GGML_ASSERT(cur_p.selected >= 0) fires
        // (SIGABRT → process crash; reproduced on a host harness against the
        // bundled stories260K smoke model, llama-sampler.cpp:956). dist picks
        // the actual token from the filtered distribution — exactly the
        // simple.cpp upstream pattern.
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // --- Feed the whole prompt in n_batch-sized chunks ---
    // (llama_decode asserts n_tokens <= n_batch; positions are tracked
    // automatically by the compat layer, seq_id defaults to 0.)
    // Return semantics in this llama.cpp version: 0 = success, 1 = no KV
    // slot (prompt exceeds context), 2 = aborted (cancel), <0 = fatal — so
    // ANY non-zero stops the feed; the cancel flag then decides "" vs null.
    const int32_t kBatch = 512; // mirrors n_batch set in nativeLoad
    bool prompt_failed = false;
    for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(kBatch)) {
        const int32_t n_i = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(kBatch), tokens.size() - i));
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_i);
        if (llama_decode(ref.ctx, batch) != 0) {
            prompt_failed = true; // fatal error, KV-full or abort-by-cancel
            break;
        }
        if (ref.cancel->load(std::memory_order_relaxed)) {
            break; // cancelled between chunks — nothing generated yet
        }
    }
    if (prompt_failed || ref.cancel->load(std::memory_order_relaxed)) {
        llama_sampler_free(smpl);
        // Cancelled before the first token → "" (NOT null; null means error).
        return ref.cancel->load(std::memory_order_relaxed)
                ? env->NewStringUTF("") : nullptr;
    }

    // --- Generation loop ---
    // After the prompt decode the logits for the NEXT token are already
    // available; sample → append piece → decode the sampled token → repeat.
    std::string out;
    int generated = 0;
    while (generated < maxTokens) {
        const llama_token tok = llama_sampler_sample(smpl, ref.ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        // Detokenize one piece (retry with the exact size if 64 bytes is
        // too small — llama_token_to_piece returns -(needed) in that case).
        char piece[64];
        int32_t n = llama_token_to_piece(vocab, tok, piece, static_cast<int32_t>(sizeof(piece) - 1),
                                         /*lstrip=*/0, /*special=*/false);
        if (n < 0) {
            std::vector<char> big(static_cast<size_t>(-n) + 1);
            n = llama_token_to_piece(vocab, tok, big.data(),
                                     static_cast<int32_t>(big.size() - 1), 0, false);
            if (n > 0) {
                out.append(big.data(), static_cast<size_t>(n));
            }
        } else if (n > 0) {
            out.append(piece, static_cast<size_t>(n));
        }
        generated += 1;

        if (generated >= maxTokens) {
            break;
        }
        if (ref.cancel->load(std::memory_order_relaxed)) {
            break; // partial output after cancel is returned as-is
        }

        // Decode the sampled token so logits for the next prediction exist.
        // Any non-zero return (1 = KV full / context exhausted, 2 = aborted,
        // <0 = fatal) stops generation and returns the partial text.
        llama_token t = tok;
        llama_batch batch = llama_batch_get_one(&t, 1);
        if (llama_decode(ref.ctx, batch) != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);
    // Never null here: "" when nothing was generated (e.g. cancelled before
    // the first token), the (possibly partial) continuation otherwise.
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeCancel(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_engines.find(handle);
    if (it != g_engines.end()) {
        it->second.cancel.store(true, std::memory_order_release);
    }
}

JNIEXPORT jstring JNICALL
Java_com_roombrowser_localai_engine_LlamaBridge_nativeApplyChatTemplate(
        JNIEnv * env, jclass /*clazz*/, jlong handle, jobjectArray roles, jobjectArray contents) {
    EngineRef ref;
    if (!engine_lookup(handle, &ref)) {
        return nullptr; // invalid handle → ERROR
    }
    if (roles == nullptr || contents == nullptr) {
        return nullptr;
    }
    const jsize n_roles = env->GetArrayLength(roles);
    const jsize n_contents = env->GetArrayLength(contents);
    if (n_roles != n_contents || n_roles <= 0) {
        return nullptr; // malformed input (Kotlin always passes equal lengths)
    }

    // The model's own chat template (this llama.cpp version does not read it
    // from the model when tmpl=nullptr — nullptr means "chatml" instead).
    std::string tmpl_buf;
    if (!model_meta_string(ref.model, "tokenizer.chat_template", &tmpl_buf) &&
        !model_meta_string(ref.model, "chat_template", &tmpl_buf)) {
        return nullptr; // model has no usable template → Kotlin plain fallback
    }
    const char * tmpl = tmpl_buf.c_str();

    // Keep the JNI strings + their UTF char pointers alive for the duration
    // of llama_chat_apply_template, then release everything.
    std::vector<jstring>        jroles(static_cast<size_t>(n_roles));
    std::vector<jstring>        jcontents(static_cast<size_t>(n_roles));
    std::vector<const char *>   croles(static_cast<size_t>(n_roles), nullptr);
    std::vector<const char *>   ccontents(static_cast<size_t>(n_roles), nullptr);
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n_roles));

    for (jsize i = 0; i < n_roles; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto c = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (r == nullptr || c == nullptr) {
            release_chat_strings(env, jroles, jcontents, croles, ccontents, i);
            return nullptr;
        }
        jroles[static_cast<size_t>(i)] = r;
        jcontents[static_cast<size_t>(i)] = c;
        croles[static_cast<size_t>(i)] = env->GetStringUTFChars(r, nullptr);
        ccontents[static_cast<size_t>(i)] = env->GetStringUTFChars(c, nullptr);
        if (croles[static_cast<size_t>(i)] == nullptr || ccontents[static_cast<size_t>(i)] == nullptr) {
            release_chat_strings(env, jroles, jcontents, croles, ccontents, i + 1);
            return nullptr;
        }
        msgs[static_cast<size_t>(i)].role = croles[static_cast<size_t>(i)];
        msgs[static_cast<size_t>(i)].content = ccontents[static_cast<size_t>(i)];
    }

    // add_ass=true appends the assistant header so generation starts right
    // after it — mirrors the plain-text fallback ("assistant: " suffix).
    const int32_t needed = llama_chat_apply_template(
            tmpl, msgs.data(), msgs.size(), /*add_ass=*/true, nullptr, 0);
    jstring result = nullptr;
    if (needed > 0) {
        std::string buf(static_cast<size_t>(needed) + 1, '\0');
        const int32_t written = llama_chat_apply_template(
                tmpl, msgs.data(), msgs.size(), true, buf.data(),
                static_cast<int32_t>(buf.size()) - 1);
        if (written > 0) {
            result = env->NewStringUTF(buf.c_str());
        }
        // written <= 0 (e.g. jinja template not understood) → nullptr →
        // Kotlin falls back to plain formatting. Honest, not an error.
    }
    // needed <= 0: model template unknown to this build → nullptr.

    release_chat_strings(env, jroles, jcontents, croles, ccontents, n_roles);
    return result;
}

} // extern "C"
