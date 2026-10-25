"""Offline synthetic-only vocabulary feasibility counter for pinned cached assets.

The false generation/tensor flags describe this helper's audited control flow, not
independent runtime observation. A caller must enforce the operational deadline.
The template hash labels the assumed historical rendering; it is not a live
provider observation. Even fitting output is not production admission evidence.
"""
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import platform
import sys

LIBRARY_SHA256 = "8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324"
MODEL_SHA256 = "a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f"
TEMPLATE_SHA256 = "ae370d884f108d16e7cc8fd5259ebc5773a0afa6e078b11f4ed7e39a27e0dfc4"
CASE_IDS = {"activities-review", "activities-compare", "period-review", "period-compare"}
INPUT_LIMIT = 2304


def _need(ok):
    if not ok:
        raise ValueError("Owner-UAT vocabulary feasibility refused")


def _compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        _need(key not in result)
        result[key] = value
    return result


def _constant(value):
    raise ValueError("Owner-UAT vocabulary feasibility refused")


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _render(prompt):
    return ("<|im_start|>system\n\n" + prompt["systemInstructions"] +
            "<|im_end|>\n<|im_start|>user\n" + prompt["userPacketJson"] +
            " /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").encode("utf-8")


def _validate(document):
    _need(type(document) is dict and set(document) == {"version", "syntheticOnly", "cases"})
    _need(document["version"] == "owner-uat-descriptive-feasibility-input-v1" and document["syntheticOnly"] is True)
    cases = document["cases"]
    _need(type(cases) is list and 1 <= len(cases) <= 4)
    seen = set()
    for row in cases:
        _need(type(row) is dict and set(row) == {"caseId", "exactPrompt"})
        _need(type(row["caseId"]) is str and row["caseId"] in CASE_IDS and row["caseId"] not in seen)
        seen.add(row["caseId"])
        prompt = row["exactPrompt"]
        _need(type(prompt) is dict and set(prompt) == {"systemInstructions", "userPacketJson"})
        _need(all(type(prompt[key]) is str and prompt[key] for key in prompt))
        _need(len(_compact(prompt)) <= 32768)
        _render(prompt)
    return cases


def _count(document, library_path, model_path, *, machine=None, system=None, loader=None):
    cases = _validate(document)
    machine = machine or platform.machine
    system = system or (lambda: sys.platform)
    _need(system() == "linux" and machine() == "x86_64")
    for path in (library_path, model_path):
        _need(path.is_absolute() and not path.is_symlink() and path.is_file())
    _need(_sha256(library_path) == LIBRARY_SHA256 and _sha256(model_path) == MODEL_SHA256)

    class Params(C.Structure):
        _fields_ = [("devices", C.c_void_p), ("tensor_buft_overrides", C.c_void_p),
                    ("n_gpu_layers", C.c_int32), ("split_mode", C.c_int), ("load_mode", C.c_int),
                    ("lazy_mode", C.c_int), ("main_gpu", C.c_int32), ("tensor_split", C.c_void_p),
                    ("progress_callback", C.c_void_p), ("progress_callback_user_data", C.c_void_p),
                    ("kv_overrides", C.c_void_p), ("vocab_only", C.c_bool), ("check_tensors", C.c_bool),
                    ("use_extra_bufts", C.c_bool), ("no_host", C.c_bool), ("no_alloc", C.c_bool), ("load_mtp", C.c_bool)]
    _need(C.sizeof(Params) == 80 and Params.vocab_only.offset == 72)
    library = (loader or C.CDLL)(str(library_path))
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
    params.vocab_only, params.n_gpu_layers, params.check_tensors = True, 0, False
    params.use_extra_bufts, params.no_alloc, params.load_mtp = False, True, False
    model = library.llama_model_load_from_file(str(model_path).encode(), params)
    _need(model)
    try:
        vocab = library.llama_model_get_vocab(model)
        _need(vocab)
        rows = []
        for case in cases:
            raw = _render(case["exactPrompt"])
            tokens = (C.c_int32 * (len(raw) + 32))()
            count = library.llama_tokenize(vocab, raw, len(raw), tokens, len(tokens), True, True)
            _need(0 < count <= len(tokens))
            packed = b"".join(int(token).to_bytes(4, "little", signed=True) for token in tokens[:count])
            rows.append({"caseId": case["caseId"], "renderedSha256": hashlib.sha256(raw).hexdigest(),
                         "renderedUtf8Bytes": len(raw), "tokens": count,
                         "tokensSha256": hashlib.sha256(packed).hexdigest(), "fitsInputBudget": count <= INPUT_LIMIT})
        return {"version": "owner-uat-descriptive-feasibility-evidence-v1", "syntheticOnly": True,
                "feasibilityOnly": True, "productionAdmission": "closed",
                "schemaBinding": "not_yet_implemented_not_admission_proof",
                "librarySha256": LIBRARY_SHA256, "modelSha256": MODEL_SHA256,
                "templateSha256": TEMPLATE_SHA256, "inputSha256": hashlib.sha256(_compact(document)).hexdigest(),
                "contextTokens": 4096, "outputReserveTokens": 1536, "marginTokens": 256,
                "inputLimitTokens": INPUT_LIMIT, "tensorWeightsLoaded": False,
                "inferenceContextCreated": False, "generationCalled": False, "cases": rows}
    finally:
        library.llama_model_free(model)


def count(document, library_path, model_path):
    return _count(document, library_path, model_path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    args = parser.parse_args()
    try:
        raw = sys.stdin.buffer.read(262145)
        _need(0 < len(raw) <= 262144)
        document = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=_pairs, parse_constant=_constant)
        print(_compact(count(document, args.library, args.model)).decode("utf-8"))
    except Exception:
        print("Owner-UAT vocabulary feasibility refused", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
