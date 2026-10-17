from __future__ import annotations

from jvmind_mat_mcp.store import StateStore


def test_store_roundtrip(tmp_path):
    store = StateStore(tmp_path)
    path = tmp_path / "app.hprof"
    path.write_bytes(b"JAVA PROFILE 1.0.2")
    record = store.upsert(path, 1.0, 10)
    assert record["report_id"].startswith("hd_")
    assert record["status"] == "QUEUED"

    again = store.upsert(path, 2.0, 20)
    assert again["report_id"] == record["report_id"]
    assert again["size"] == 20

    store.update(record["report_id"], status="DONE", progress=1.0)
    assert store.get(record["report_id"])["status"] == "DONE"
    assert len(store.list()) == 1

    store.set_service({"port": 9000})
    assert store.get_service() == {"port": 9000}
    store.set_service(None)
    assert store.get_service() is None

    removed = store.remove(record["report_id"])
    assert removed["report_id"] == record["report_id"]
    assert store.list() == []


def test_store_tolerates_corrupt_state(tmp_path):
    (tmp_path / "state.json").write_text("{not json", encoding="utf-8")
    store = StateStore(tmp_path)
    assert store.list() == []
    assert store.read()["version"] == 1
