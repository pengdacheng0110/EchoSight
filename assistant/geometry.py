# -*- coding: utf-8 -*-
"""单目测距与方位判断：双模型融合 + 时序滤波。

思路参考单目视觉测距方案（YOLO 检测 → 相机标定 → 接地参考点 → 地面平面几何），
在原单一"高度法"基础上改成两条独立模型融合，并补上时序滤波。

模型 A「高度法」——与相机俯仰无关，物体在任何位置（地面/桌面/手中）都适用
    d_A = H_real × fx / h_px
    依赖类别真实高度先验；检测框被遮挡或截断时框高失真，误差随之变大。

模型 B「地面法」——与物体类别无关，高度表缺失的类别同样有效
    φ   = arctan((v_ref − cy) / fy)      接地参考点相对光轴的下偏角
    d_B = h_cam / tan(pitch + φ)         相机离地高 h_cam、俯仰角 pitch
    前提是物体确实接触地面；放在桌面上时该假设不成立。

融合策略：
    两模型比值落在 [1/FUSE_TOL, FUSE_TOL] 内 → 认为地面假设成立，
    按类别高度可信度加权做几何平均，置信度高；
    否则判定地面假设不成立，退回模型 A，置信度低。

参考点：用检测框底边中点（接地/接触点），并按 GROUND_OFFSET 比例上移——
检测框通常比实物略大，向上偏移可抵消这部分偏差。
方位角同样改用参考点的横向位置，而不是框中心：侧方目标时框中心会被
目标自身宽度带偏，参考点才代表它在地面上的实际方位。

时序滤波：同一类别连续帧的距离先过中位数窗口（抗框跳变离群），再过指数平滑，
避免语音播报的数字剧烈跳动；方位角也做轻量平滑，防止在分级阈值附近来回切换。

接口分两层，原因是同一目标可能同时出现多个检测框，直接滤波会把不同物体的
距离混在一起：
    measure()  无状态，对每个框单独算一次；
    smooth()   有状态，只对最终要播报的那个框调用。
"""
import collections
import math
import statistics
import time
from typing import NamedTuple

from . import config
from .labels import REAL_HEIGHT, height_trust


class Measure(NamedTuple):
    """单次测距结果。"""
    dist: float        # 距离（米）
    angle: float       # 方位角（度，负=左，正=右）
    direction: str     # 中文方位：左前方 / 正前方 / 右前方
    confidence: float  # 置信度 0~1，越低越不可信
    source: str        # 距离来源：fused=双模型一致 / height=仅高度法


class _Track:
    """单个目标类别的时序状态：中位数窗口 + 指数平滑。"""

    __slots__ = ("buf", "ema", "angle", "ts")

    def __init__(self):
        self.buf = collections.deque(maxlen=max(config.DIST_MEDIAN_WIN, 1))
        self.ema = None
        self.angle = None
        self.ts = 0.0

    def push_dist(self, value, now):
        self.buf.append(value)
        med = statistics.median(self.buf)
        if self.ema is None:
            self.ema = med
        else:
            a = config.DIST_EMA_ALPHA
            self.ema = a * med + (1.0 - a) * self.ema
        self.ts = now
        return self.ema

    def push_angle(self, value):
        if self.angle is None:
            self.angle = value
        else:
            a = config.ANGLE_EMA_ALPHA
            self.angle = a * value + (1.0 - a) * self.angle
        return self.angle

    def stale(self, now):
        return now - self.ts > config.TRACK_TTL_SEC


class DistanceEstimator:
    """有状态的测距器：内部维护各目标的时序滤波，跨帧复用同一个实例。"""

    def __init__(self, frame_w, frame_h, fx=None, fy=None,
                 cx=None, cy=None, cam_height=None, pitch_deg=None):
        self.frame_w = float(frame_w)
        self.frame_h = float(frame_h)
        # 焦距优先用标定值；未标定时退回 config.FX_FACTOR 的估算
        self.fx = float(fx) if fx else self.frame_w * config.FX_FACTOR
        self.fy = float(fy) if fy else self.fx * config.FY_OVER_FX
        self.cx = float(cx) if cx is not None else self.frame_w * config.CX_OVER_W
        self.cy = float(cy) if cy is not None else self.frame_h * config.CY_OVER_H
        self.cam_height = config.CAM_HEIGHT if cam_height is None else cam_height
        self.pitch = math.radians(
            config.CAM_PITCH_DEG if pitch_deg is None else pitch_deg)
        self._tracks = {}

    # ---------------- 状态管理 ----------------
    def reset(self, target_en=None):
        """清空滤波状态。切换目标时传入类别名只清该类别，传 None 清全部。"""
        if target_en is None:
            self._tracks.clear()
        else:
            self._tracks.pop(target_en, None)

    # ---------------- 两条测距模型 ----------------
    def _height_model(self, box, target_en):
        """高度法：d = H_real × fx / 框高。"""
        h_px = max(box[3] - box[1], 1.0)
        real_h = REAL_HEIGHT.get(target_en, config.DEFAULT_HEIGHT)
        return real_h * self.fx / h_px

    def _ground_model(self, v_ref):
        """地面法：d = h_cam / tan(俯仰角 + 参考点下偏角)。

        参考点落在画面水平线以上（总俯角 ≤ 0）时与地面无交点，返回 None。
        """
        phi = math.atan2(v_ref - self.cy, self.fy)
        depression = self.pitch + phi
        if depression <= math.radians(2.0):
            return None
        return self.cam_height / math.tan(depression)

    @staticmethod
    def _fuse(d_a, d_b, trusted):
        """融合两模型，返回 (距离, 置信度, 来源)。"""
        if d_b is None or d_b <= 0:
            return d_a, 0.5, "height"
        ratio = d_a / d_b
        if 1.0 / config.FUSE_TOL <= ratio <= config.FUSE_TOL:
            w = config.W_TRUSTED if trusted else config.W_UNTRUSTED
            d = math.exp(w * math.log(d_a) + (1.0 - w) * math.log(d_b))
            return d, 0.9, "fused"
        # 两模型差异过大：物体多半不在假设的地面上（例如杯子在桌上），
        # 此时地面法不可用，退回高度法并降低置信度。
        return d_a, 0.35, "height"

    @staticmethod
    def _direction(angle):
        if angle < config.DIR_LEFT_DEG:
            return "左前方"
        if angle > config.DIR_RIGHT_DEG:
            return "右前方"
        return "正前方"

    # ---------------- 对外接口 ----------------
    def measure(self, box, target_en):
        """无状态测量：只按几何算一次，不碰滤波。多目标场景对每个框各调一次。"""
        x1, y1, x2, y2 = box
        # 接地参考点：底边中点按比例上移，抵消"框比实物略大"
        h_px = max(y2 - y1, 1.0)
        v_ref = y2 - config.GROUND_OFFSET * h_px
        u_ref = (x1 + x2) / 2.0

        dist, conf, source = self._fuse(
            self._height_model(box, target_en),
            self._ground_model(v_ref),
            height_trust(target_en),
        )
        dist = min(max(dist, config.DIST_MIN), config.DIST_MAX)
        angle = math.degrees(math.atan2(u_ref - self.cx, self.fx))
        return Measure(dist, angle, self._direction(angle), conf, source)

    def smooth(self, target_en, m, now=None):
        """有状态平滑：只对最终要播报的那一个目标调用，返回平滑后的 Measure。"""
        now = time.time() if now is None else now
        track = self._tracks.get(target_en)
        if track is None:
            track = _Track()
            self._tracks[target_en] = track
        elif track.stale(now):
            # 目标丢了很久，旧距离不再有参考价值
            track.buf.clear()
            track.ema = None
            track.angle = None

        dist = track.push_dist(m.dist, now)
        angle = track.push_angle(m.angle)
        return Measure(dist, angle, self._direction(angle),
                       m.confidence, m.source)

    def estimate(self, box, target_en, now=None):
        """measure + smooth 的便捷组合，适用于画面里只有一个目标的情况。"""
        return self.smooth(target_en, self.measure(box, target_en), now)
