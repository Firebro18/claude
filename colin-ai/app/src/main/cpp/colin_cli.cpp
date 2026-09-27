// Desktop test harness for the engine: colin_cli model.gguf "question" ["question 2" ...]
#include <cstdio>
#include <iostream>

#include "colin_engine.h"

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s model.gguf question...\n", argv[0]); return 1; }
    colin::Engine e;
    std::string err = e.load(argv[1], 2048, 4);
    if (!err.empty()) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }
    std::vector<colin::Message> chat = {{"system", "You are Colin AI, a personal AI assistant made for Colin. You run fully offline on his phone. Be direct, warm, honest and helpful. Answer in the language the user writes in."}};
    for (int i = 2; i < argc; ++i) {
        chat.push_back({"user", argv[i]});
        std::cout << "\n>>> " << argv[i] << "\n";
        std::string reply;
        int n = e.generate(chat, 200, 0.0f, [&](const std::string& t) { std::cout << t << std::flush; reply += t; return true; });
        if (n < 0) { std::fprintf(stderr, "error: %s\n", e.last_error().c_str()); return 1; }
        chat.push_back({"assistant", reply});
        std::cout << "\n";
    }
    return 0;
}
