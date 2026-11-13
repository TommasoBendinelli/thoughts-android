# Written for Thoughts. License is GPLv3.

import importlib.util
from importlib.machinery import SourceFileLoader
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "thoughts-mobilectl"
SPEC = importlib.util.spec_from_loader(
    "thoughts_mobilectl",
    SourceFileLoader("thoughts_mobilectl", str(SCRIPT)),
)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_parse_broadcast_result_decodes_json_data():
    output = (
        "Broadcasting: Intent { act=org.tbend.thoughts.action.SYNC_NOW }\n"
        "Broadcast completed: result=-1, data=\"{\\\"schema_version\\\":1,"
        "\\\"accepted\\\":true,\\\"operation_id\\\":\\\"abc\\\"}\"\n"
    )

    result_code, payload = MODULE.parse_broadcast_result(output)

    assert result_code == -1
    assert payload["accepted"] is True
    assert payload["operation_id"] == "abc"


def test_parse_broadcast_result_decodes_android_shell_unescaped_data():
    output = (
        "Broadcasting: Intent { act=org.tbend.thoughts.action.SYNC_STATUS }\n"
        'Broadcast completed: result=2, data="{"accepted":false,'
        '"error_code":"unknown_operation","message":"Sync operation was not found."}", '
        "extras: Bundle[mParcelledData.dataSize=392]\n"
    )

    result_code, payload = MODULE.parse_broadcast_result(output)

    assert result_code == 2
    assert payload["accepted"] is False
    assert payload["error_code"] == "unknown_operation"


def test_exit_codes_are_stable():
    assert MODULE.exit_code(-1, {"state": "queued"}) == 0
    assert MODULE.exit_code(2, {"error_code": "invalid_request"}) == 2
    assert MODULE.exit_code(3, {"error_code": "transport_unavailable"}) == 3
    assert MODULE.exit_code(4, {"state": "failed"}) == 4
    assert MODULE.exit_code(-1, {"client_timeout": True}) == 5
    assert MODULE.exit_code(6, {"error_code": "busy"}) == 6
