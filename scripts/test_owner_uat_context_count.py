import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import owner_uat_context_count as helper


def document(system="s", packet="{}"):
    return {"version": "owner-uat-descriptive-feasibility-input-v1", "syntheticOnly": True,
            "cases": [{"caseId": "activities-review", "exactPrompt":
                       {"systemInstructions": system, "userPacketJson": packet}}]}


class NativeFunction:
    def __init__(self, fn):
        self.fn = fn
    def __call__(self, *args):
        return self.fn(*args)


class CounterTests(unittest.TestCase):
    def test_exact_render_and_unicode(self):
        raw = helper._render({"systemInstructions": "é", "userPacketJson": "{}"})
        self.assertEqual(raw, "<|im_start|>system\n\né<|im_end|>\n<|im_start|>user\n{} /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n".encode())
        self.assertEqual(len(raw), len(raw.decode().encode()))

    def test_rejects_invalid_shape_duplicate_ids_and_bounds(self):
        bad = document()
        bad["extra"] = 1
        with self.assertRaises(ValueError):
            helper._validate(bad)
        duplicate = document()
        duplicate["cases"].append(duplicate["cases"][0])
        with self.assertRaises(ValueError):
            helper._validate(duplicate)
        with self.assertRaises(ValueError):
            helper._validate(document(packet="x" * 32768))

    def test_native_counts_and_cleanup(self):
        class Params:
            pass
        params = Params()
        freed = []
        tokens_count = 2304
        def tokenize(vocab, raw, raw_len, output, capacity, special, parse):
            for i in range(tokens_count):
                output[i] = i
            return tokens_count
        lib = SimpleNamespace(
            llama_model_default_params=NativeFunction(lambda: params),
            llama_model_load_from_file=NativeFunction(lambda path, p: 17),
            llama_model_get_vocab=NativeFunction(lambda model: 23),
            llama_model_free=NativeFunction(lambda model: freed.append(model)),
            llama_tokenize=NativeFunction(tokenize))
        with tempfile.TemporaryDirectory() as tmp:
            library, model = Path(tmp) / "lib", Path(tmp) / "model"
            library.write_bytes(b"lib")
            model.write_bytes(b"model")
            with patch.object(helper, "LIBRARY_SHA256", hashlib.sha256(b"lib").hexdigest()), \
                 patch.object(helper, "MODEL_SHA256", hashlib.sha256(b"model").hexdigest()):
                result = helper._count(document(packet="x" * 2400), library, model, machine=lambda: "x86_64",
                                       system=lambda: "linux", loader=lambda path: lib)
            self.assertTrue(result["cases"][0]["fitsInputBudget"])
            self.assertEqual(result["inputLimitTokens"], 2304)
            self.assertEqual(params.vocab_only, True)
            self.assertEqual(params.n_gpu_layers, 0)
            self.assertEqual(freed, [17])
            tokens_count = 2305
            with patch.object(helper, "LIBRARY_SHA256", hashlib.sha256(b"lib").hexdigest()), \
                 patch.object(helper, "MODEL_SHA256", hashlib.sha256(b"model").hexdigest()):
                over = helper._count(document(packet="x" * 2400), library, model, machine=lambda: "x86_64",
                                     system=lambda: "linux", loader=lambda path: lib)
            self.assertFalse(over["cases"][0]["fitsInputBudget"])
            self.assertEqual(freed, [17, 17])
            tokens_count = -2400
            with patch.object(helper, "LIBRARY_SHA256", hashlib.sha256(b"lib").hexdigest()), \
                 patch.object(helper, "MODEL_SHA256", hashlib.sha256(b"model").hexdigest()):
                with self.assertRaises(ValueError):
                    helper._count(document(packet="x" * 2400), library, model, machine=lambda: "x86_64",
                                  system=lambda: "linux", loader=lambda path: lib)
            self.assertEqual(freed, [17, 17, 17])
            self.assertFalse(params.check_tensors)
            self.assertFalse(params.use_extra_bufts)
            self.assertTrue(params.no_alloc)
            self.assertFalse(params.load_mtp)

    def test_drift_platform_refuse_before_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            library, model = Path(tmp) / "lib", Path(tmp) / "model"
            library.write_bytes(b"drift")
            model.write_bytes(b"model")
            loaded = []
            with self.assertRaises(ValueError):
                helper._count(document(), library, model, machine=lambda: "x86_64",
                              system=lambda: "linux", loader=lambda path: loaded.append(path))
            self.assertEqual(loaded, [])
            with self.assertRaises(ValueError):
                helper._count(document(), library, model, machine=lambda: "arm64",
                              system=lambda: "linux", loader=lambda path: loaded.append(path))
            self.assertEqual(loaded, [])

    def test_cli_rejects_duplicate_keys_nonfinite_empty_and_oversized_before_native_load(self):
        bad_inputs = [b'', b'{"version":1,"version":2}', b'{"value":NaN}', b'x' * 262145,
                      b'{"value":"\xff"}']
        for raw in bad_inputs:
            with self.subTest(raw_bytes=len(raw)), \
                 patch.object(helper.sys, "argv", ["counter", "--library", "/lib", "--model", "/model"]), \
                 patch.object(helper.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(raw))), \
                 patch.object(helper.sys, "stderr", io.StringIO()), \
                 patch.object(helper, "count") as count:
                self.assertEqual(helper.main(), 1)
                count.assert_not_called()

    def test_cli_reads_entire_valid_document_without_peek_or_native_access(self):
        raw = json.dumps(document()).encode()
        with patch.object(helper.sys, "argv", ["counter", "--library", "/lib", "--model", "/model"]), \
             patch.object(helper.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(raw))), \
             patch.object(helper.sys, "stdout", io.StringIO()) as stdout, \
             patch.object(helper, "count", return_value={"feasibilityOnly": True}) as count:
            self.assertEqual(helper.main(), 0)
            count.assert_called_once_with(document(), Path("/lib"), Path("/model"))
            self.assertEqual(json.loads(stdout.getvalue()), {"feasibilityOnly": True})


if __name__ == "__main__":
    unittest.main()
