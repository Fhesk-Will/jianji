"""客户端配对：数据服务器最多只允许一台同步设备（阶段 6，里程碑 M4）。

语义：
  - 服务端记单行配对表，先到先得；此后 SYNC_KEY 写接口（sync/versions/export）
    都必须携带相同客户端身份（X-Jianji-Client 头），否则 409 client_mismatch。
  - 未配对时首个写请求自动抢占（身份取请求头，缺省记 "legacy"），
    因此旧 App（不带身份头）也能占坑，新设备再来会被拒。
  - READ_KEY 只读通道不参与配对：AI 查询不是同步设备。
  - unpair 解除配对（SYNC_KEY 鉴权），用于 App「接管此服务器」与重装救急。

并发：单行表主键 id=1，INSERT ... ON CONFLICT DO NOTHING 后回读比较，
两个并发抢占只有一个成功。
"""

from asyncpg.pool import Pool

HEADER = "X-Jianji-Client"
LEGACY_CLIENT_ID = "legacy"
CLIENT_ID_MAX_LEN = 64


class ClientMismatch(Exception):
    """已与其他客户端配对。hint 供 409 响应展示。"""

    def __init__(self, hint: str = "服务器已与另一台设备配对，无法从此设备同步") -> None:
        self.hint = hint
        super().__init__(hint)


def normalize(presented: str | None) -> str:
    """请求头取身份：缺省记 legacy（旧 App）；带空白视为缺失。"""
    value = (presented or "").strip()
    return value if value else LEGACY_CLIENT_ID


def valid_client_id(client_id: str) -> bool:
    """显式配对（/api/pair）的身份格式：非空、长度受限。"""
    value = client_id.strip()
    return 0 < len(value) <= CLIENT_ID_MAX_LEN


async def _claim_then_read(pool: Pool, client_id: str) -> str:
    """以 client_id 抢占（若未配对）并返回表中实际身份。新抢占置 needs_merge。"""
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "INSERT INTO client_pairing (id, client_id, needs_merge) "
                "VALUES (1, $1, TRUE) ON CONFLICT (id) DO NOTHING",
                client_id,
            )
            row = await conn.fetchrow(
                "SELECT client_id FROM client_pairing WHERE id = 1"
            )
    assert row is not None
    return row["client_id"]


async def claim_or_check(pool: Pool, presented: str | None) -> None:
    """写接口守卫：未配对则抢占，配对过则要求同一身份。

    Raises:
        ClientMismatch: 已被其他客户端占用。
    """
    client_id = normalize(presented)
    if await _claim_then_read(pool, client_id) != client_id:
        raise ClientMismatch()


async def pair(pool: Pool, client_id: str) -> bool:
    """显式配对（保存配置时探测）。

    Returns:
        True：抢占成功或已是本设备，可以继续保存配置；
        False：已被其他设备占用，App 应拒绝保存并提供「接管」入口。
    """
    if not valid_client_id(client_id):
        return False
    return await _claim_then_read(pool, client_id.strip()) == client_id.strip()


async def unpair(pool: Pool) -> None:
    """解除配对；之后首个写请求或 /api/pair 可重新抢占（新抢占会重新置 needs_merge）。"""
    async with pool.acquire() as conn:
        await conn.execute("DELETE FROM client_pairing WHERE id = 1")


async def should_merge(conn) -> bool:
    """新配对设备的首次同步是否与镜像做并集合并。调用方持有连接。"""
    row = await conn.fetchrow(
        "SELECT needs_merge FROM client_pairing WHERE id = 1"
    )
    return bool(row and row["needs_merge"])


async def clear_merge(conn) -> None:
    """客户端已全量（推送内容 ⊇ 镜像）后转正常整本替换。调用方持有连接。"""
    await conn.execute(
        "UPDATE client_pairing SET needs_merge = FALSE WHERE id = 1"
    )
