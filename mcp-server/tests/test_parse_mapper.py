from __future__ import annotations

from jvmind_mat_mcp.parse import TextProgressMapper

SAMPLE = [
    "Task: Parsing /tmp/app.hprof",
    "Subtask: Scanning /tmp/app.hprof",
    "[................",
    "[INFO] Heap /tmp/app.hprof contains 1,234,567 objects",
    "Subtask: Extracting objects from /tmp/app.hprof",
    "[..............................",
    "Task: Removing unreachable objects",
    "Subtask: Re-indexing objects",
    "Subtask: Writing /tmp/app.idx.index",
    "Task: Calculating Dominator Tree",
    "Subtask: Computing dominators",
    "Subtask: Calculate retained sizes",
]


def test_mapper_matches_phase_progression():
    mapper = TextProgressMapper()
    progress = []
    phases = []
    messages = []
    for line in SAMPLE:
        value, phase, message = mapper.feed(line)
        if value is not None:
            progress.append(value)
            phases.append(phase)
        if message:
            messages.append(message)

    assert progress == sorted(progress)
    assert progress[-1] < 1.0
    assert "Pass1 Scanning" in phases
    assert "Pass2 Extracting" in phases
    assert "GarbageCleaner" in phases
    assert "DominatorTree" in phases
    assert messages == ["[INFO] Heap /tmp/app.hprof contains 1,234,567 objects"]


def test_mapper_ignores_phase_regressions():
    mapper = TextProgressMapper()
    mapper.feed("Task: Calculating Dominator Tree")
    value, phase, _ = mapper.feed("Subtask: Re-indexing objects")
    assert value is None and phase is None


def test_mapper_reopening_is_fast_path():
    mapper = TextProgressMapper()
    value, phase, _ = mapper.feed("Task: Reopening /tmp/app.hprof")
    assert value == 0.0
    assert phase == "Reopening"
