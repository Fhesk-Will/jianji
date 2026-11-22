import os
import sys
from pathlib import Path

# 测试密钥（必须在 app.config / app.main 导入前注入；conftest 先于测试模块执行）
os.environ.setdefault("SYNC_KEY", "test-sync-key-0000000000000000000000000000")
os.environ.setdefault("READ_KEY", "test-read-key-00000000000000000000000000000")
os.environ.setdefault("DATABASE_URL", "postgres://jianji:jianji@localhost:5432/jianji")

sys.path.insert(0, str(Path(__file__).parent.parent))
