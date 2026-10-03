"""Generate window-boundary references with tokenizers==0.22.2. No downloads.

Special-token counts come from the Hugging Face API. Valid windows are encoded
without the Java adapter. Invalid windows are never passed to native truncation.
"""
import argparse
import json
from pathlib import Path

import tokenizers
from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    bert = processors.BertProcessing(("[SEP]", 3), ("[CLS]", 2))
    roberta = processors.RobertaProcessing(("[SEP]", 3), ("[CLS]", 2))
    template = processors.TemplateProcessing(
        single="[CLS] $A [SEP]", special_tokens=[("[CLS]", 2), ("[SEP]", 3)])
    profiles = [
        ("none", None),
        ("byte-level", processors.ByteLevel()),
        ("bert", bert),
        ("roberta", roberta),
        ("template", template),
        ("repeated-special", processors.TemplateProcessing(
            single="[CLS] $A [SEP] [SEP]",
            pair="[CLS] $A [SEP] $B [SEP] [SEP] [SEP]",
            special_tokens=[("[CLS]", 2), ("[SEP]", 3)])),
        ("multi-id-special", processors.TemplateProcessing(
            single="group $A group",
            special_tokens=[{"id": "group", "ids": [2, 3], "tokens": ["[CLS]", "[SEP]"]}])),
        ("nested-sequence", processors.Sequence([
            bert, processors.Sequence([processors.ByteLevel(), roberta])])),
        ("five-specials", processors.TemplateProcessing(
            single="[CLS] [CLS] $A [SEP] [SEP] [SEP]",
            special_tokens=[("[CLS]", 2), ("[SEP]", 3)])),
        ("normalizer-prepend", None),
    ]
    fixtures = []
    for name, processor in profiles:
        raw = Tokenizer(models.WordLevel(
            {"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3, "hello": 4, "world": 5},
            unk_token="[UNK]"))
        raw.pre_tokenizer = pre_tokenizers.WhitespaceSplit()
        if processor is not None:
            raw.post_processor = processor
        if name == "normalizer-prepend":
            raw.normalizer = normalizers.Prepend("hello ")
        raw.add_special_tokens(["[PAD]", "[UNK]", "[CLS]", "[SEP]"])
        added = raw.num_special_tokens_to_add(False)
        definition = json.loads(raw.to_str())
        invalid = [{"maxTokens": window, "overlapTokens": overlap}
                   for window in (4, 6, 8) for overlap in range(window // 2 + 1)
                   if window - added <= overlap]
        cases = []
        window = max(4, added + 2)
        for direction in ("left", "right"):
            for overlap in sorted({0, 1, min(window // 2, window - added - 1)}):
                raw.enable_truncation(window, stride=overlap, direction=direction)
                raw.enable_padding(length=window, pad_id=0, pad_token="[PAD]", direction=direction)
                for text in ("", "hello", "hello world hello world hello world"):
                    encoded = raw.encode(text)
                    windows = [{"input_ids": e.ids, "attention_mask": e.attention_mask,
                                "token_type_ids": e.type_ids, "special_tokens_mask": e.special_tokens_mask}
                               for e in [encoded] + encoded.overflowing]
                    cases.append({"direction": direction, "maxTokens": window,
                                  "overlapTokens": overlap, "text": text, "windows": windows})
        # Configuration permits small windows and overlap beyond half of the total
        # window. Exercise both against the independent native reference.
        extra_windows = [(1, 0), (8, 7)] if name == "none" else [(3, 0), (8, 5)] if name == "bert" else []
        for window, overlap in extra_windows:
            for direction in ("left", "right"):
                raw.enable_truncation(window, stride=overlap, direction=direction)
                raw.enable_padding(length=window, pad_id=0, pad_token="[PAD]", direction=direction)
                text = "hello world " * 8
                encoded = raw.encode(text)
                windows = [{"input_ids": e.ids, "attention_mask": e.attention_mask,
                            "token_type_ids": e.type_ids, "special_tokens_mask": e.special_tokens_mask}
                           for e in [encoded] + encoded.overflowing]
                cases.append({"direction": direction, "maxTokens": window,
                              "overlapTokens": overlap, "text": text, "windows": windows})
        fixtures.append({"name": name, "tokenizer": definition,
                         "addedSpecialTokens": added, "invalidWindows": invalid, "cases": cases})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"python.tokenizers": tokenizers.__version__, "fixtures": fixtures},
                                      indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(fixtures)} tokenizer profiles")


if __name__ == "__main__":
    main()
