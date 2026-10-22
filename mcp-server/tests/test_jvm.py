from __future__ import annotations

from pathlib import Path

import pytest

from jvmind_mat_mcp import jvm
from jvmind_mat_mcp.config import Settings
from jvmind_mat_mcp.mat_runtime import MatError


def test_parse_jcmd_filters_helpers_and_mat():
    output = """
123 com.example.Main -Xmx1g
456 sun.tools.jcmd.JCmd -l
789 org.eclipse.equinox.launcher.Main -application com.jvmind.mat.query.app
"""
    processes = jvm.parse_jcmd_list(output)
    assert [process.pid for process in processes] == [123]
    assert processes[0].main_class == "com.example.Main"
    assert processes[0].jvm_args == ("-Xmx1g",)


def test_parse_jps_filters_helpers():
    output = """
123 com.example.Main -Xmx1g --add-opens java.base/java.lang=ALL-UNNAMED
456 jdk.jcmd/sun.tools.jps.Jps -Dapplication.home=/opt/jdk
789 -- process information unavailable
"""
    processes = jvm.parse_jps_list(output)
    assert [process.pid for process in processes] == [123]
    assert processes[0].jvm_args[0] == "-Xmx1g"


def test_discover_merges_jcmd_and_jps(monkeypatch, settings: Settings):
    def fake_run(cmd, timeout=20.0):
        if cmd[0] == settings.jcmd:
            return "123 com.example.Main -Xmx1g\n"
        return "123 com.example.Main -Xmx1g -Dpassword=secret\n"

    monkeypatch.setattr(jvm, "_run", fake_run)
    processes = jvm.discover_processes(settings)
    assert len(processes) == 1
    assert processes[0].pid == 123
    assert any("-Dpassword=***" in arg for arg in processes[0].to_dict()["jvmArgs"])


def test_discover_errors_when_both_tools_fail(monkeypatch, settings: Settings):
    def fake_run(cmd, timeout=20.0):
        raise MatError(f"missing {cmd[0]}")

    monkeypatch.setattr(jvm, "_run", fake_run)
    with pytest.raises(MatError, match="Could not discover"):
        jvm.discover_processes(settings)


def test_mask_secrets():
    masked = jvm.mask_secrets("-Ddb.password=hunter2 --token abc -Dapp.name=foo")
    assert "hunter2" not in masked
    assert "abc" not in masked
    assert "foo" in masked


def test_unique_dump_path(tmp_path: Path):
    first = jvm.unique_dump_path(tmp_path, 123, "com.example.Main")
    first.write_bytes(b"x")
    second = jvm.unique_dump_path(tmp_path, 123, "com.example.Main")
    assert first != second
    assert second.name.endswith(".hprof")
    assert "com.example.Main-123-" in second.name


def test_live_dump_command(settings: Settings, tmp_path: Path):
    path = tmp_path / "app.hprof"
    assert jvm.live_dump_command(settings, 123, path) == [
        settings.jmap,
        f"-dump:live,format=b,file={path}",
        "123",
    ]
    with pytest.raises(MatError, match="commas"):
        jvm.live_dump_command(settings, 123, tmp_path / "a,b.hprof")


def test_check_space_rejects_tiny_disk(monkeypatch, tmp_path: Path):
    monkeypatch.setattr(jvm, "disk_free", lambda directory: 100)
    with pytest.raises(MatError, match="Not enough disk space"):
        jvm.check_space(tmp_path, None, 1000)
    free, warning = jvm.check_space(tmp_path, None, 100)
    assert free == 100
    assert warning is None
