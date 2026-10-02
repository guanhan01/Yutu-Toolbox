#!/usr/bin/env python3
"""从 CHANGELOG.md 抽取某个版本的段落，用于 GitHub Release 正文。

为什么单独成脚本：正文内容写死在 workflow 里容易与仓库漂移，让 CI 直接从
CHANGELOG 取，能保证更新日志与 Release 页面永远一致。

用法：python3 scripts/release_body.py 0.1.5 > release-body.md
"""
import io
import os
import re
import sys

CHANGELOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "CHANGELOG.md")


def extract(version: str, text: str) -> str:
    """取 `## v<version>` 到下一个 `## v` 之间的内容。

    版本号按普通字符串匹配（不当作正则），避免 `0.1.5` 里的点被解释成通配符。
    """
    pattern = r"^## v" + re.escape(version) + r"(?:\s|$).*?(?=^## v|\Z)"
    m = re.search(pattern, text, re.S | re.M)
    return m.group(0).strip() if m else ""


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: release_body.py <版本号>", file=sys.stderr)
        return 2
    version = sys.argv[1].lstrip("vV")
    with io.open(CHANGELOG, encoding="utf-8") as f:
        text = f.read()
    body = extract(version, text)
    if not body:
        # 找不到就退回一行版本名：Release 正文为空会让 action 直接报错
        print(f"v{version}")
        print(f"CHANGELOG 里没有 v{version} 的段落，已退回占位正文", file=sys.stderr)
        return 0
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
