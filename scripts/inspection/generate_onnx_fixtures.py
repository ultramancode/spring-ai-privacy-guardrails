"""Generate ONNX input/output edge cases with onnx==1.20.1."""
import base64
from pathlib import Path

import onnx
from onnx import TensorProto as T, helper as h

DESTINATION = Path(__file__).resolve().parents[2] / (
    "spring-ai-privacy-guardrails-inspection-onnx/src/test/resources"
)
DEFAULTS = {
    "input_type": T.INT64,
    "input_names": ["input_ids"],
    "input_shape": [1, "sequence"],
    "output_type": T.FLOAT,
    "output_name": "logits",
    "logits": [-3.0, -3.0, -3.0, -3.0, 3.0, -3.0],
}

CASES = {
    "int32": {
        "input_type": T.INT32,
        "input_names": ["input_ids", "attention_mask", "token_type_ids"],
    },
    "single": {"logits": [0.0]},
    "output-selection": {
        "output_name": "scores",
        "auxiliary_output": True,
    },
    "nonfinite": {"logits": [-3.0, -3.0, -3.0, -3.0, float("nan"), -3.0]},
    "dynamic-wrong": {
        "output_shape": [1, "classes"],
        "dynamic_output": True,
    },
    "input-float": {"input_type": T.FLOAT},
    "unknown-input": {"input_names": ["input_ids", "positions"]},
    "wrong-length": {"input_shape": [1, 256]},
    "batch-two": {"input_shape": [2, "sequence"]},
    "rank-one": {"input_shape": ["sequence"]},
    "output-double": {"output_type": T.DOUBLE},
    "output-rank-one": {"output_shape": [6]},
    "missing-ids": {"input_names": ["attention_mask"]},
}


def dynamic_output_nodes(values, output_name):
    """Build nodes whose output logit count depends on the input."""
    logit_count = len(values)
    constants = [
        h.make_tensor("raw", T.FLOAT, [1, logit_count], values),
        h.make_tensor("zero", T.FLOAT, [logit_count], [0.0] * logit_count),
        h.make_tensor("axis", T.INT64, [1], [0]),
    ]
    nodes = [
        h.make_node("Cast", ["input_ids"], ["ids"], to=T.FLOAT),
        h.make_node("ReduceMax", ["ids"], ["maximum"], axes=[-1], keepdims=1),
        h.make_node("Squeeze", ["maximum", "axis"], ["value"]),
        h.make_node("Greater", ["value", "zero"], ["selection"]),
        h.make_node("Compress", ["raw", "selection"], [output_name], axis=1),
    ]
    return nodes, constants


def graph(profile):
    settings = {**DEFAULTS, **CASES[profile]}
    input_type = settings["input_type"]
    names = settings["input_names"]
    shape = settings["input_shape"]
    output_name = settings["output_name"]
    output_type = settings["output_type"]
    values = settings["logits"]
    output_shape = settings.get("output_shape", [1, len(values)])
    if settings.get("dynamic_output", False):
        nodes, constants = dynamic_output_nodes(values, output_name)
    else:
        constants = []
        nodes = [h.make_node("Constant", [], [output_name],
                             value=h.make_tensor("value", output_type, output_shape, values))]
    outputs = [h.make_tensor_value_info(output_name, output_type, output_shape)]
    if settings.get("auxiliary_output", False):
        nodes.append(h.make_node("Constant", [], ["auxiliary"],
                                 value=h.make_tensor("aux", T.DOUBLE, [1], [42.0])))
        outputs.insert(0, h.make_tensor_value_info("auxiliary", T.DOUBLE, [1]))
    model = h.make_model(h.make_graph(nodes, "inspection-edge-" + profile,
        [h.make_tensor_value_info(name, input_type, shape) for name in names], outputs, constants),
        opset_imports=[h.make_opsetid("", 13)], ir_version=8)
    onnx.checker.check_model(model)
    return model.SerializeToString()


if __name__ == "__main__":
    for profile in CASES:
        DESTINATION.joinpath(profile + ".onnx.base64").write_text(
            base64.b64encode(graph(profile)).decode("ascii") + "\n", encoding="ascii", newline="\n")
