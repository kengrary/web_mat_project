from __future__ import annotations

from jvmind_mat_mcp.engine import parsing_message
from jvmind_mat_mcp.mat_client import ApiResult
from jvmind_mat_mcp.mat_runtime import MatError
from jvmind_mat_mcp.tools import Toolbox


class FakeClient:
    def histogram(self, dump_dir, top, sort, object_set):
        return ApiResult(
            True,
            {
                "rows": [
                    {
                        "className": "com.example.Foo",
                        "count": 10,
                        "shallowBytes": 1024,
                        "retainedBytes": 2048,
                        "objectId": "0x20",
                    }
                ]
            },
        )

    def overview(self, dump_dir):
        return ApiResult(
            True,
            {
                "usedHeapSize": 1024 * 1024,
                "committedHeapSize": 2 * 1024 * 1024,
                "numObjects": 1234,
                "numClasses": 56,
                "numClassLoaders": 7,
                "leakSuspectCount": 0,
            },
        )

    def threads(self, dump_dir, thread=None, frame=None):
        return ApiResult(True, [])

    def oql(self, dump_dir, q, limit, view, sort):
        return ApiResult(True, {"rows": [{"a": 1}], "resultSetId": "rs-1"})


class FakeEngine:
    def __init__(self, ready: bool = True):
        self.ready_flag = ready

    def ready(self, report_id):
        if not self.ready_flag:
            raise MatError("解析中(进度 42%，阶段 Pass1 Scanning)，稍后再试")
        return (
            {
                "report_id": report_id,
                "filename": "app.hprof",
                "dump_dir": "/tmp",
                "path": "/tmp/app.hprof",
            },
            FakeClient(),
        )


def test_histogram_formatting():
    text = Toolbox(FakeEngine()).histogram("hd_x", top=20, sort="retained")
    assert "com.example.Foo" in text
    assert "1.0 KB" in text
    assert "2.0 KB" in text
    assert "id=0x20" in text


def test_overview_formatting():
    text = Toolbox(FakeEngine()).overview("hd_x")
    assert "1.0 MB" in text
    assert "1,234" in text
    assert "Leak suspects: 0" in text


def test_parsing_error_is_returned_not_raised():
    text = Toolbox(FakeEngine(ready=False)).overview("hd_x")
    assert "42%" in text


def test_thread_address_guard():
    text = Toolbox(FakeEngine()).threads("hd_x", thread="0x20")
    assert "not a thread id" in text


def test_oql_result_set():
    text = Toolbox(FakeEngine()).oql("hd_x", "SELECT * FROM java.lang.String s")
    assert "rs-1" in text


def test_parsing_message_variants():
    assert "queued" in parsing_message({"status": "QUEUED"})
    assert "42%" in parsing_message({"status": "PARSING", "progress": 0.42, "phase": "Pass2"})
    assert "失败" in parsing_message({"status": "FAILED", "error": "boom"})
