"""环境变量配置。缺失关键密钥时启动即失败（fail fast），不静默降级。"""

import os
import sys


class Config:
    """从环境变量读取的运行配置。"""

    def __init__(self) -> None:
        self.sync_key = os.environ.get("SYNC_KEY", "")
        self.read_key = os.environ.get("READ_KEY", "")
        self.database_url = os.environ.get(
            "DATABASE_URL", "postgres://jianji:jianji@localhost:5432/jianji"
        )
        self.snapshot_max_bytes = int(os.environ.get("SNAPSHOT_MAX_BYTES", str(20 * 1024 * 1024)))
        # 熔断阈值：存活 < 当前的 30% 且当前 >= 100 条
        self.wipe_min_live = int(os.environ.get("WIPE_MIN_LIVE", "100"))
        self.wipe_ratio = float(os.environ.get("WIPE_RATIO", "0.3"))

    def validate_startup(self) -> None:
        """启动期校验：密钥必须显式提供。"""
        missing = [k for k, v in (
            ("SYNC_KEY", self.sync_key), ("READ_KEY", self.read_key)) if not v]
        if missing:
            print(f"FATAL: 缺少环境变量 {', '.join(missing)}", file=sys.stderr)
            raise SystemExit(1)
        if self.sync_key == self.read_key:
            print("FATAL: SYNC_KEY 与 READ_KEY 不能相同", file=sys.stderr)
            raise SystemExit(1)


config = Config()
