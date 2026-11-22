"""内容指纹（canonical form + sha256）。

规则与《服务器端需求与架构.md》§3.2 严格一致，且与 App 端（Kotlin）实现逐字节对齐——
这是双端去重的根基。任何一端修改规则，必须双端同提交并重新生成
dev/tests/sync/fixtures/canonical_cases.json（golden fixture 是对答案的唯一裁判）。

canonical 形式的精确定义（两端实现的共同契约）：
  1. records 按 id 升序（UTF-8 字节序；id 为 UUID/ASCII，两端排序等价），
     categories 同理。
  2. 每条记录拼成一行：字段按下方 RECORD_FIELDS / CATEGORY_FIELDS 的固定顺序，
     字段间用 U+001F（UNIT SEPARATOR）连接。
  3. 字段序列化：int → 十进制字符串；bool → "true"/"false"；str → 原样（含空串、
     含 emoji、含 U+001F 之外的任意字符；note/emoji 理论不会含控制字符，即使含
     了两端也一致，安全性不依赖转义）。
  4. 行间用 U+001E（RECORD SEPARATOR）连接；records 块与 categories 块之间用
     U+001C（FILE SEPARATOR）连接。空块的字符串为 ""。
  5. 指纹 = sha256(canonical.encode("utf-8")).hexdigest()。

字段顺序（§3.2 定义）：
  - record:  amountMinor, categoryId, dateEpochDay, id, isDeleted, note, type,
             createdAtEpochMs        （前 7 个按字母序，createdAtEpochMs 挂尾）
  - category: emoji, id, isDeleted, name, parentId, sortOrder, type
  - 两者的 updatedAtEpochMs 一律排除——这是"版本前进 ⟺ 语义内容变化"的关键。

parentId（两级分类）：null → 空字符串（一级分类）；非 null 为父分类 id。
App 端不会用空串当 parentId，null 与 "" 无歧义。Kotlin 端序列化 null
时同样映射为 ""，以 fixture 为准。
"""

import hashlib

RECORD_FIELDS = (
    "amountMinor", "categoryId", "dateEpochDay", "id",
    "isDeleted", "note", "type", "createdAtEpochMs",
)
CATEGORY_FIELDS = ("emoji", "id", "isDeleted", "name", "parentId", "sortOrder", "type")

US = "\x1f"  # 字段分隔
RS = "\x1e"  # 行分隔
FS = "\x1c"  # 块分隔


def _field_str(value: object) -> str:
    """字段值 → 字符串（跨语言一致的三种类型；null → 空串，见 docstring）。"""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    return value if isinstance(value, str) else str(value)


def _row(item: dict, fields: tuple) -> str:
    return US.join(_field_str(item.get(f)) for f in fields)


def _block(items: list, fields: tuple) -> str:
    ordered = sorted(items, key=lambda x: x["id"].encode("utf-8"))
    return RS.join(_row(i, fields) for i in ordered)


def canonical_form(snapshot: dict) -> str:
    """快照 → canonical 字符串。snapshot 结构 = SnapshotV1（见 docs §3.1）。"""
    records = snapshot.get("records") or []
    categories = snapshot.get("categories") or []
    return _block(records, RECORD_FIELDS) + FS + _block(categories, CATEGORY_FIELDS)


def content_hash(snapshot: dict) -> str:
    """快照 → 内容指纹（sha256 hex）。"""
    return hashlib.sha256(canonical_form(snapshot).encode("utf-8")).hexdigest()
