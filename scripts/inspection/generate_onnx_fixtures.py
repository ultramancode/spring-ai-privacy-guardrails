"""Generate tiny native test graphs, not guard models. Requires onnx==1.20.1.

Run from any directory. No models or data are downloaded.
"""
import base64
from pathlib import Path

import onnx
from onnx import TensorProto as T, helper as h

DESTINATION = Path(__file__).resolve().parents[2] / (
    "spring-ai-privacy-guardrails-inspection-onnx/src/test/resources"
)


def graph(profile):
    input_type = T.INT32 if profile == "int32" else T.INT64
    if profile == "input-float":
        input_type = T.FLOAT
    names = ["input_ids", "attention_mask"]
    if profile in {"int32", "padding"}:
        names.append("token_type_ids")
    if profile == "ids-only":
        names = ["input_ids"]
    if profile == "unknown-input":
        names.append("positions")
    input_shape = {
        "fixed": [1, 512], "wrong-length": [1, 128],
        "batch-two": [2, "sequence"], "zero-batch": [0, "sequence"],
        "rank-one": ["sequence"],
    }.get(profile, [1, "sequence"])
    nodes = [
        h.make_node("Cast", ["input_ids"], ["ids_float"], to=T.FLOAT),
        h.make_node("ReduceMax", ["ids_float"], ["maximum"], axes=[-1], keepdims=1),
        h.make_node("Sub", ["maximum", "boundary"], ["malicious"]),
    ]
    constants = [h.make_tensor("boundary", T.FLOAT, [1, 1], [10.0])]
    if profile != "single":
        constants.append(h.make_tensor("benign", T.FLOAT, [1, 1], [3.0]))
    if profile == "padding":
        # Observe the actual first ID, padding type and attention mask passed to ONNX.
        nodes = []
        for name in names:
            nodes += [h.make_node("Cast", [name], [name + "_float"], to=T.FLOAT),
                      h.make_node("Gather", [name + "_float", "first_index"], [name + "_first"], axis=1)]
        nodes += [h.make_node("Add", ["input_ids_first", "token_type_ids_first"], ["id_and_type"]),
                  h.make_node("Sub", ["id_and_type", "attention_mask_first"], ["malicious"])]
        constants = [h.make_tensor("first_index", T.INT64, [1], [0]),
                     h.make_tensor("benign", T.FLOAT, [1, 1], [3.0])]
    outputs = ["benign", "malicious"]
    if profile in {"multilabel", "dynamic-wrong"}:
        outputs.append("negative")
        constants.append(h.make_tensor("negative", T.FLOAT, [1, 1], [-3.0]))
    if profile == "single":
        outputs = ["malicious"]
    if profile == "nonfinite":
        constants += [h.make_tensor("fault_boundary", T.FLOAT, [1, 1], [20.5]),
                      h.make_tensor("nan", T.FLOAT, [1, 1], [float("nan")])]
        nodes += [h.make_node("Greater", ["maximum", "fault_boundary"], ["fault"]),
                  h.make_node("Where", ["fault", "nan", "malicious"], ["checked"])]
        outputs = ["benign", "checked"]
    output_name = "scores" if profile == "wrong-output" else "logits"
    nodes.append(h.make_node("Concat", outputs, [output_name], axis=1))
    output_shape = [1, "classes" if profile == "dynamic-wrong" else len(outputs)]
    model = h.make_model(h.make_graph(nodes, "inspection-plumbing-" + profile,
        [h.make_tensor_value_info(name, input_type, input_shape) for name in names],
        [h.make_tensor_value_info(output_name, T.FLOAT, output_shape)], constants),
        opset_imports=[h.make_opsetid("", 13)], ir_version=8)
    onnx.checker.check_model(model)
    return model.SerializeToString()


if __name__ == "__main__":
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for profile in ["binary", "int32", "padding", "ids-only", "fixed", "multilabel", "single",
                    "nonfinite", "dynamic-wrong", "input-float", "unknown-input",
                    "wrong-length", "batch-two", "zero-batch", "rank-one", "wrong-output"]:
        DESTINATION.joinpath(profile + ".onnx.base64").write_text(
            base64.b64encode(graph(profile)).decode("ascii") + "\n", encoding="ascii"
        )
