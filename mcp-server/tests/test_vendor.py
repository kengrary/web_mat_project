from __future__ import annotations

import zipfile
from pathlib import Path

from jvmind_mat_mcp import QUERY_BUNDLE_NAME


def test_vendored_query_bundle_is_present_and_valid():
    path = (
        Path(__file__).resolve().parents[1]
        / "src"
        / "jvmind_mat_mcp"
        / "vendor"
        / QUERY_BUNDLE_NAME
    )
    assert path.is_file(), f"missing {path}; run mcp-server/scripts/vendor-bundle.sh"
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
