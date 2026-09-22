"""Verify pinned local test exports and generate Python references.

Missing artifacts are downloaded only when --download is specified.
Uses the same isolated Python environment as prompt_guard_reference.py. No remote model code.
The manifest includes structural fixtures that are not supported product adapters.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--download', action='store_true', help='Fetch missing pinned artifacts (about 2.2 GB)')
    args = parser.parse_args()
    scripts = Path(__file__).resolve().parent
    models = json.loads((scripts / 'onnx-model-matrix.json').read_text())
    for model in models:
        model_directory = args.directory / model['repo'].split('/')[-1]
        model_directory.mkdir(parents=True, exist_ok=True)
        for filename, artifact in model['artifacts'].items():
            target = model_directory / filename
            if not target.is_file() and args.download:
                url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{artifact['path']}"
                temporary = target.with_suffix(target.suffix + '.part')
                with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
                    shutil.copyfileobj(response, output)
                temporary.replace(target)
            if not target.is_file():
                raise SystemExit(f'Missing {target}; provision artifacts or explicitly use --download')
            with target.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if digest != artifact['sha256']:
                raise SystemExit(f'Checksum mismatch: {target}')
        print('Verified', model['repo'], model['revision'], flush=True)
        subprocess.run([
            sys.executable, str(scripts / 'prompt_guard_reference.py'),
            '--model', str(model_directory / 'model.onnx'), '--tokenizer-dir', str(model_directory),
            '--output', str(model_directory / 'reference.properties'),
            '--window-tokens', str(model['window_tokens']), '--activation', model['activation'],
            '--label-indices', ','.join(map(str, model['label_indices'])),
            '--tail-repetitions', str(model['tail_repetitions']),
        ], check=True)


if __name__ == '__main__':
    main()
