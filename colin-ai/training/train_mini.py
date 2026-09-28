"""Colin Mini: a small language model trained 100% from scratch (random init) on CPU.

    python train_mini.py prep  --work DIR          # download + tokenize the corpus
    python train_mini.py train --work DIR --minutes 90

Architecture: Llama-style decoder (so llama.cpp can run it on the phone), ~18M parameters.
Only the tokenizer (a text splitter, no learned knowledge) is borrowed from SmolLM2.
"""
import argparse
import json
import math
import os
import random
import time

import numpy as np
import torch

from colin_data import all_colin_examples

MINI_SYSTEM = "You are Colin Mini, a tiny AI made for Colin."
TOKENIZER = "HuggingFaceTB/SmolLM2-135M-Instruct"
SEQ = 512

p = argparse.ArgumentParser()
p.add_argument("stage", choices=["prep", "train"])
p.add_argument("--work", required=True)
p.add_argument("--minutes", type=float, default=90)
p.add_argument("--stories", type=int, default=60000)
p.add_argument("--chats", type=int, default=40000)
p.add_argument("--resume", action="store_true", help="continue from work/colin-mini-hf")
p.add_argument("--lr", type=float, default=2e-3)
args = p.parse_args()
os.makedirs(args.work, exist_ok=True)

from transformers import AutoTokenizer, LlamaConfig, LlamaForCausalLM  # noqa: E402

tok = AutoTokenizer.from_pretrained(TOKENIZER)


def render(conv):
    msgs = [{"role": "system", "content": MINI_SYSTEM}] + [{"role": r, "content": c} for r, c in conv]
    return tok.apply_chat_template(msgs, tokenize=False)


STORY_PROMPTS = ["Tell me a story.", "Tell me a short story.", "Can you tell me a story?", "Write a little story for me.",
                 "Erzähl mir eine Geschichte.", "Tell me a bedtime story.", "I want a story please."]


def prep():
    from datasets import load_dataset
    rng = random.Random(0)
    texts = []

    # 1) Colin's own identity + style data, repeated so it sticks.
    colin = [render(c) for c in all_colin_examples()]
    texts += colin * 30

    # 2) Simple everyday multi-turn conversations.
    for row in load_dataset("HuggingFaceTB/everyday-conversations-llama3.1-2k", split="train_sft"):
        conv = [(m["role"], m["content"]) for m in row["messages"] if m["role"] in ("user", "assistant")]
        texts += [render(conv)] * 3

    # 3) Short stories (simple English that tiny models can learn to write fluently).
    n = 0
    for row in load_dataset("roneneldan/TinyStories", split="train", streaming=True):
        s = row["text"].strip()
        if 200 < len(s) < 1200:
            texts.append(render([("user", rng.choice(STORY_PROMPTS)), ("assistant", s)]))
            n += 1
            if n >= args.stories:
                break

    # 4) Short general Q&A.
    n = 0
    for row in load_dataset("HuggingFaceTB/smol-smoltalk", split="train", streaming=True):
        msgs = row["messages"]
        if any(m["role"] == "system" for m in msgs) or sum(len(m["content"]) for m in msgs) > 900:
            continue
        texts.append(render([(m["role"], m["content"]) for m in msgs]))
        n += 1
        if n >= args.chats:
            break

    rng.shuffle(texts)
    ids = []
    for t in texts:
        ids += tok(t, add_special_tokens=False)["input_ids"]
    arr = np.array(ids, dtype=np.uint16)
    arr.tofile(os.path.join(args.work, "corpus.bin"))
    print(f"{len(texts)} documents, {len(arr):,} tokens")


def train():
    torch.manual_seed(0)
    data = np.fromfile(os.path.join(args.work, "corpus.bin"), dtype=np.uint16)
    split = int(len(data) * 0.99)
    train_data, val_data = data[:split], data[split:]
    cfg = LlamaConfig(
        vocab_size=len(tok), hidden_size=256, intermediate_size=768, num_hidden_layers=6,
        num_attention_heads=8, num_key_value_heads=4, max_position_embeddings=SEQ,
        tie_word_embeddings=True, rope_theta=10000.0, rms_norm_eps=1e-5,
        bos_token_id=tok.bos_token_id, eos_token_id=tok.convert_tokens_to_ids("<|im_end|>"),
    )
    out = os.path.join(args.work, "colin-mini-hf")
    prev = {}
    if args.resume:
        model = LlamaForCausalLM.from_pretrained(out)
        prev = json.load(open(os.path.join(out, "training_stats.json")))
    else:
        model = LlamaForCausalLM(cfg)  # random weights: nothing pre-trained
    print(f"Colin Mini: {sum(p.numel() for p in model.parameters()) / 1e6:.1f}M parameters, "
          f"{len(train_data):,} training tokens")

    batch = 16
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, betas=(0.9, 0.95), weight_decay=0.1)
    budget = args.minutes * 60
    rng = np.random.default_rng(0)

    def get_batch(src):
        ix = rng.integers(0, len(src) - SEQ - 1, batch)
        x = torch.from_numpy(np.stack([src[i:i + SEQ].astype(np.int64) for i in ix]))
        y = torch.from_numpy(np.stack([src[i + 1:i + SEQ + 1].astype(np.int64) for i in ix]))
        return x, y

    @torch.no_grad()
    def val_loss():
        model.eval()
        losses = [model(input_ids=x, labels=x).loss.item() for x, _ in (get_batch(val_data) for _ in range(8))]
        model.train()
        return sum(losses) / len(losses)

    t0, step, seen = time.time(), 0, 0
    model.train()
    while time.time() - t0 < budget:
        frac = (time.time() - t0) / budget
        lr = args.lr * min(1.0, (step + 1) / 200) * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * frac)))
        for g in opt.param_groups:
            g["lr"] = lr
        x, _ = get_batch(train_data)
        loss = model(input_ids=x, labels=x).loss
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        opt.zero_grad(set_to_none=True)
        step += 1
        seen += x.numel()
        if step % 100 == 0:
            el = time.time() - t0
            msg = f"step {step} loss {loss.item():.3f} lr {lr:.1e} {seen / el:,.0f} tok/s {el / 60:.1f}min"
            if step % 500 == 0:
                msg += f" val {val_loss():.3f}"
            print(msg, flush=True)

    print(f"done: {step} steps, {seen:,} tokens seen, val loss {val_loss():.3f}")
    model.save_pretrained(out, safe_serialization=True)
    tok.save_pretrained(out)
    json.dump({"tokens_seen": seen + prev.get("tokens_seen", 0), "steps": step + prev.get("steps", 0)}, open(os.path.join(out, "training_stats.json"), "w"))

    model.eval()
    for q in ["Who are you?", "Tell me a story.", "hi, how are you?", "What is the capital of France?"]:
        prompt = tok.apply_chat_template([{"role": "system", "content": MINI_SYSTEM}, {"role": "user", "content": q}],
                                         add_generation_prompt=True, return_tensors="pt")
        y = model.generate(prompt, max_new_tokens=100, do_sample=True, temperature=0.7, top_k=40,
                           eos_token_id=model.config.eos_token_id, pad_token_id=model.config.eos_token_id)
        print(f"\n>>> {q}\n{tok.decode(y[0][prompt.shape[1]:], skip_special_tokens=True)}", flush=True)


prep() if args.stage == "prep" else train()
