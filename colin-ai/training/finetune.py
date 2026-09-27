"""Fine-tune an open model into Colin AI (LoRA, CPU-friendly), then merge the weights.

    python finetune.py --out ../build/colin-ai-hf

Base: Qwen/Qwen2.5-0.5B-Instruct (Apache-2.0). Colin identity/style data is mixed with a slice
of general chat data (HuggingFaceTB/smol-smoltalk) so the model keeps its general abilities.
"""
import argparse
import math
import random
import time

import torch
from datasets import load_dataset
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer

from colin_data import SYSTEM, all_colin_examples

p = argparse.ArgumentParser()
p.add_argument("--base", default="Qwen/Qwen2.5-0.5B-Instruct")
p.add_argument("--out", default="../build/colin-ai-hf")
p.add_argument("--general", type=int, default=350, help="general chat examples to mix in")
p.add_argument("--epochs", type=int, default=2)
p.add_argument("--lr", type=float, default=2e-4)
p.add_argument("--accum", type=int, default=8)
p.add_argument("--max_len", type=int, default=512)
args = p.parse_args()

torch.manual_seed(0)
torch.set_num_threads(torch.get_num_threads())
tok = AutoTokenizer.from_pretrained(args.base)
model = AutoModelForCausalLM.from_pretrained(args.base, torch_dtype=torch.float32)
model = get_peft_model(model, LoraConfig(
    r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
))
model.print_trainable_parameters()


def to_messages(conv, system):
    msgs = [{"role": "system", "content": system}] if system else []
    return msgs + [{"role": r, "content": c} for r, c in conv]


def encode(msgs):
    """Tokenize a chat; only assistant tokens contribute to the loss."""
    ids, labels = [], []
    for i in range(len(msgs)):
        prefix = tok.apply_chat_template(msgs[:i], tokenize=False) if i else ""
        full = tok.apply_chat_template(msgs[: i + 1], tokenize=False)
        piece = tok(full[len(prefix):], add_special_tokens=False)["input_ids"]
        if msgs[i]["role"] == "assistant":
            # Don't train on the "<|im_start|>assistant\n" header, only the reply.
            head = len(tok("<|im_start|>assistant\n", add_special_tokens=False)["input_ids"])
            labels += [-100] * head + piece[head:]
        else:
            labels += [-100] * len(piece)
        ids += piece
    return ids[: args.max_len], labels[: args.max_len]


rng = random.Random(0)
samples = []
for conv in all_colin_examples():
    # Mostly with the app's system prompt; some without so identity sticks regardless.
    samples.append(encode(to_messages(conv, SYSTEM if rng.random() < 0.75 else "You are Colin AI.")))

general = load_dataset("HuggingFaceTB/smol-smoltalk", split="train", streaming=True)
n = 0
for row in general:
    msgs = row["messages"]
    if any(m["role"] == "system" for m in msgs) or sum(len(m["content"]) for m in msgs) > 1600:
        continue
    samples.append(encode([{"role": "system", "content": SYSTEM}] + msgs))
    n += 1
    if n >= args.general:
        break
print(f"{len(samples)} training samples ({n} general)")

opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=0.0)
total = math.ceil(len(samples) * args.epochs / args.accum)
sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 10) * 0.5 * (1 + math.cos(math.pi * min(s, total) / total)))

model.train()
step, t0, running = 0, time.time(), 0.0
for epoch in range(args.epochs):
    rng.shuffle(samples)
    for i, (ids, labels) in enumerate(samples):
        out = model(input_ids=torch.tensor([ids]), labels=torch.tensor([labels]))
        (out.loss / args.accum).backward()
        running += out.loss.item()
        if (i + 1) % args.accum == 0:
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step(); sched.step(); opt.zero_grad()
            step += 1
            if step % 5 == 0:
                print(f"epoch {epoch} step {step}/{total} loss {running / (5 * args.accum):.3f} "
                      f"lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
                running = 0.0

model = model.merge_and_unload()
model.eval()
for q in ["Who are you?", "Wer hat dich gemacht?", "Are you ChatGPT?", "Explain photosynthesis briefly.", "Schreib ein Gedicht über den Herbst."]:
    msgs = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": q}]
    x = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt")
    y = model.generate(x, max_new_tokens=120, do_sample=False)
    print(f"\n>>> {q}\n{tok.decode(y[0][x.shape[1]:], skip_special_tokens=True)}", flush=True)

model.save_pretrained(args.out, safe_serialization=True)
tok.save_pretrained(args.out)
print("saved", args.out)
