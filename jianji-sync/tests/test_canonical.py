"""canonical 指纹测试：直接消费双端 golden fixture（canonical_cases.json）。

这是"双端指纹一致性"的服务端半边。App 端 Kotlin 测试消费同一份 fixture
（镜像到 test/resources/sync-fixtures/），两端全绿 = 规则对齐。
"""

import json
import sys
from pathlib import Path

import pytest

from app import canonical

FIXTURE = Path(__file__).parent / "fixtures" / "canonical_cases.json"


def _cases():
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return [(c["name"], c["snapshot"], c["expectedHash"]) for c in data["cases"]]


@pytest.mark.parametrize("name,snapshot,expected", _cases(),
                         ids=[c[0] for c in _cases()])
def test_golden_fixture(name: str, snapshot: dict, expected: str) -> None:
    got = canonical.content_hash(snapshot)
    assert got == expected, (
        f"case {name}: 服务端指纹与 fixture 不一致。\n"
        f"  fixture: {expected}\n  实现:   {got}\n"
        f"  若规则有意变更：双端同提交 + 重跑 tests/gen_fixture.py"
    )


def test_fixture_itself_loaded() -> None:
    assert len(_cases()) >= 8, "fixture 用例数量异常，请检查 gen_fixture.py"
