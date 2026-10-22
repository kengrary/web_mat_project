from __future__ import annotations

from jvmind_mat_mcp.config import Settings, default_xmx
from jvmind_mat_mcp.mat_runtime import parse_java_version


def test_settings_env_overrides(tmp_path):
    settings = Settings.from_env(
        {
            "MAT_HOME": str(tmp_path / "mat"),
            "JVMIND_JAVA": "/opt/jdk21/bin/java",
            "JVMIND_MAT_XMX": "12g",
            "JVMIND_MCP_STATE_DIR": str(tmp_path / "state"),
            "JVMIND_MCP_DUMP_DIR": str(tmp_path / "dumps"),
            "JVMIND_DUMP_TIMEOUT": "60",
            "JVMIND_DUMP_MIN_FREE_GB": "1.5",
            "JVMIND_JCMD": "/opt/jdk21/bin/jcmd",
            "JVMIND_JPS": "/opt/jdk21/bin/jps",
            "JVMIND_JMAP": "/opt/jdk21/bin/jmap",
            "MAT_QUERY_SERVICE_URL": "http://127.0.0.1:9999/",
            "MAT_QUERY_PORT": "9001",
            "MAT_QUERY_CACHE": "false",
        }
    )
    assert settings.mat_home == tmp_path / "mat"
    assert settings.java == "/opt/jdk21/bin/java"
    assert settings.xmx == "12g"
    assert settings.state_dir == tmp_path / "state"
    assert settings.dump_dir == tmp_path / "dumps"
    assert settings.dump_timeout == 60
    assert settings.dump_min_free_gb == 1.5
    assert settings.jcmd == "/opt/jdk21/bin/jcmd"
    assert settings.jps == "/opt/jdk21/bin/jps"
    assert settings.jmap == "/opt/jdk21/bin/jmap"
    assert settings.service_url == "http://127.0.0.1:9999"
    assert settings.preferred_port == 9001
    assert settings.query_cache is False


def test_settings_rejects_bad_xmx(tmp_path):
    settings = Settings.from_env({"JVMIND_MAT_XMX": "lots", "JVMIND_MCP_STATE_DIR": str(tmp_path)})
    assert settings.xmx == default_xmx()


def test_parse_java_versions():
    assert parse_java_version('openjdk version "21.0.5" 2025-01-01') == 21
    assert parse_java_version('java version "17.0.12"') == 17
    assert parse_java_version('java version "1.8.0_402"') == 8
