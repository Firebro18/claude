// Colin AI on-device inference engine (thin layer over llama.cpp).
#pragma once
#include <atomic>
#include <functional>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;

namespace colin {

struct Message {
    std::string role;
    std::string content;
};

class Engine {
public:
    ~Engine();
    // Returns an empty string on success, otherwise an error message.
    std::string load(const std::string& path, int n_ctx, int n_threads);
    // Streams complete UTF-8 chunks to on_text; return false from it to stop. Returns tokens generated or -1.
    int generate(const std::vector<Message>& chat, int max_tokens, float temperature,
                 const std::function<bool(const std::string&)>& on_text);
    void stop() { stop_ = true; }
    std::string last_error() const { return error_; }

private:
    std::string format(const std::vector<Message>& chat);
    llama_model* model_ = nullptr;
    llama_context* ctx_ = nullptr;
    std::vector<int> cached_;  // tokens currently in the KV cache
    std::atomic<bool> stop_{false};
    std::string error_;
};

}  // namespace colin
