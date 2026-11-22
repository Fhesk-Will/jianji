"""快照校验测试：每个校验规则的拒绝与放行。"""

import pytest

from app import validate
from tests.gen_fixture import CAT_SALARY, CAT_FOOD, rec, snap


def test_valid_snapshot_passes() -> None:
    s = snap([rec("r-1"), rec("r-2", type="INCOME", categoryId="cat-salary")],
              [CAT_FOOD, CAT_SALARY])
    assert validate.validate_snapshot(s) is s


def test_empty_ledger_passes() -> None:
    assert validate.validate_snapshot(snap()) is not None


def test_unknown_fields_ignored() -> None:
    s = snap([rec("r-1")], [CAT_FOOD])
    s["records"][0]["futureField"] = {"any": "thing"}
    validate.validate_snapshot(s)  # 不抛


@pytest.mark.parametrize("mutate,match", [
    (lambda s: s.update(version=2), "version"),
    (lambda s: s.update(exportedAtEpochMs="x"), "exportedAtEpochMs"),
    (lambda s: s.update(records={}), "records"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "type": "TRANSFER"}),
     "type"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "amountMinor": 0}),
     "amountMinor"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "amountMinor": -5}),
     "amountMinor"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "amountMinor": 23.5}),
     "amountMinor"),
    (lambda s: s["records"].append({**rec("r-1")}), "id 重复"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "categoryId": "ghost"}),
     "不在分类表"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "dateEpochDay": "x"}),
     "dateEpochDay"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "note": 5}), "note"),
    (lambda s: s["records"].__setitem__(0, {**rec("r-1"), "isDeleted": "yes"}),
     "isDeleted"),
    (lambda s: s["categories"].__setitem__(0, {**CAT_FOOD, "name": ""}), "name"),
    (lambda s: s["categories"].__setitem__(0, {**CAT_FOOD, "type": "FOO"}), "type"),
    (lambda s: s["categories"].__setitem__(0, {**CAT_FOOD, "sortOrder": "1"}),
     "sortOrder"),
])
def test_rejections(mutate, match: str) -> None:
    s = snap([rec("r-1")], [CAT_FOOD])
    mutate(s)
    with pytest.raises(validate.ValidationError, match=match):
        validate.validate_snapshot(s)


def test_counts() -> None:
    s = snap([rec("r-1"), rec("r-2", isDeleted=True)], [CAT_FOOD])
    assert validate.counts(s) == (2, 1, rec("r-1")["updatedAtEpochMs"])
    assert validate.counts(snap()) == (0, 0, 0)
