"""Rebuild the full Colin AI model: free base model + Colin's trained LoRA adapter.

    python merge_adapter.py colin-ai-adapter <out-hf-dir>
"""
import sys

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

BASE = "Qwen/Qwen2.5-0.5B-Instruct"
model = AutoModelForCausalLM.from_pretrained(BASE, dtype=torch.float32)
model = PeftModel.from_pretrained(model, sys.argv[1]).merge_and_unload()
model.save_pretrained(sys.argv[2], safe_serialization=True)
AutoTokenizer.from_pretrained(BASE).save_pretrained(sys.argv[2])
print("saved", sys.argv[2])
