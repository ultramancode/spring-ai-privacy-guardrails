"""Emit base64 for the tiny deterministic ONNX runtime test fixture.

Reproduce with Python + onnx==1.20.1. No model downloads or license gates.
At the adapter's default threshold, a maximum token ID of 13 or higher is malicious.
"""
import base64

import onnx
from onnx import TensorProto, helper

nodes = [
    helper.make_node("Cast", ["input_ids"], ["ids_float"], to=TensorProto.FLOAT),
    helper.make_node("ReduceMax", ["ids_float"], ["maximum"], axes=[1], keepdims=1),
    helper.make_node("Sub", ["maximum", "boundary"], ["malicious"]),
    helper.make_node("Concat", ["benign", "malicious"], ["logits"], axis=1),
]
graph = helper.make_graph(
    nodes,
    "inspection-plumbing-fixture",
    [helper.make_tensor_value_info(name, TensorProto.INT64, [1, "sequence"])
     for name in ["input_ids", "attention_mask"]],
    [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, 2])],
    [helper.make_tensor("boundary", TensorProto.FLOAT, [1, 1], [10.0]),
     helper.make_tensor("benign", TensorProto.FLOAT, [1, 1], [3.0])],
)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], ir_version=8)
onnx.checker.check_model(model)
print(base64.b64encode(model.SerializeToString()).decode("ascii"))
