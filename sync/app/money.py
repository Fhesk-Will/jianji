"""金额：分（integer）→ 元字符串。全链路唯一一次换算，只发生在读接口的响应序列化处。

服务端内部与存储一律用分（bigint 镜像）；本模块是唯一的转换点（docs §5.4）。
App 端已保证 amountMinor > 0，这里对负值/非整数显式报错，防御异常数据。
"""


def yuan(minor: int) -> str:
    """分 → "12.50" 形式的元字符串（两位小数，无货币符号；符号由调用方加）。"""
    if not isinstance(minor, int) or isinstance(minor, bool):
        raise ValueError(f"amountMinor 必须是整数，得到 {minor!r}")
    if minor < 0:
        raise ValueError(f"amountMinor 不允许为负（App 端收支由 type 区分），得到 {minor}")
    return f"{minor // 100}.{minor % 100:02d}"
