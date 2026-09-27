#!/usr/bin/env bash
# Convert trained Hugging Face checkpoints into quantized GGUF files the app ships in assets/models.
#   ./build_models.sh <colin-ai-hf dir> <colin-mini-hf dir>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
llama="$here/../app/src/main/cpp/llama.cpp"
out="$here/../app/src/main/assets/models"
work="${WORK:-$(mktemp -d)}"
mkdir -p "$out"

if [ ! -x "$work/quant/bin/llama-quantize" ]; then
  cmake -S "$llama" -B "$work/quant" -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF >/dev/null
  cmake --build "$work/quant" --target llama-quantize -j"$(nproc)" >/dev/null
fi

python3 "$llama/convert_hf_to_gguf.py" "$1" --outtype f16 --outfile "$work/colin-ai-f16.gguf"
"$work/quant/bin/llama-quantize" "$work/colin-ai-f16.gguf" "$out/colin-ai.gguf" Q4_K_M

# Colin Mini is tiny, so keep it at 8-bit for best quality.
python3 "$llama/convert_hf_to_gguf.py" "$2" --outtype q8_0 --outfile "$out/colin-mini.gguf"

ls -lh "$out"
