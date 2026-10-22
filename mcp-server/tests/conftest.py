from __future__ import annotations

from pathlib import Path

import pytest

from jvmind_mat_mcp.config import Settings


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        mat_home=None,
        java="java",
        jcmd="jcmd",
        jps="jps",
        jmap="jmap",
        xmx="4g",
        service_url=None,
        preferred_port=8090,
        state_dir=tmp_path / "state",
        dump_dir=tmp_path / "dumps",
        dump_timeout=30.0,
        dump_min_free_gb=0.0,
        query_cache=True,
        http_timeout=5.0,
        poll_interval=0.01,
        poll_max_secs=2.0,
    )


@pytest.fixture
def fake_mat(tmp_path: Path) -> Path:
    home = tmp_path / "mat"
    (home / "plugins").mkdir(parents=True)
    (home / "configuration" / "org.eclipse.equinox.simpleconfigurator").mkdir(parents=True)
    (home / "plugins" / "org.eclipse.mat.api_1.17.0.jar").write_bytes(b"")
    (home / "plugins" / "org.eclipse.equinox.launcher_1.7.0.jar").write_bytes(b"")
    (home / "configuration" / "org.eclipse.equinox.simpleconfigurator" / "bundles.info").write_text(
        "org.eclipse.equinox.common,3.19.0,plugins/org.eclipse.equinox.common_3.19.0.jar,4,true\n",
        encoding="utf-8",
    )
    return home
