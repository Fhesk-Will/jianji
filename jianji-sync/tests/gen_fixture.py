"""双端 golden fixture 生成器。

运行：cd jianji-sync && python tests/gen_fixture.py
产出：tests/fixtures/canonical_cases.json

改 canonical 规则后必须重跑本脚本，并同步 App 端 Kotlin 实现与
test/resources/sync-fixtures/canonical_cases.json（双端镜像，同一文件内容）。
expected_hash 由本文件（服务端实现）计算 —— 注意：生成器用的是独立的最小参考
实现（见 REF_*），不 import app.canonical，这样 fixture 与被测实现解耦，
实现错了 fixture 不会跟着错。
"""

import hashlib
import json
from pathlib import Path

REF_RECORD_FIELDS = ("amountMinor", "categoryId", "dateEpochDay", "id",
                     "isDeleted", "note", "type", "createdAtEpochMs")
REF_CATEGORY_FIELDS = ("emoji", "id", "isDeleted", "name", "parentId", "sortOrder", "type")


def ref_field(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def ref_row(item, fields):
    # 注意 .get()：缺失键与 null 等价（Kotlin explicitNulls=false 会省略 null 键）
    return "\x1f".join(ref_field(item.get(f)) for f in fields)


def ref_block(items, fields):
    return "\x1e".join(
        ref_row(i, fields) for i in sorted(items, key=lambda x: x["id"].encode())
    )


def ref_hash(snapshot):
    canonical = (
        ref_block(snapshot.get("records") or [], REF_RECORD_FIELDS)
        + "\x1c"
        + ref_block(snapshot.get("categories") or [], REF_CATEGORY_FIELDS)
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def snap(records=(), categories=()):
    return {
        "version": 1,
        "exportedAtEpochMs": 1763400000000,
        "categories": list(categories),
        "records": list(records),
    }


def rec(rid, **over):
    base = dict(
        id=rid, type="EXPENSE", amountMinor=2350, categoryId="cat-food",
        dateEpochDay=20680, note="午饭", createdAtEpochMs=1763100000000,
        updatedAtEpochMs=1763100000000, isDeleted=False,
    )
    base.update(over)
    return base


def cat(cid, **over):
    base = dict(
        id=cid, name="餐饮", emoji="🍜", type="EXPENSE",
        sortOrder=1, updatedAtEpochMs=1763000000000, isDeleted=False,
        parentId=None,
    )
    base.update(over)
    return base


CAT_FOOD = cat("cat-food")
CAT_SALARY = cat("cat-salary", name="工资", emoji="💰", type="INCOME", sortOrder=8)

CASES = [
    # 1. 空账本
    ("empty_ledger", snap()),
    # 2. 单分类单记录
    ("minimal", snap([rec("r-1")], [CAT_FOOD])),
    # 3. 多记录乱序输入 → canonical 按 id 排序（与输入顺序无关）
    ("order_independence",
     snap([rec("r-3", dateEpochDay=20681, amountMinor=990),
           rec("r-1", dateEpochDay=20680),
           rec("r-2", dateEpochDay=20682, note="晚饭", amountMinor=1800)],
          [CAT_SALARY, CAT_FOOD])),
    # 4. updatedAtEpochMs 变化 → hash 不变（核心规则）
    ("updatedat_excluded",
     snap([rec("r-1", updatedAtEpochMs=9999999999999)],
          [dict(CAT_FOOD, updatedAtEpochMs=1234567890123)])),
    # 5. note 变一个字 → hash 必须变（与 case minimal 对比）
    ("note_changed",
     snap([rec("r-1", note="晚饭")], [CAT_FOOD])),
    # 6. 非 ASCII note + emoji 字段值（UTF-8 一致性）
    ("unicode_payload",
     snap([rec("r-1", note="米线🍜加蛋🥚 – 半糖")], [CAT_FOOD])),
    # 7. 墓碑记录参与指纹
    ("tombstone",
     snap([rec("r-1"), rec("r-2", isDeleted=True, note="")], [CAT_FOOD])),
    # 8. 收支混合 + 分类字段差异（emoji 空、sortOrder 大数）
    ("mixed_types",
     snap([rec("r-1"), rec("r-2", type="INCOME", amountMinor=1200000,
                           categoryId="cat-salary", note="11月工资")],
          [CAT_FOOD, cat("cat-salary", name="工资", emoji="", type="INCOME",
                         sortOrder=99)])),
    # 9. 两级分类：parentId 是指纹的一部分（挂到别的父分类 → hash 必须变）
    ("parent_changed",
     snap([rec("r-1")],
          [cat("cat-parent-a", name="吃喝", sortOrder=0),
           cat("cat-parent-b", name="居家", sortOrder=1),
           cat("cat-food", parentId="cat-parent-a")])),
    # 10. parentId=null 与缺失该键等价（Kotlin explicitNulls=false 时省略）
    ("parent_null_vs_missing",
     snap([rec("r-1")],
          [{"id": "cat-food", "name": "餐饮", "emoji": "🍜", "type": "EXPENSE",
            "sortOrder": 1, "updatedAtEpochMs": 1763000000000, "isDeleted": False},
           ])),
]


def main() -> None:
    out = {
        "description": "双端 canonical 指纹 golden cases。"
                       "Kotlin 端实现必须对每个 case 产出相同 expectedHash。",
        "note": "case4/5 是同一快照的 updatedAt 变体，两者 hash 必须相等"
                "（排除 updatedAt 规则的直接验证）。",
        "cases": [
            {"name": name, "snapshot": snapshot, "expectedHash": ref_hash(snapshot)}
            for name, snapshot in CASES
        ],
    }
    # 校验参考实现的两个不变量，防止生成器自身退化
    h4 = {c[0] for c in CASES if c[0] == "updatedat_excluded"}
    hashes = {name: ref_hash(s) for name, s in CASES}
    assert hashes["updatedat_excluded"] == hashes["minimal"], \
        "updatedAt 排除规则失效（case minimal vs updatedat_excluded）"
    assert hashes["parent_null_vs_missing"] == hashes["minimal"], \
        "parentId 缺失键与显式 null 必须等价（case minimal vs parent_null_vs_missing）"
    assert hashes["parent_changed"] != hashes["minimal"], \
        "parentId 变化未引起 hash 变化"
    assert hashes["note_changed"] != hashes["minimal"], \
        "note 变化未引起 hash 变化"
    assert hashes["order_independence"] == ref_hash(snap(
        [rec("r-1", dateEpochDay=20680),
         rec("r-2", dateEpochDay=20682, note="晚饭", amountMinor=1800),
         rec("r-3", dateEpochDay=20681, amountMinor=990)],
        [CAT_FOOD, CAT_SALARY])), "排序不稳定"

    path = Path(__file__).parent / "fixtures" / "canonical_cases.json"
    path.write_text(
        json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"OK: {len(CASES)} cases -> {path}")


if __name__ == "__main__":
    main()
