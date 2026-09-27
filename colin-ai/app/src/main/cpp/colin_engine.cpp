#include "colin_engine.h"

#include <algorithm>
#include <mutex>

#include "llama.h"

namespace colin {

static std::once_flag g_backend_once;

Engine::~Engine() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
}

std::string Engine::load(const std::string& path, int n_ctx, int n_threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) return "Could not load model file: " + path;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) return "Could not create inference context";
    return "";
}

std::string Engine::format(const std::vector<Message>& chat) {
    std::vector<llama_chat_message> msgs;
    msgs.reserve(chat.size());
    for (const auto& m : chat) msgs.push_back({m.role.c_str(), m.content.c_str()});
    const char* tmpl = llama_model_chat_template(model_, nullptr);
    if (!tmpl) tmpl = "chatml";
    std::vector<char> buf(4096);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
    }
    if (n < 0) return "";
    return std::string(buf.data(), n);
}

// Length of the longest prefix of s that is complete UTF-8.
static size_t utf8_complete(const std::string& s) {
    size_t i = s.size();
    // Walk back over at most 3 continuation bytes to find the last lead byte.
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

int Engine::generate(const std::vector<Message>& chat, int max_tokens, float temperature,
                     const std::function<bool(const std::string&)>& on_text) {
    stop_ = false;
    error_.clear();
    const llama_vocab* vocab = llama_model_get_vocab(model_);
    const std::string prompt = format(chat);
    if (prompt.empty()) { error_ = "Chat template failed"; return -1; }

    int n = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), n, true, true) < 0) {
        error_ = "Tokenization failed";
        return -1;
    }
    const int n_ctx = (int)llama_n_ctx(ctx_);
    if ((int)tokens.size() + max_tokens > n_ctx) {
        max_tokens = std::max(64, n_ctx - (int)tokens.size());
        if ((int)tokens.size() + max_tokens > n_ctx) { error_ = "Conversation is too long, start a new chat"; return -1; }
    }

    // Reuse the KV cache for the shared prefix with the previous turn.
    size_t common = 0;
    while (common < cached_.size() && common < tokens.size() && cached_[common] == tokens[common]) ++common;
    if (common == tokens.size()) --common;  // always decode at least one token to get logits
    llama_memory_t mem = llama_get_memory(ctx_);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    cached_.assign(tokens.begin(), tokens.begin() + common);

    const int n_batch = (int)llama_n_batch(ctx_);
    for (size_t i = common; i < tokens.size(); i += n_batch) {
        int len = (int)std::min<size_t>(n_batch, tokens.size() - i);
        if (llama_decode(ctx_, llama_batch_get_one(tokens.data() + i, len)) != 0) {
            error_ = "Prompt evaluation failed";
            cached_.clear();
            llama_memory_clear(mem, true);
            return -1;
        }
        cached_.insert(cached_.end(), tokens.begin() + i, tokens.begin() + i + len);
        if (stop_) return 0;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    int produced = 0;
    char piece[256];
    while (produced < max_tokens && !stop_) {
        llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);
        size_t ok = utf8_complete(pending);
        if (ok > 0) {
            if (!on_text(pending.substr(0, ok))) stop_ = true;
            pending.erase(0, ok);
        }
        ++produced;
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) break;
        cached_.push_back(tok);
    }
    if (!pending.empty()) on_text(pending);
    llama_sampler_free(smpl);
    return produced;
}

}  // namespace colin
