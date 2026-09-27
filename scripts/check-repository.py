"""Fail without printing secret values; examine only Git-tracked repository files."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
errors = []
secret_patterns = [
    re.compile(rb"\bsk-[A-Za-z0-9_-]{24,}"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{30,}"),
]
for name in filter(None, paths):
    path = root / name
    if not path.is_file():
        continue
    if (path.name == ".env" or path.name.startswith(".env.")) and not path.name.endswith(".example"):
        errors.append(f"{name}: 私密环境配置不得提交")
    if any(part in {"secrets", ".local", "node_modules", "target", ".next"} for part in Path(name).parts):
        errors.append(f"{name}: 本地生成内容不得提交")
    data = path.read_bytes()
    if any(pattern.search(data) for pattern in secret_patterns):
        errors.append(f"{name}: 检测到疑似真实密钥（内容已隐藏）")
    if path.suffix in {".md", ".yml", ".yaml", ".java", ".ts", ".tsx", ".py", ".json", ".xml"}:
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            errors.append(f"{name}: 文本应使用 UTF-8 编码")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"仓库检查通过：{len(list(filter(None, paths)))} 个受版本管理的文件。")
