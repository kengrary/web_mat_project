from __future__ import annotations

from pathlib import Path

import pytest

from jvmind_mat_mcp.config import Settings
from jvmind_mat_mcp.mat_runtime import MatError, detect, find_mat_home, read_mat_version


def _settings(mat_home: Path, state_dir: Path) -> Settings:
    return Settings(
        mat_home=mat_home,
        java="java",
        xmx="4g",
        service_url=None,
        preferred_port=8090,
        state_dir=state_dir,
        query_cache=True,
        http_timeout=5.0,
        poll_interval=0.01,
        poll_max_secs=2.0,
    )


def test_find_mat_home_uses_env(fake_mat, tmp_path):
    assert find_mat_home(_settings(fake_mat, tmp_path)) == fake_mat.resolve()


def test_missing_mat_home_is_actionable(tmp_path):
    with pytest.raises(MatError, match="MAT_HOME"):
        find_mat_home(_settings(tmp_path / "nope", tmp_path))


def test_read_mat_version(fake_mat):
    assert read_mat_version(fake_mat) == "1.17.0"


def test_detect_rejects_wrong_version(fake_mat, tmp_path, monkeypatch):
    for jar in (fake_mat / "plugins").glob("org.eclipse.mat.api_*.jar"):
        jar.unlink()
    (fake_mat / "plugins" / "org.eclipse.mat.api_1.16.0.jar").write_bytes(b"")
    monkeypatch.setattr("jvmind_mat_mcp.mat_runtime.java_major", lambda _: 21)
    with pytest.raises(MatError, match="1.17"):
        detect(_settings(fake_mat, tmp_path))


def test_detect_rejects_old_java(fake_mat, tmp_path, monkeypatch):
    monkeypatch.setattr("jvmind_mat_mcp.mat_runtime.java_major", lambda _: 17)
    with pytest.raises(MatError, match="JDK 21"):
        detect(_settings(fake_mat, tmp_path))


def test_detect_accepts_supported_runtime(fake_mat, tmp_path, monkeypatch):
    monkeypatch.setattr("jvmind_mat_mcp.mat_runtime.java_major", lambda _: 21)
    install = detect(_settings(fake_mat, tmp_path))
    assert install.version == "1.17.0"
    assert install.launcher.name.startswith("org.eclipse.equinox.launcher_")
