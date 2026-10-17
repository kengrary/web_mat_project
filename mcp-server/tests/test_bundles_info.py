from __future__ import annotations

from pathlib import Path

from jvmind_mat_mcp import QUERY_BUNDLE_NAME, mat_runtime
from jvmind_mat_mcp.mat_runtime import MatInstall, ensure_bundle


def _install(home: Path) -> MatInstall:
    return MatInstall(
        home=home,
        version="1.17.0",
        launcher=next((home / "plugins").glob("org.eclipse.equinox.launcher_*.jar")),
        java="java",
        java_major=21,
    )


def test_ensure_bundle_installs_and_is_idempotent(tmp_path, fake_mat, monkeypatch):
    source = tmp_path / "query.jar"
    source.write_bytes(b"query-bundle")
    monkeypatch.setattr(mat_runtime, "bundle_source", lambda: source)
    install = _install(fake_mat)

    first = ensure_bundle(install)
    assert first.copied and first.registered and first.changed
    assert (fake_mat / "plugins" / QUERY_BUNDLE_NAME).read_bytes() == b"query-bundle"
    bundles = install.bundles_info.read_text(encoding="utf-8")
    assert bundles.count("com.jvmind.mat.query,") == 1
    assert "org.eclipse.equinox.common" in bundles
    assert install.bundles_info.with_name(install.bundles_info.name + ".jvmind-mcp.bak").exists()

    second = ensure_bundle(install)
    assert not second.copied and not second.registered and not second.changed
    assert install.bundles_info.read_text(encoding="utf-8") == bundles


def test_ensure_bundle_replaces_old_version(tmp_path, fake_mat, monkeypatch):
    source = tmp_path / "query.jar"
    source.write_bytes(b"new")
    monkeypatch.setattr(mat_runtime, "bundle_source", lambda: source)
    install = _install(fake_mat)
    old = fake_mat / "plugins" / "com.jvmind.mat.query-0.0.9.jar"
    old.write_bytes(b"old")
    bundles = install.bundles_info
    bundles.write_text(
        bundles.read_text(encoding="utf-8")
        + "com.jvmind.mat.query,0.0.9,plugins/com.jvmind.mat.query-0.0.9.jar,4,true\n",
        encoding="utf-8",
    )

    ensure_bundle(install)
    assert not old.exists()
    lines = [
        line
        for line in bundles.read_text(encoding="utf-8").splitlines()
        if "com.jvmind.mat" in line
    ]
    assert lines == [f"com.jvmind.mat.query,0.1.0,plugins/{QUERY_BUNDLE_NAME},4,true"]


def test_uninstall_removes_bundle_and_registration(tmp_path, fake_mat, monkeypatch):
    source = tmp_path / "query.jar"
    source.write_bytes(b"query")
    monkeypatch.setattr(mat_runtime, "bundle_source", lambda: source)
    install = _install(fake_mat)
    ensure_bundle(install)
    assert mat_runtime.uninstall_bundle(install)
    assert not install.bundle_jar.exists()
    assert "com.jvmind.mat.query" not in install.bundles_info.read_text(encoding="utf-8")
    assert not mat_runtime.uninstall_bundle(install)
