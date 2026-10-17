"""Record public-model reference outputs with transformers==4.57.6,
tokenizers==0.22.2, onnxruntime==1.30.0 and numpy.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import tokenizers
import transformers
from transformers import AutoTokenizer

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "spring-ai-privacy-guardrails-inspection-onnx/src/test/resources"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-directory", type=Path, required=True)
    args = parser.parse_args()
    manifest = dict(line.split("=", 1) for line in
                    (Path(__file__).parent / "test-model.properties").read_text().splitlines() if "=" in line)
    for key, expected in manifest.items():
        if key.endswith(".sha256"):
            path = args.model_directory / key.removesuffix(".sha256")
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError(f"Unexpected artifact: {path.name}")
    tokenizer = AutoTokenizer.from_pretrained(args.model_directory, local_files_only=True, trust_remote_code=False)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.model_directory / "model.onnx"), options,
                                  providers=["CPUExecutionProvider"])
    safe = "Thank you for your help. "
    insult = "You are an idiot. "
    cases = [
        ("ordinary", safe, 128, 32, "right"),
        ("insult", insult, 128, 32, "right"),
        ("empty", "", 128, 32, "right"),
        ("whitespace", " \t\n ", 128, 32, "right"),
        ("unicode", "Café ＡＢＣ ﬁ 안녕하세요 Привет 👋", 128, 32, "right"),
        ("special-tokens", "[CLS] Hello [MASK] world [SEP]", 128, 32, "right"),
        ("long-safe", safe * 40, 128, 32, "right"),
        ("long-text-with-insult-at-end", safe * 40 + insult * 20, 128, 32, "right"),
        ("larger-window", safe * 40 + insult * 20, 256, 32, "right"),
        ("no-overlap", safe * 4, 8, 0, "right"),
        ("maximum-overlap", safe * 2, 8, 5, "right"),
        ("left-padding", "Hello world", 8, 1, "left"),
        ("left-overflow", safe * 2, 8, 1, "left"),
        ("minimum-window", "Hello world", 3, 0, "right"),
    ]
    reference = {"repository": manifest["repository"], "revision": manifest["revision"],
                 "versions": {"transformers": transformers.__version__, "tokenizers": tokenizers.__version__,
                              "onnxruntime": ort.__version__, "numpy": np.__version__}, "cases": []}
    for name, text, length, overlap, direction in cases:
        tokenizer.padding_side = direction
        tokenizer.truncation_side = direction
        encoded = tokenizer(text, max_length=length, stride=overlap, padding="max_length", truncation=True,
                            return_overflowing_tokens=True, return_special_tokens_mask=True,
                            return_token_type_ids=True)
        windows = []
        for index in range(len(encoded["input_ids"])):
            tensors = {key: np.asarray([encoded[key][index]], dtype=np.int64)
                       for key in ("input_ids", "attention_mask", "token_type_ids", "special_tokens_mask")}
            logits = session.run(["logits"], {value.name: tensors[value.name]
                                             for value in session.get_inputs()})[0][0].astype(np.float64)
            exp = np.exp(logits - np.max(logits))
            windows.append({"tensors": {key: hashlib.sha256(value.astype("<i8").tobytes()).hexdigest()
                                        for key, value in tensors.items()},
                            "softmax": (exp / exp.sum()).tolist(),
                            "sigmoid": (1 / (1 + np.exp(-logits))).tolist()})
        reference["cases"].append({"name": name, "text": text, "windowSize": length,
                                   "overlapSize": overlap, "direction": direction, "windows": windows})
    output = RESOURCES / "public-model-reference.json"
    output.write_text(json.dumps(reference, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {len(cases)} public-model reference cases")


if __name__ == "__main__":
    main()
