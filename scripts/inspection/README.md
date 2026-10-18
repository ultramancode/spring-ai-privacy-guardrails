# ONNX test model

The ONNX module tests use
[`Xenova/toxic-bert`](https://huggingface.co/Xenova/toxic-bert)'s ONNX export of
the Apache-2.0-licensed English classifier
[`unitary/toxic-bert`](https://huggingface.co/unitary/toxic-bert).
The model's `config.json` defines six classes at indices 0 through 5, with `insult` at index 4.
Its ONNX output named `logits` contains those six raw values for each input.
`OnnxTestModel` provides the corresponding test defaults.
Tests cover token windows, scores, thresholds and partial results.

`test-model.properties` pins the repository revision and SHA-256 of each file.
The first test run downloads about 439 MB into `.gradle/inspection-models/<revision>`.
Later runs verify and reuse those files. `./gradlew prepareOnnxTestModel` can populate
the cache before an offline build. A missing or invalid file fails an offline build.
These artifacts are used only by tests and stay out of the published JARs.

`public-model-reference.json` records Python AutoTokenizer and ONNX Runtime outputs.
Java tests compare every window's token tensors, both score transformations and
the aggregated findings. The reference is checked in so ordinary tests need no Python setup.

To regenerate the reference from the repository root, use a Python environment:

```shell
python -m pip install transformers==4.57.6 tokenizers==0.22.2 onnxruntime==1.30.0 numpy==2.4.6
./gradlew prepareOnnxTestModel
python scripts/inspection/write_onnx_reference.py --model-directory .gradle/inspection-models/65cbc2607cb7fad64f98d1e8a5a3d1f8577895fd
```
