"""Recover Colin AI's LoRA adapter (rank 16) from the merged checkpoint, so only ~18 MB needs to be stored.

    python extract_adapter.py <merged-hf-dir> <out-adapter-dir>

delta = merged - base is exactly rank <= 16 per linear layer, so a truncated SVD recovers it losslessly
(up to float rounding). The adapter is saved in PEFT format with alpha == r (scale 1).
"""
import sys

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM

BASE = "Qwen/Qwen2.5-0.5B-Instruct"
R = 16
TARGETS = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]

merged = AutoModelForCausalLM.from_pretrained(sys.argv[1], dtype=torch.float32).state_dict()
base = AutoModelForCausalLM.from_pretrained(BASE, dtype=torch.float32)
base_sd = {k: v.clone() for k, v in base.state_dict().items()}
other = [k for k in merged if not any(t in k for t in TARGETS) and not torch.equal(merged[k], base_sd[k])]
assert not other, f"non-LoRA weights changed: {other[:5]}"

peft_model = get_peft_model(base, LoraConfig(r=R, lora_alpha=R, target_modules=TARGETS, task_type="CAUSAL_LM"))
worst = 0.0
with torch.no_grad():
    for name, mod in peft_model.named_modules():
        if not hasattr(mod, "lora_A") or "default" not in mod.lora_A:
            continue
        key = name.replace("base_model.model.", "") + ".weight"
        delta = merged[key] - base_sd[key]
        U, S, Vh = torch.linalg.svd(delta, full_matrices=False)
        root = S[:R].sqrt()
        mod.lora_B["default"].weight.copy_(U[:, :R] * root)
        mod.lora_A["default"].weight.copy_(root[:, None] * Vh[:R])
        approx = (U[:, :R] * S[:R]) @ Vh[:R]
        worst = max(worst, ((approx - delta).norm() / delta.norm()).item())
print(f"worst relative reconstruction error: {worst:.2e}")
peft_model = peft_model.to(torch.float16)
peft_model.save_pretrained(sys.argv[2], safe_serialization=True)
print("saved", sys.argv[2])
