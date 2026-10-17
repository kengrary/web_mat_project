from __future__ import annotations

import os

from jvmind_mat_mcp.parse import (
    clean_partial_indexes,
    index_files,
    indexes_ready,
    indexes_stale,
)


def _dump(tmp_path):
    hprof = tmp_path / "app.hprof"
    hprof.write_bytes(b"JAVA PROFILE 1.0.2")
    os.utime(hprof, (1000, 1000))
    return hprof


def test_index_files_use_stem_prefix(tmp_path):
    hprof = _dump(tmp_path)
    (tmp_path / "app.index").write_bytes(b"index")
    (tmp_path / "app.idx.index").write_bytes(b"index")
    (tmp_path / "apple.index").write_bytes(b"other")
    files = {path.name for path in index_files(hprof)}
    assert files == {"app.index", "app.idx.index"}
    assert indexes_ready(hprof)


def test_indexes_stale_when_hprof_is_newer(tmp_path):
    hprof = _dump(tmp_path)
    index = tmp_path / "app.index"
    index.write_bytes(b"index")
    os.utime(index, (2000, 2000))
    assert not indexes_stale(hprof)
    os.utime(hprof, (3000, 3000))
    assert indexes_stale(hprof)


def test_clean_only_matching_indexes(tmp_path):
    hprof = _dump(tmp_path)
    matching = [tmp_path / "app.index", tmp_path / "app.threads"]
    other = [tmp_path / "apple.index", tmp_path / "other.hprof.index"]
    for path in matching + other:
        path.write_bytes(b"x")
    clean_partial_indexes(hprof)
    assert not any(path.exists() for path in matching)
    assert all(path.exists() for path in other)
