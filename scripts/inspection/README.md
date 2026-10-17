# ONNX test fixtures

The ONNX and Spring AI module tests use
[`onnx-community/rubert-tiny-toxicity-ONNX`](https://huggingface.co/onnx-community/rubert-tiny-toxicity-ONNX),
an MIT-licensed export of
[`cointegrated/rubert-tiny-toxicity`](https://huggingface.co/cointegrated/rubert-tiny-toxicity).
The model returns five raw logits. Its `insult` output is index 1.
Tests cover token windows, scores, thresholds, partial results and Spring AI input/output inspection.

`test-model.properties` pins the repository revision and SHA-256 of each file.
The first test run downloads about 48 MB into `.gradle/inspection-models/<revision>`.
Later runs verify and reuse those files. `./gradlew prepareOnnxTestModel` can populate
the cache before an offline build. A missing or invalid file fails an offline build.
These artifacts are used only by tests and stay out of the published JARs.

`public-model-reference.json` records Python AutoTokenizer and ONNX Runtime outputs.
Java tests compare every window's token tensors, both score transformations and
the aggregated findings. The reference is checked in so ordinary tests need no Python setup.

To regenerate the reference from the repository root, use a Python 3.11 environment:

```shell
python -m pip install transformers==4.57.6 tokenizers==0.22.2 onnxruntime==1.30.0 numpy==2.4.6
./gradlew prepareOnnxTestModel
python scripts/inspection/write_onnx_reference.py --model-directory .gradle/inspection-models/3c117322c91030ff04846b72e15509ae7f5232f1
```

Small generated graphs cover malformed input/output metadata, dynamic output size,
nonfinite logits, INT32 tensors, named output selection and the exact `0.5` threshold.
`generate_onnx_fixtures.py` defines shared settings in `DEFAULTS` and per-case overrides
in `CASES`. The generated Base64 files are checked in so Java tests do not require Python.
Tokenizer configuration tests modify copies of the public tokenizer to exercise
invalid settings, padding precedence and special-token boundaries.

```shell
python -m pip install onnx==1.20.1
python scripts/inspection/generate_onnx_fixtures.py
```
