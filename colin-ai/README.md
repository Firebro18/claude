# Colin AI

Colin's own AI for Android. It runs **100% offline on the phone**: no cloud AI, no API key, no internet
permission. Nothing typed into it ever leaves the device.

The app ships with two brains, both trained in this repo:

| Brain | What it is | Size |
|---|---|---|
| **Colin AI** (main) | [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct) (Apache-2.0), fine-tuned with LoRA into Colin AI: its own name, identity, personality and style, in English and German. | ~380 MB (Q4_K_M) |
| **Colin Mini** (experimental) | A brand-new 17M-parameter Llama-style network, trained **from scratch** (random weights) on simple chats, short stories and Colin's identity data. Only the tokenizer is borrowed from SmolLM2. | ~20 MB (Q8_0) |

Honest limits: these are small models. Colin AI can chat, explain, translate, summarize and brainstorm,
but it makes mistakes and knows nothing after its training data. Colin Mini is a demo of an AI grown from
nothing: it can greet you and tell simple stories, but it often talks nonsense.

## App features

- Pick the brain in Settings; set creativity (Precise / Balanced / Creative).
- **Training & memory** screen: teach it facts about you and write custom instructions. Say "remember that…" or
  "merk dir…" in a chat and it saves the fact automatically.
- Voice input, read-aloud, chat history, retry, copy, share text into the app.

## How it's built

```
training/colin_data.py     Colin's identity + style dataset (EN/DE)
training/finetune.py       LoRA fine-tune Qwen2.5-0.5B-Instruct -> Colin AI (CPU, ~1 h)
training/train_mini.py     Train Colin Mini from scratch (CPU, time-boxed)
training/build_models.sh   HF checkpoints -> quantized GGUF in app/src/main/assets/models
app/src/main/cpp/          On-device engine: llama.cpp (submodule) + C++/JNI wrapper
app/src/main/java/...      Kotlin + Jetpack Compose app
```

Rebuild everything:

```bash
git submodule update --init
pip install torch transformers peft datasets gguf
cd colin-ai/training
python finetune.py --out /tmp/colin-ai-hf
python train_mini.py prep  --work /tmp/mini
python train_mini.py train --work /tmp/mini --minutes 100
./build_models.sh /tmp/colin-ai-hf /tmp/mini/colin-mini-hf
cd .. && ./gradlew assembleRelease    # needs Android SDK 36, NDK 27, CMake 3.31
```

The GGUF model files are git-ignored (too large for GitHub), so the APK is attached to the chat/release instead.

Test the engine on a desktop without a phone:

```bash
cmake -S app/src/main/cpp -B /tmp/host && cmake --build /tmp/host -j
/tmp/host/colin_cli app/src/main/assets/models/colin-ai.gguf "Who are you?" "Wer hat dich gemacht?"
```
