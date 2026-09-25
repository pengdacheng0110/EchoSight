# -*- coding: utf-8 -*-
"""画面绘制：检测框 + 中文标签（类名/置信度/距离/方位）+ 顶部状态栏。"""
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

LABEL_PX = 26          # 标签字号
STATUS_BAR_H = 44      # 顶部状态栏高度
LABEL_PAD = 6          # 标签底框左右内边距

# 中文字体候选：按优先级找第一个存在的。
# 原先直接 ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 26) 写在模块顶层，
# 字体缺失时 import 就抛 OSError，整个应用起不来，且报错信息完全看不出
# 是字体的问题。现在逐个试，全都不行就退回 Pillow 自带位图字体（画不了中文，
# 但至少能跑起来，并明确打印提示）。
_FONT_CANDIDATES = (
    "C:/Windows/Fonts/msyh.ttc",       # 微软雅黑
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/simhei.ttf",     # 黑体
    "C:/Windows/Fonts/simsun.ttc",     # 宋体
)


def _load_font(size):
    for path in _FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    print(f"[警告] 未找到中文字体（试过 {len(_FONT_CANDIDATES)} 个路径），"
          "标签将无法显示中文，请安装微软雅黑或黑体。")
    return ImageFont.load_default()


font_cn = _load_font(LABEL_PX)


def draw_frame(frame, detections, status):
    img = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img)
    w, h = img.size

    for x1, y1, x2, y2, cn, conf, dist, direction in detections:
        draw.rectangle([x1, y1, x2, y2], outline=(0, 255, 0), width=3)

        tag = f"{cn} {conf:.2f} {dist:.1f}米{direction}"
        # 底框宽度按文字实测宽度算，不再写死 350px。
        # "笔记本电脑 0.95 12.3米左前方" 这类长标签实测约 390px，
        # 用固定宽度会让文字直接压到绿底之外、甚至跑出画面。
        box_w = int(draw.textlength(tag, font=font_cn)) + LABEL_PAD * 2
        box_h = LABEL_PX + 6

        # 默认贴在框上方；顶到状态栏就翻到框内顶部，别被状态栏盖住
        top = y1 - box_h
        if top < STATUS_BAR_H:
            top = y1
        top = min(max(top, STATUS_BAR_H), max(STATUS_BAR_H, h - box_h))
        # 水平方向钳制在画面内，右侧目标的标签不会跑出屏幕
        left = min(max(x1, 0), max(0, w - box_w))

        draw.rectangle([left, top, left + box_w, top + box_h], fill=(0, 180, 0))
        # anchor="lm" 让文字在底框里垂直居中，不用手算基线
        draw.text((left + LABEL_PAD, top + box_h / 2), tag,
                  font=font_cn, fill=(255, 255, 255), anchor="lm")

    draw.rectangle([0, 0, w, STATUS_BAR_H], fill=(0, 0, 0))
    draw.text((10, 6), status, font=font_cn, fill=(0, 255, 255))
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
