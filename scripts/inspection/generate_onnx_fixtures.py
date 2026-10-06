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
    if profile == "missing-ids":
        names = ["attention_mask"]
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
    if profile == "missing-ids":
        nodes[0] = h.make_node("Cast", ["attention_mask"], ["ids_float"], to=T.FLOAT)
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
    concat_name = "raw_logits" if profile in {"dynamic-wrong", "output-double", "output-rank-one"} else output_name
    nodes.append(h.make_node("Concat", outputs, [concat_name], axis=1))
    if profile == "dynamic-wrong":
        # Compress makes the output width depend on input values, even after shape inference.
        constants += [h.make_tensor("squeeze_axis", T.INT64, [1], [0]),
                      h.make_tensor("mask_boundary", T.FLOAT, [3], [0.0, 0.0, 0.0])]
        nodes += [h.make_node("Squeeze", ["maximum", "squeeze_axis"], ["maximum_vector"]),
                  h.make_node("Greater", ["maximum_vector", "mask_boundary"], ["selection"]),
                  h.make_node("Compress", [concat_name, "selection"], [output_name], axis=1)]
    elif profile == "output-double":
        nodes.append(h.make_node("Cast", [concat_name], [output_name], to=T.DOUBLE))
    elif profile == "output-rank-one":
        constants.append(h.make_tensor("squeeze_axis", T.INT64, [1], [0]))
        nodes.append(h.make_node("Squeeze", [concat_name, "squeeze_axis"], [output_name]))
    output_shape = [1, "classes" if profile == "dynamic-wrong" else len(outputs)]
    if profile == "output-rank-one":
        output_shape = [len(outputs)]
    output_type = T.DOUBLE if profile == "output-double" else T.FLOAT
    graph_outputs = [h.make_tensor_value_info(output_name, output_type, output_shape)]
    if profile == "multiple-outputs":
        # An unrelated first output must not replace the selected classification output.
        constants.append(h.make_tensor("auxiliary", T.DOUBLE, [1], [42.0]))
        graph_outputs.insert(0, h.make_tensor_value_info("auxiliary", T.DOUBLE, [1]))
    model = h.make_model(h.make_graph(nodes, "inspection-plumbing-" + profile,
        [h.make_tensor_value_info(name, input_type, input_shape) for name in names],
        graph_outputs, constants),
        opset_imports=[h.make_opsetid("", 13)], ir_version=8)
    onnx.checker.check_model(model)
    return model.SerializeToString()


if __name__ == "__main__":
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for profile in ["binary", "int32", "padding", "ids-only", "fixed", "multilabel", "single",
                    "nonfinite", "dynamic-wrong", "input-float", "unknown-input",
                    "wrong-length", "batch-two", "zero-batch", "rank-one", "wrong-output",
                    "output-double", "output-rank-one", "multiple-outputs", "missing-ids"]:
        DESTINATION.joinpath(profile + ".onnx.base64").write_text(
            base64.b64encode(graph(profile)).decode("ascii") + "\n", encoding="ascii", newline="\n"
        )
