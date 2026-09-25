# -*- coding: utf-8 -*-
"""测距算法自检：双模型精度、融合判定、时序滤波、方位与边界。

直接运行，全部通过则打印 ALL PASS：
    py scripts/test_geometry.py
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from assistant import config                                    # noqa: E402
from assistant.geometry import DistanceEstimator                # noqa: E402
from assistant.labels import (CLASS_CN, REAL_HEIGHT, TRUSTED_HEIGHT,  # noqa: E402
                              match_target)

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + (f"  {detail}" if detail else ""))


def approx(a, b, rel=0.01):
    return abs(a - b) <= rel * max(abs(b), 1e-9)


# 画面 640×480，焦距标定值 fx=500，主点取画面中心
W, H, FX = 640, 480, 500.0
CX, CY = W / 2, H / 2

# 俯仰 -90° 可让地面法失效（参考点总在水平线之下时与地面无交点），
# 用来单独验证高度法。
NO_GROUND = dict(pitch_deg=-90)

print("\n[1] 高度法精度（关闭地面法，纯几何反算）")
est = DistanceEstimator(W, H, fx=FX, **NO_GROUND)
for en, real_d in (("person", 1.0), ("person", 2.0), ("person", 5.0),
                   ("chair", 3.0), ("car", 8.0)):
    h_px = REAL_HEIGHT[en] * FX / real_d
    y2 = 400.0
    m = est.measure((300.0, y2 - h_px, 340.0, y2), en)
    check(f"{en} @ {real_d}m", approx(m.dist, real_d, 0.01),
          f"得到 {m.dist:.3f}m")
check("地面法失效时来源为 height", m.source == "height", m.source)

print("\n[2] 地面法精度（物体类别无关）")
est_g = DistanceEstimator(W, H, fx=FX, fy=FX, pitch_deg=0.0, cam_height=1.25)
# 目标 2.5m：总俯角 = atan(1.25/2.5) = 26.565°，对应参考点 v_ref = cy + fy*tan
want = 2.5
v_ref = CY + FX * math.tan(math.atan(1.25 / want))
h_px = REAL_HEIGHT["chair"] * FX / want          # 让高度法也算出同一个值
y2 = v_ref + config.GROUND_OFFSET * h_px
m = est_g.measure((300.0, y2 - h_px, 340.0, y2), "chair")
check("双模型一致 → fused", m.source == "fused", m.source)
check("融合距离 ≈ 2.5m", approx(m.dist, want, 0.02), f"得到 {m.dist:.3f}m")
check("一致时置信度高", m.confidence >= 0.8, f"{m.confidence}")

print("\n[3] 融合判定：两模型分歧大时退回高度法")
# 框高翻倍 → 高度法算出 1.25m，地面法仍是 2.5m，比值 0.5 超出容差
m = est_g.measure((300.0, y2 - h_px * 2, 340.0, y2), "chair")
check("分歧大 → 来源 height", m.source == "height", m.source)
check("分歧大 → 置信度低", m.confidence < 0.5, f"{m.confidence}")

print("\n[4] 时序滤波：中位数窗口抗单帧离群")
est_f = DistanceEstimator(W, H, fx=FX, **NO_GROUND)
h_stable = REAL_HEIGHT["person"] * FX / 3.0
y2 = 400.0
stable_box = (300.0, y2 - h_stable, 340.0, y2)
t = 1000.0
for i in range(10):
    s = est_f.smooth("person", est_f.measure(stable_box, "person"), t + i * 0.1)
base = s.dist
outlier = (300.0, y2 - 40.0, 340.0, y2)          # 框突然变很矮 → 距离暴增
s_out = est_f.smooth("person", est_f.measure(outlier, "person"), t + 1.1)
check("单帧离群几乎不影响输出", abs(s_out.dist - base) / base < 0.02,
      f"{base:.3f} → {s_out.dist:.3f}")

print("\n[5] 时序滤波：阶跃后收敛到新值")
est_s = DistanceEstimator(W, H, fx=FX, **NO_GROUND)
for i in range(10):
    est_s.smooth("person", est_s.measure(stable_box, "person"), t + i * 0.1)
h_new = REAL_HEIGHT["person"] * FX / 1.5
new_box = (300.0, y2 - h_new, 340.0, y2)
for i in range(20):
    s_new = est_s.smooth("person", est_s.measure(new_box, "person"),
                         t + 1.0 + i * 0.1)
check("20 帧内收敛到 1.5m", approx(s_new.dist, 1.5, 0.05),
      f"得到 {s_new.dist:.3f}m")

print("\n[6] 滤波状态过期后重新起算")
est_t = DistanceEstimator(W, H, fx=FX, **NO_GROUND)
for i in range(10):
    est_t.smooth("person", est_t.measure(stable_box, "person"), t + i * 0.1)
h_new2 = REAL_HEIGHT["person"] * FX / 8.0
far_box = (300.0, y2 - h_new2, 340.0, y2)
# 间隔超过 TRACK_TTL_SEC，旧窗口应被清空 → 直接反映新值
s_far = est_t.smooth("person", est_t.measure(far_box, "person"),
                     t + 10.0)
check("过期后立即采用新值", approx(s_far.dist, 8.0, 0.05),
      f"得到 {s_far.dist:.3f}m")

print("\n[7] 方位角与分级（用接地参考点，不是框中心）")
est_d = DistanceEstimator(W, H, fx=FX, **NO_GROUND)
mid = est_d.measure((300.0, 300.0, 340.0, 400.0), "person")
left = est_d.measure((40.0, 300.0, 80.0, 400.0), "person")
right = est_d.measure((560.0, 300.0, 600.0, 400.0), "person")
check("居中 → 正前方", mid.direction == "正前方", mid.direction)
check("左侧 → 左前方", left.direction == "左前方",
      f"{left.direction} {left.angle:.1f}°")
check("右侧 → 右前方", right.direction == "右前方",
      f"{right.direction} {right.angle:.1f}°")
check("角度符号正确（左负右正）", left.angle < 0 < right.angle)

print("\n[8] 边界情况")
tiny = DistanceEstimator(W, H, fx=FX, **NO_GROUND).measure(
    (300.0, 399.0, 340.0, 400.0), "person")
check("极小框 → 钳到 DIST_MAX", tiny.dist <= config.DIST_MAX, f"{tiny.dist}")
# 小物件（鼠标 0.04m）几乎占满画面 → 高度法算出 0.04m，应被钳到下限
huge = DistanceEstimator(W, H, fx=FX, **NO_GROUND).measure(
    (0.0, 0.0, 640.0, 479.0), "mouse")
check("小物件大框 → 钳到 DIST_MIN", huge.dist == config.DIST_MIN, f"{huge.dist}")
flat = DistanceEstimator(W, H, fx=FX, **NO_GROUND).measure(
    (300.0, 400.0, 340.0, 400.0), "person")
check("零高度框不崩溃", flat.dist > 0, f"{flat.dist}")
unknown = DistanceEstimator(W, H, fx=FX, **NO_GROUND).measure(
    (300.0, 300.0, 340.0, 400.0), "no_such_class")
check("未知类别走默认高度", unknown.dist > 0, f"{unknown.dist}")

print("\n[9] 高度表完整性")
missing = set(CLASS_CN) - set(REAL_HEIGHT)
extra = set(REAL_HEIGHT) - set(CLASS_CN)
check("COCO 80 类齐全", len(CLASS_CN) == 80, f"{len(CLASS_CN)} 类")
check("高度表无缺漏", not missing, f"缺 {sorted(missing)}")
check("高度表无多余键", not extra, f"多 {sorted(extra)}")
check("可信集合是高度表子集", TRUSTED_HEIGHT <= set(REAL_HEIGHT))
check("可信类别非空且不过半", 0 < len(TRUSTED_HEIGHT) < 40,
      f"{len(TRUSTED_HEIGHT)} 类")
bad = [k for k, v in REAL_HEIGHT.items() if not (0.01 <= v <= 6.0)]
check("高度取值都在合理区间", not bad, f"异常 {bad}")

print("\n[10] 目标名匹配：每个类别都要叫得出来，且不能叫错")
# 目标物品只能靠语音指定，所以"能不能匹配到"和"会不会匹配错"都是硬指标。
# 错配（说 A 返回 B）比匹配不到更危险 —— 应用会自信地去找另一个东西。
# 实测过的真实缺陷：说"棒球棒"返回 sports ball（因为别名里有"球"），
# 说"烤面包机"返回 handbag（因为别名里有"包"）。
AMBIGUOUS = {"skis", "snowboard"}      # 中文名同为"滑雪板"，属固有歧义
unreachable, mismatched = [], []
for en, cn in CLASS_CN.items():
    got = match_target(f"帮我找{cn}")
    if got is None:
        unreachable.append(cn)
    elif got != en and en not in AMBIGUOUS:
        mismatched.append(f"{cn}→{got}")
check("每个类别都能被中文名匹配到", not unreachable, f"叫不出来 {unreachable}")
check("没有把 A 匹配成 B", not mismatched, f"错配 {mismatched}")
check("歧义的滑雪板可用限定词区分",
      match_target("双板滑雪板") == "skis"
      and match_target("单板滑雪板") == "snowboard")
check("无关话语不会误触发",
      match_target("今天天气不错") is None and match_target("") is None)

print("\n" + "=" * 56)
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项：" + "，".join(FAIL))
    sys.exit(1)
print("ALL PASS")
