"""金额转换测试：全链路唯一换算点的边界。"""

import pytest

from app.money import yuan


@pytest.mark.parametrize("minor,expected", [
    (2350, "23.50"),
    (0, "0.00"),
    (1, "0.01"),
    (9, "0.09"),
    (10, "0.10"),
    (99, "0.99"),
    (100, "1.00"),
    (101, "1.01"),
    (999999999, "9999999.99"),   # App 端 9 位整数上限
])
def test_yuan(minor: int, expected: str) -> None:
    assert yuan(minor) == expected


def test_negative_rejected() -> None:
    with pytest.raises(ValueError):
        yuan(-1)


def test_non_int_rejected() -> None:
    for bad in (23.5, "2350", None, True):
        with pytest.raises(ValueError):
            yuan(bad)
