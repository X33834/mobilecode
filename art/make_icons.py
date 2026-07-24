#!/usr/bin/env python3
"""把生成的 Mobilecode 图标处理成 Android 各密度 + 自适应前景。"""
import os
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
BASE = HERE / "mobilecode_icon_base.jpg"
RES = HERE.parent / "android" / "app" / "src" / "main" / "res"

img = Image.open(BASE).convert("RGB")

def rounded_icon(size: int, radius_ratio: float = 0.22) -> Image.Image:
    resized = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(resized, (0, 0), mask)
    return out

# 传统 launcher 图标（API < 26 也必须有，否则桌面不显示）
legacy = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in legacy.items():
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    rounded_icon(size).save(os.path.join(d, "ic_launcher.png"))
    print("legacy", folder, size)

# 自适应图标前景：432x432 透明画布，Logo 居中占 60%（避开安全区裁切）
fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
mark = img.resize((260, 260), Image.LANCZOS)
fg.paste(mark, ((432 - 260) // 2, (432 - 260) // 2))
nodpi = os.path.join(RES, "drawable-nodpi")
os.makedirs(nodpi, exist_ok=True)
fg.save(os.path.join(nodpi, "ic_launcher_foreground.png"))
print("foreground 432 done")