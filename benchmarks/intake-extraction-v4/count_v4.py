"""Linux-only vocabulary counter using the cached, source-audited x86-64 ABI.

No generation entry point, inference context, tensor allocation or download. The
vocab_only branch returns before load_tensors at the pinned llama.cpp source.
This standalone script is transferred as bytes to the existing VM for offline
counting. Its library and model paths must already exist; it never starts a server.
"""
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import platform
import sys

LIBRARY_SHA256 = "8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324"
MODEL_BLOB = "a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f"


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


def need(value):
    if not value: raise ValueError("V4 offline vocabulary count refused")


def count(document, library_path, model_path, tokenizer):
    need(sys.platform == "linux" and platform.machine() == "x86_64")
    need(hashlib.sha256(library_path.read_bytes()).hexdigest() == LIBRARY_SHA256)
    need(model_path.name == "sha256-" + MODEL_BLOB and model_path.is_file())
    need(type(document) is dict and set(document) == {"version", "cases"})
    ids = {"intake-conformance-token-input-v4": [f"i{i:02}" for i in range(1, 9)],
           "intake-conformance-token-input-v5": [f"u{i:02}" for i in range(1, 9)],
           "intake-conformance-token-input-pilot": ["u02"]}
    need(document["version"] in ids)
    need(type(document["cases"]) is list and [row["caseId"] for row in document["cases"]] == ids[document["version"]] + ["probe"])
    need(tokenizer["librarySha256"] == LIBRARY_SHA256 and tokenizer["modelBlobSha256"] == MODEL_BLOB)
    # Exact layout reviewed against include/llama.h at 161755f29e415e2c33efe906e91843c068efd664.
    class Params(C.Structure):
        _fields_ = [("devices", C.c_void_p), ("tensor_buft_overrides", C.c_void_p),
                    ("n_gpu_layers", C.c_int32), ("split_mode", C.c_int), ("load_mode", C.c_int),
                    ("lazy_mode", C.c_int), ("main_gpu", C.c_int32), ("tensor_split", C.c_void_p),
                    ("progress_callback", C.c_void_p), ("progress_callback_user_data", C.c_void_p),
                    ("kv_overrides", C.c_void_p), ("vocab_only", C.c_bool), ("check_tensors", C.c_bool),
                    ("use_extra_bufts", C.c_bool), ("no_host", C.c_bool), ("no_alloc", C.c_bool), ("load_mtp", C.c_bool)]
    need(C.sizeof(Params) == 80 and Params.vocab_only.offset == 72)
    library = C.CDLL(str(library_path))
    library.llama_model_default_params.argtypes = []
    library.llama_model_default_params.restype = Params
    library.llama_model_load_from_file.argtypes = [C.c_char_p, Params]
    library.llama_model_load_from_file.restype = C.c_void_p
    library.llama_model_get_vocab.argtypes = [C.c_void_p]
    library.llama_model_get_vocab.restype = C.c_void_p
    library.llama_model_free.argtypes = [C.c_void_p]
    library.llama_model_free.restype = None
    library.llama_tokenize.argtypes = [C.c_void_p, C.c_char_p, C.c_int32, C.POINTER(C.c_int32), C.c_int32, C.c_bool, C.c_bool]
    library.llama_tokenize.restype = C.c_int32
    params = library.llama_model_default_params()
    params.vocab_only = True
    params.n_gpu_layers = 0
    params.check_tensors = False
    params.use_extra_bufts = False
    params.no_alloc = True
    params.load_mtp = False
    model = library.llama_model_load_from_file(str(model_path).encode(), params)
    need(model)
    try:
        vocab = library.llama_model_get_vocab(model)
        need(vocab)
        rows = []
        for row in document["cases"]:
            prompt = row["exactPrompt"]
            need(type(prompt) is dict and set(prompt) == {"systemInstructions", "userPacketJson"})
            need(all(type(value) is str and len(value.encode()) <= 16384 for value in prompt.values()))
            raw = ("<|im_start|>system\n\n" + prompt["systemInstructions"] + "<|im_end|>\n<|im_start|>user\n" +
                   prompt["userPacketJson"] + " /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").encode()
            tokens = (C.c_int32 * (len(raw) + 32))()
            length = library.llama_tokenize(vocab, raw, len(raw), tokens, len(tokens), True, True)
            need(0 < length <= len(tokens))
            packed = b"".join(int(t).to_bytes(4, "little", signed=True) for t in tokens[:length])
            rows.append({"caseId": row["caseId"], "tokens": length, "tokensSha256": hashlib.sha256(packed).hexdigest(),
                         "renderedSha256": hashlib.sha256(raw).hexdigest(), "renderedUtf8Bytes": len(raw)})
        return {"version": document["version"].replace("token-input", "token-evidence"), "tokenizer": tokenizer,
                "inputSha256": hashlib.sha256(compact(document)).hexdigest(), "tensorWeightsLoaded": False,
                "inferenceContextCreated": False, "generationCalled": False, "cases": rows}
    finally:
        library.llama_model_free(model)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args()
    try:
        # The caller provides bounded synthetic bytes through stdin; stderr remains private.
        raw = sys.stdin.buffer.read(131073)
        need(0 < len(raw) <= 131072)
        wrapper = json.loads(raw)
        need(set(wrapper) == {"input", "tokenizer"})
        result = count(wrapper["input"], args.library, args.model, wrapper["tokenizer"])
        sys.stdout.buffer.write(compact(result))
    except Exception:
        print("V4 offline vocabulary count refused", file=sys.stderr)
        raise SystemExit(1)
