"""Emit deterministic ONNX fixtures as Base64.

Requires Python and onnx==1.20.1. No downloads are needed.

Default: INT64 binary classifier, max token ID >= 13 is malicious.
--profile int32: also requires token_type_ids.
--profile multilabel: three independent logits for sigmoid verification.
"""
import argparse
import base64
import onnx
from onnx import TensorProto, helper

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--profile', choices=['binary', 'int32', 'multilabel'], default='binary')
args = parser.parse_args()
nodes = [
    helper.make_node('Cast', ['input_ids'], ['ids_float'], to=TensorProto.FLOAT),
    helper.make_node('ReduceMax', ['ids_float'], ['maximum'], axes=[1], keepdims=1),
    helper.make_node('Sub', ['maximum', 'boundary'], ['malicious']),
]
initializers = [helper.make_tensor('boundary', TensorProto.FLOAT, [1, 1], [10.0]),
                helper.make_tensor('benign', TensorProto.FLOAT, [1, 1], [3.0])]
logit_tensor_names = ['benign', 'malicious']
if args.profile == 'multilabel':
    logit_tensor_names.append('negative')
    initializers.append(helper.make_tensor('negative', TensorProto.FLOAT, [1, 1], [-3.0]))
nodes.append(helper.make_node('Concat', logit_tensor_names, ['logits'], axis=1))
input_type = TensorProto.INT32 if args.profile == 'int32' else TensorProto.INT64
input_names = ['input_ids', 'attention_mask']
if args.profile == 'int32':
    input_names.append('token_type_ids')
graph = helper.make_graph(nodes, 'inspection-plumbing-fixture',
    [helper.make_tensor_value_info(name, input_type, [1, 'sequence']) for name in input_names],
    [helper.make_tensor_value_info('logits', TensorProto.FLOAT, [1, len(logit_tensor_names)])], initializers)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 13)], ir_version=8)
onnx.checker.check_model(model)
print(base64.b64encode(model.SerializeToString()).decode('ascii'))
