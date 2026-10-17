from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx


@dataclass
class ApiResult:
    ok: bool
    data: Any = None
    error: str = ""


class MatClient:
    def __init__(
        self,
        base_url: str,
        timeout: float = 60.0,
        poll_interval: float = 2.0,
        poll_max_secs: float = 900.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.poll_interval = poll_interval
        self.poll_max_secs = poll_max_secs
        self._client = httpx.Client(
            base_url=self.base_url,
            timeout=httpx.Timeout(timeout, connect=5.0),
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: Any = None,
        timeout: float | None = None,
    ) -> ApiResult:
        clean = {key: value for key, value in (params or {}).items() if value is not None}
        try:
            response = self._client.request(
                method,
                path,
                params=clean,
                json=json_body,
                timeout=httpx.Timeout(timeout or self.timeout, connect=5.0),
            )
        except httpx.TimeoutException:
            return ApiResult(False, error="查询超时 / query timed out")
        except Exception as exc:
            return ApiResult(
                False,
                error=f"查询服务不可用 / query service unavailable: {exc}",
            )
        try:
            data = response.json()
        except Exception:
            data = response.text
        if response.status_code not in (200, 202):
            message = data.get("error", "") if isinstance(data, dict) else str(data)[:300]
            return ApiResult(
                False,
                data=data,
                error=f"查询失败({response.status_code}) / query failed: {message}",
            )
        return ApiResult(True, data=data)

    def get(self, path: str, **kwargs: Any) -> ApiResult:
        return self._request("GET", path, **kwargs)

    def post(self, path: str, **kwargs: Any) -> ApiResult:
        return self._request("POST", path, **kwargs)

    def overview(self, dump_dir: str) -> ApiResult:
        return self.get("/overview", params={"dumpDir": dump_dir, "full": "true"})

    def histogram(
        self,
        dump_dir: str,
        top: int,
        sort: str,
        object_set: str | None = None,
    ) -> ApiResult:
        return self.get(
            "/histogram",
            params={
                "dumpDir": dump_dir,
                "top": top,
                "offset": 0,
                "sort": sort,
                "approx": "true",
                "objectSet": object_set,
            },
        )

    def dominator(self, dump_dir: str, parent: str, top: int) -> ApiResult:
        return self.get(
            "/dominator",
            params={"dumpDir": dump_dir, "parent": parent, "top": top, "offset": 0},
        )

    def threads(
        self,
        dump_dir: str,
        thread: str | None = None,
        frame: int | None = None,
    ) -> ApiResult:
        return self.get(
            "/threads",
            params={"dumpDir": dump_dir, "top": 20, "thread": thread, "frame": frame},
            timeout=60.0 if thread else 30.0,
        )

    def threadlocals(self, dump_dir: str, group_by: str) -> ApiResult:
        return self.get(
            "/threadlocals",
            params={"dumpDir": dump_dir, "groupBy": group_by, "top": 20},
            timeout=60.0,
        )

    def object(self, dump_dir: str, object_id: str) -> ApiResult:
        return self.get("/object", params={"dumpDir": dump_dir, "id": object_id})

    def path2gc(self, dump_dir: str, object_id: str, exclude_weak_soft: bool) -> ApiResult:
        return self.get(
            "/path2gc",
            params={
                "dumpDir": dump_dir,
                "object": object_id,
                "excludeWeakSoft": "true" if exclude_weak_soft else "false",
            },
            timeout=60.0,
        )

    def oql(
        self,
        dump_dir: str,
        query: str,
        limit: int,
        view: str,
        sort: str,
    ) -> ApiResult:
        return self.post(
            "/oql",
            params={"dumpDir": dump_dir, "view": view, "sort": sort},
            json_body={"q": query, "limit": limit, "offset": 0},
            timeout=120.0,
        )

    def submit_leak_suspects(self, dump_dir: str) -> ApiResult:
        return self.post("/leak-suspects", params={"dumpDir": dump_dir}, timeout=10.0)

    def submit_diagnose_oom(
        self,
        dump_dir: str,
        culprit_pct: int,
        only_risky: bool,
    ) -> ApiResult:
        return self.get(
            "/diagnose-oom",
            params={
                "dumpDir": dump_dir,
                "culpritPct": culprit_pct,
                "onlyRisky": "true" if only_risky else "false",
            },
            timeout=10.0,
        )

    def connection_pools(self, dump_dir: str) -> ApiResult:
        return self.get("/connection-pools", params={"dumpDir": dump_dir})

    def pool_leaks(self, dump_dir: str, threshold_ms: int) -> ApiResult:
        return self.get(
            "/pool-leaks",
            params={"dumpDir": dump_dir, "thresholdMs": threshold_ms},
            timeout=60.0,
        )

    def top_consumers(self, dump_dir: str) -> ApiResult:
        return self.get("/top-consumers", params={"dumpDir": dump_dir}, timeout=60.0)

    def sql_in_threads(self, dump_dir: str, only_risky: bool) -> ApiResult:
        return self.get(
            "/sql-in-threads",
            params={
                "dumpDir": dump_dir,
                "onlyRisky": "true" if only_risky else "false",
            },
        )

    def get_task(self, task_id: str) -> ApiResult:
        return self.get(f"/tasks/{task_id}", timeout=10.0)

    def wait_task(self, task_id: str, max_secs: float | None = None) -> ApiResult:
        deadline = time.monotonic() + (max_secs or self.poll_max_secs)
        while time.monotonic() < deadline:
            result = self.get_task(task_id)
            if not result.ok:
                return result
            data = result.data or {}
            status = data.get("status")
            if status == "DONE":
                if data.get("resultEvicted"):
                    return ApiResult(
                        False,
                        error="结果已被回收，请重新查询 / result evicted, re-run query",
                    )
                return ApiResult(True, data=data.get("result"))
            if status in ("FAILED", "CANCELLED"):
                return ApiResult(
                    False,
                    data=data,
                    error=data.get("error") or f"任务 {status} / task {status}",
                )
            time.sleep(self.poll_interval)
        return ApiResult(
            False,
            error=f"等待任务超时（{int(max_secs or self.poll_max_secs)}s） / timed out waiting for task",
        )
