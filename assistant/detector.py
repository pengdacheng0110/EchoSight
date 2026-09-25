# -*- coding: utf-8 -*-
"""后台推理线程：主线程投递最新帧，推理线程异步产出检测框，画面不卡顿。"""
import threading

from ultralytics import YOLO

from . import config

# 连续多少次推理失败就认定线程已废。
# 偶发一次失败（坏帧、瞬时显存紧张）不该终止扫描；但一直失败就不能再装作没事。
_MAX_CONSEC_FAIL = 5


class Detector:
    """异步检测器。

    推理线程一旦死掉，`out_boxes` 会永远停在上一次成功的结果上。这不是"没检测到"，
    而是**看起来一切正常的错数据**：扫描循环会拿着这份陈旧框一直播报"椅子在左前方
    约2.0米"，用户走了半间屋子，播报一个字都不变。对盲人用户来说，这比直接报错
    危险得多 —— 因为它看起来是在正常工作的。

    所以这里做两件事：
      1. 推理整体兜异常，连续失败到阈值就置 `fatal_error` 并清空结果，
         保证绝不向外提供陈旧框；
      2. 调用方（DesktopApp）通过 `alive` 判断，发现线程已废就立刻停扫描并出声。
    """

    def __init__(self, model_path=config.MODEL_PATH):
        self.model = YOLO(str(model_path))
        self.in_frame = None
        self.out_boxes = []
        self.evt = threading.Event()
        self.lock = threading.Lock()
        self.fatal_error = None      # 线程彻底失效的原因；None 表示还正常
        self._fail_streak = 0
        self._stopping = False
        self._thread = threading.Thread(target=self._loop, daemon=True,
                                        name="detector")
        self._thread.start()

    @property
    def alive(self):
        """推理线程是否还在正常工作。"""
        return self.fatal_error is None and self._thread.is_alive()

    def submit(self, frame):
        if self._stopping:
            return
        with self.lock:
            self.in_frame = frame
        self.evt.set()

    def latest(self):
        with self.lock:
            return list(self.out_boxes)

    def stop(self):
        """让推理线程退出（进程收尾时调用）。"""
        self._stopping = True
        self.evt.set()

    def _loop(self):
        while not self._stopping:
            self.evt.wait()
            self.evt.clear()
            if self._stopping:
                return
            with self.lock:
                frame = None if self.in_frame is None else self.in_frame.copy()
            if frame is None:
                continue
            try:
                res = self.model(frame, conf=config.CONF,
                                 imgsz=config.IMGSZ, verbose=False)
                boxes = [(float(b.xyxy[0][0]), float(b.xyxy[0][1]),
                          float(b.xyxy[0][2]), float(b.xyxy[0][3]),
                          int(b.cls[0]), float(b.conf[0]))
                         for b in res[0].boxes]
            except Exception as e:
                self._fail_streak += 1
                if self._fail_streak >= _MAX_CONSEC_FAIL:
                    self.fatal_error = f"{type(e).__name__}: {e}"
                    with self.lock:
                        self.out_boxes = []     # 不再向外提供陈旧结果
                    print(f"[检测] 连续 {self._fail_streak} 次推理失败，"
                          f"推理线程停止：{self.fatal_error}")
                    return
                print(f"[检测] 推理失败（第 {self._fail_streak} 次）：{e}")
                continue
            self._fail_streak = 0
            with self.lock:
                self.out_boxes = boxes
