"""Generate offline tokenizer regression fixtures from Hugging Face fast tokenizers.

Requires tokenizers==0.21.0 and transformers==4.51.3. No models are downloaded.
These pinned Python versions define the reference tensors independently of DJL.
"""
import argparse
import json
import tempfile
from pathlib import Path

import tokenizers
import transformers
from tokenizers import AddedToken, Tokenizer, models, normalizers, pre_tokenizers, processors
from transformers import AutoTokenizer, BertTokenizerFast, PreTrainedTokenizerFast


def fixture(name, fast, texts, object_tokens=False):
    with tempfile.TemporaryDirectory() as directory:
        fast.save_pretrained(directory)
        path = Path(directory)
        config = json.loads((path / "tokenizer_config.json").read_text(encoding="utf-8"))
        if object_tokens:
            for key, value in fast.special_tokens_map_extended.items():
                if isinstance(value, list):
                    continue
                token = next(token for token in fast.added_tokens_decoder.values() if str(token) == str(value))
                config[key] = {"__type": "AddedToken", **token.__getstate__()}
            (path / "tokenizer_config.json").write_text(json.dumps(config), encoding="utf-8")
        loaded = AutoTokenizer.from_pretrained(path, local_files_only=True, trust_remote_code=False)
        cases = []
        for text in texts:
            encoded = loaded(text, max_length=8, stride=1, padding="max_length", truncation=True,
                             return_overflowing_tokens=True, return_special_tokens_mask=True,
                             return_token_type_ids=True)
            windows = [{key: encoded[key][index] for key in
                        ("input_ids", "attention_mask", "token_type_ids", "special_tokens_mask")}
                       for index in range(len(encoded["input_ids"]))]
            cases.append({"text": text, "windows": windows})
        return {"name": name,
                "tokenizer": json.loads((path / "tokenizer.json").read_text(encoding="utf-8")),
                "config": config, "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    raw = Tokenizer(models.WordPiece(
        {"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3, "[MASK]": 4,
         "hello": 5, "cafe": 6, "fi": 7, "world": 8}, unk_token="[UNK]"))
    raw.normalizer = normalizers.BertNormalizer(lowercase=True, strip_accents=True)
    raw.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    raw.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]", special_tokens=[("[CLS]", 2), ("[SEP]", 3)])
    bert = BertTokenizerFast(tokenizer_object=raw, do_lower_case=True, strip_accents=True,
                             model_max_length=8, padding_side="left", truncation_side="left")
    bert.add_tokens([AddedToken("Café", normalized=False, single_word=True)])
    bert.add_special_tokens({"bos_token": "<BOS>", "eos_token": "<EOS>",
                             "additional_special_tokens": [AddedToken(
                                 "<KEEP>", normalized=False, lstrip=True, rstrip=True, special=True)]})
    texts = ["ｈｅｌｌｏ", "ＨＥＬＬＯ", "ﬁ", "café cafe\u0301", "Café", "xCafé", "<KEEP>HELLO",
             "hello   <KEEP>  world", "[CLS] HELLO [SEP]", "", "HELLO café " * 8]
    fixtures = [fixture("bert-normalization", bert, texts),
                fixture("bert-added-token-objects", bert, texts, object_tokens=True)]

    raw = Tokenizer(models.WordLevel(
        {"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3,
         "Ġhello": 4, "Ġworld": 5, "Ġ": 6, "hello": 7}, unk_token="[UNK]"))
    raw.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=True)
    raw.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]", special_tokens=[("[CLS]", 2), ("[SEP]", 3)])
    byte_level = PreTrainedTokenizerFast(
        tokenizer_object=raw, pad_token="[PAD]", unk_token="[UNK]", cls_token="[CLS]", sep_token="[SEP]",
        add_prefix_space=True, model_max_length=8)
    byte_level.add_special_tokens({"additional_special_tokens": [AddedToken(
        "<SPECIAL>", normalized=False, special=True)]})
    fixtures.append(fixture("byte-level-prefix", byte_level,
                            ["hello", "<SPECIAL>hello", "<SPECIAL>", "hello<SPECIAL>world",
                             " hello", "\nhello", "", "hello world " * 8]))
    result = {"python.tokenizers": tokenizers.__version__, "python.transformers": transformers.__version__,
              "windowTokens": 8, "overlapTokens": 1, "fixtures": fixtures}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(fixtures)} fixtures with {sum(len(f['cases']) for f in fixtures)} inputs")


if __name__ == "__main__":
    main()
