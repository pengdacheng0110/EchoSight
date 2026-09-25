# -*- coding: utf-8 -*-
"""相机焦距标定：用"已知真实尺寸的物体 + 已知距离"反解 fx。

原理就是把高度法公式反解：

    高度法   d = H_real × fx / h_px
    反解     fx = d × h_px / H_real

在画面里量出该物体检测框的像素高度 h_px，代入实测距离 d 和真实高度 H_real，
就得到这台相机的 fx，用来替换 config.FX_FACTOR 那个经验值 0.85。

两种用法：

  1) 离线手算（不需要摄像头和模型）——自己量出框的像素高度：
       py scripts/calibrate_camera.py --height-px 283 --real-height 1.70 --distance 3.0

  2) 摄像头实测（自动取检测框，多次采样取中位数）：
       py scripts/calibrate_camera.py --class person --distance 3.0
     把目标放在量好的距离上，按空格采样，按 Q 结束并输出结果。

加 --write 可以把结果直接写回 assistant/config.py。

建议：物体选 person / chair / car 这类高度可信的刚性物体，距离取 2~5 米，
量距离时从相机镜头算起。多采几帧取中位数，能消掉检测框抖动带来的误差。
"""
import argparse
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from assistant import config                                    # noqa: E402
from assistant.labels import CLASS_CN, REAL_HEIGHT, height_trust  # noqa: E402


def compute_fx(distance, h_px, real_h):
    """由 实测距离 / 框像素高 / 物体真实高 反解焦距（像素）。"""
    if h_px <= 0:
        raise ValueError("框的像素高度必须大于 0")
    if real_h <= 0:
        raise ValueError("物体真实高度必须大于 0")
    return distance * h_px / real_h


def report(samples, frame_w):
    """samples: [(fx, h_px, frame_w)]，输出中位数结果。"""
    fx = statistics.median(s[0] for s in samples)
    w = frame_w or samples[0][2]
    print("\n" + "=" * 56)
    print(f"采样 {len(samples)} 次，各次 fx："
          + "，".join(f"{s[0]:.1f}" for s in samples))
    if len(samples) > 1:
        spread = (max(s[0] for s in samples) - min(s[0] for s in samples))
        print(f"离散度 {spread:.1f} px（越小越可靠；超过 fx 的 10% 建议重采）")
    print("-" * 56)
    print(f"标定焦距 fx        = {fx:.1f} px")
    print(f"画面宽             = {w:.0f} px")
    print(f"换算 FX_FACTOR     = {fx / w:.4f}   （当前配置值 "
          f"{config.FX_FACTOR}）")
    print(f"竖直角 fy          ≈ {fx:.1f} px（像素为正方形时 fy = fx）")
    print("=" * 56)
    return fx, fx / w


def offline(args):
    real_h = args.real_height
    if args.klass:
        real_h = REAL_HEIGHT.get(args.klass)
        if real_h is None:
            sys.exit(f"未知类别 {args.klass}，可选示例：person / chair / car")
    if real_h is None:
        sys.exit("请用 --real-height 指定物体真实高度，或用 --class 指定类别")

    fx = compute_fx(args.distance, args.height_px, real_h)
    frame_w = args.frame_w or config.IMAGE_W_FALLBACK
    print(f"物体        {args.klass or '(自定义)'}  真实高度 {real_h} m")
    print(f"实测距离    {args.distance} m")
    print(f"框像素高    {args.height_px} px")
    print(f"→ 反解 fx = {args.distance} × {args.height_px} / {real_h} = {fx:.1f} px")
    return report([(fx, args.height_px, frame_w)], frame_w)


def camera(args):
    import cv2                                        # 延迟导入：离线模式无需
    from assistant.detector import Detector

    if args.klass:
        en = args.klass
        real_h = REAL_HEIGHT[en]
        if not height_trust(en):
            print(f"提示：{CLASS_CN[en]} 的高度先验可信度较低，"
                  f"标定结果可能偏差较大，建议换 person / chair 这类物体。")
    else:
        sys.exit("摄像头模式需要用 --class 指定目标类别，例如 --class person")

    print("加载模型...")
    det = Detector()
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        sys.exit("无法打开摄像头")
    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    print(f"画面 {frame_w}×{int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))}，"
          f"把 {CLASS_CN[en]} 放在 {args.distance} 米处，"
          f"按空格采样，按 Q 结束。")

    cid = next(k for k, v in det.model.names.items() if v == en)
    samples = []
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        det.submit(frame)
        boxes = [b for b in det.latest() if b[4] == cid]
        shown = frame.copy()
        for x1, y1, x2, y2, _, conf in boxes:
            cv2.rectangle(shown, (int(x1), int(y1)), (int(x2), int(y2)),
                          (0, 255, 0), 2)
            cv2.putText(shown, f"{conf:.2f} h={y2 - y1:.0f}px",
                        (int(x1), int(y1) - 6), cv2.FONT_HERSHEY_SIMPLEX,
                        0.6, (0, 255, 0), 2)
        cv2.putText(shown, f"samples={len(samples)}  SPACE=sample  Q=done",
                    (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
        cv2.imshow("camera calibration", shown)

        key = cv2.waitKey(1) & 0xFF
        if key == ord("q"):
            break
        if key == ord(" ") and boxes:
            # 取面积最大的那个框，避免把背景里的小目标算进来
            b = max(boxes, key=lambda b: (b[3] - b[1]) * (b[2] - b[0]))
            h_px = b[3] - b[1]
            fx = compute_fx(args.distance, h_px, real_h)
            samples.append((fx, h_px, frame_w))
            print(f"  采样 {len(samples)}: h_px={h_px:.0f} → fx={fx:.1f}")

    cap.release()
    cv2.destroyAllWindows()

    if not samples:
        sys.exit("没有采到任何样本，标定中止。")
    return report(samples, frame_w)


def write_config(factor, fx):
    """把标定结果写回 config.py。只替换 FX_FACTOR 一行，其余内容不动。"""
    path = ROOT / "assistant" / "config.py"
    src = path.read_text(encoding="utf-8")
    new, n = re.subn(r"^FX_FACTOR = .*$",
                     f"FX_FACTOR = {factor:.4f}",
                     src, count=1, flags=re.M)
    if n != 1:
        sys.exit("没找到 FX_FACTOR 那一行，未修改文件。")
    path.write_text(new, encoding="utf-8")
    print(f"已写回 {path}")
    print(f"  FX_FACTOR = {factor:.4f}   (fx = {fx:.1f} px @ 画面宽 "
          f"{config.IMAGE_W_FALLBACK})")


def main():
    ap = argparse.ArgumentParser(description="相机焦距标定")
    ap.add_argument("--class", dest="klass",
                    help="目标类别英文名，如 person / chair / car")
    ap.add_argument("--distance", type=float,
                    help="相机到物体的实测距离（米）")
    ap.add_argument("--height-px", type=float,
                    help="离线模式：检测框的像素高度")
    ap.add_argument("--real-height", type=float,
                    help="离线模式：物体真实高度（米），与 --class 二选一")
    ap.add_argument("--frame-w", type=float,
                    help="离线模式：画面宽度（像素），默认 640")
    ap.add_argument("--write", action="store_true",
                    help="把标定结果写回 assistant/config.py")
    args = ap.parse_args()

    if args.distance is None:
        sys.exit("必须用 --distance 指定实测距离（米）")

    if args.height_px is not None:
        fx, factor = offline(args)
    else:
        fx, factor = camera(args)

    if args.write:
        write_config(factor, fx)


if __name__ == "__main__":
    main()
