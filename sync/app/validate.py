"""快照解析校验（sync 第一步）。

原则：坏数据 400 + 明细（指向具体记录/字段），好数据放行。
校验的是"结构合法性 + 引用一致性"，语义熔断（清空疑似）在 sync.py，两层分开。
未知字段忽略（SnapshotV1 前向兼容，docs §3.1）。
"""

VALID_TYPES = {"EXPENSE", "INCOME"}


class ValidationError(Exception):
    """携带用户可读明细的校验错误。message 直接进 400 响应。"""


def _require(cond: bool, msg: str) -> None:
    if not cond:
        raise ValidationError(msg)


def _is_int(v: object) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def validate_snapshot(snap: object) -> dict:
    """校验并规范化 SnapshotV1。通过则原样返回（只读，不修改）。

    Raises:
        ValidationError: 首个错误即抛出，message 含定位信息。
    """
    _require(isinstance(snap, dict), "快照根必须是 JSON 对象")
    _require(snap.get("version") == 1, f"version 必须为 1，得到 {snap.get('version')!r}")
    _require(
        _is_int(snap.get("exportedAtEpochMs")),
        "exportedAtEpochMs 必须是整数毫秒时间戳",
    )
    categories = snap.get("categories")
    records = snap.get("records")
    _require(isinstance(categories, list), "categories 必须是数组")
    _require(isinstance(records, list), "records 必须是数组")

    cat_ids: set[str] = set()
    for i, c in enumerate(categories):
        _require(isinstance(c, dict), f"categories[{i}] 必须是对象")
        cid = c.get("id")
        _require(isinstance(cid, str) and cid != "", f"categories[{i}].id 必须是非空字符串")
        _require(cid not in cat_ids, f"分类 id 重复：{cid}")
        cat_ids.add(cid)
        _require(isinstance(c.get("name"), str) and c.get("name") != "",
                 f"category {cid}: name 必须是非空字符串")
        _require(isinstance(c.get("emoji"), str), f"category {cid}: emoji 必须是字符串（可为空）")
        _require(c.get("type") in VALID_TYPES, f"category {cid}: type 必须是 EXPENSE/INCOME")
        _require(_is_int(c.get("sortOrder")), f"category {cid}: sortOrder 必须是整数")
        pid = c.get("parentId")
        _require(pid is None or (isinstance(pid, str) and pid != ""),
                 f"category {cid}: parentId 必须是 null 或非空字符串")
        _require(pid != cid, f"category {cid}: parentId 不能指向自身")
        _require(_is_int(c.get("updatedAtEpochMs")) and c["updatedAtEpochMs"] > 0,
                 f"category {cid}: updatedAtEpochMs 必须是正整数毫秒")
        _require(isinstance(c.get("isDeleted"), bool), f"category {cid}: isDeleted 必须是布尔")

    # 两级分类引用一致性：父分类必须存在（第二轮校验，因为父可能定义在子之后）
    for c in categories:
        pid = c.get("parentId")
        if pid is not None:
            _require(pid in cat_ids, f"category {c['id']}: parentId {pid} 不在分类表中")

    seen: set[str] = set()
    for i, r in enumerate(records):
        _require(isinstance(r, dict), f"records[{i}] 必须是对象")
        rid = r.get("id")
        _require(isinstance(rid, str) and rid != "", f"records[{i}].id 必须是非空字符串")
        _require(rid not in seen, f"记录 id 重复：{rid}")
        seen.add(rid)
        _require(r.get("type") in VALID_TYPES, f"record {rid}: type 必须是 EXPENSE/INCOME")
        amt = r.get("amountMinor")
        _require(_is_int(amt) and amt > 0, f"record {rid}: amountMinor 必须是正整数（分），得到 {amt!r}")
        cat = r.get("categoryId")
        _require(isinstance(cat, str) and cat != "", f"record {rid}: categoryId 必须是非空字符串")
        _require(cat in cat_ids, f"record {rid}: categoryId {cat} 不在分类表中")
        _require(_is_int(r.get("dateEpochDay")), f"record {rid}: dateEpochDay 必须是整数天")
        _require(isinstance(r.get("note"), str), f"record {rid}: note 必须是字符串（可为空）")
        for k in ("createdAtEpochMs", "updatedAtEpochMs"):
            _require(_is_int(r.get(k)) and r[k] > 0, f"record {rid}: {k} 必须是正整数毫秒")
        _require(isinstance(r.get("isDeleted"), bool), f"record {rid}: isDeleted 必须是布尔")

    return snap


def counts(snap: dict) -> tuple[int, int, int]:
    """→ (record_count 含墓碑, live_count 不含墓碑, max_updated_at)。落库统计用。"""
    records = snap["records"]
    live = [r for r in records if not r["isDeleted"]]
    max_updated = max((r["updatedAtEpochMs"] for r in records), default=0)
    max_updated = max(max_updated,
                      max((c["updatedAtEpochMs"] for c in snap["categories"]), default=0))
    return len(records), len(live), max_updated
