# -*- coding: utf-8 -*-
"""全局配置：SenseAudio 接口、检测参数、录音与路径。

API Key 优先读环境变量 SENSEAUDIO_API_KEY，没有时用内置的备用 Key。
"""
import os
from pathlib import Path

# ---------------- 路径 ----------------
ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"
ASSETS_DIR = ROOT / "assets"
TMP_WAV = ROOT / "_tts_tmp.wav"

# ---------------- SenseAudio 云端接口 ----------------
BASE = "https://api.senseaudio.cn"


def _load_api_key():
    """Key 来源优先级：
    1. 环境变量 SENSEAUDIO_API_KEY
    2. 根目录 .api_key 明文文件（已 gitignore，不入仓库）
    """
    key = os.environ.get("SENSEAUDIO_API_KEY")
    if key:
        return key.strip()
    key_file = ROOT / ".api_key"
    if key_file.exists():
        return key_file.read_text(encoding="utf-8").strip()
    raise SystemExit(
        "未找到 API Key：请设置环境变量 SENSEAUDIO_API_KEY，"
        "或在项目根目录创建 .api_key 文件（写入 sk- 开头的 Key）。")


API_KEY = _load_api_key()
HEADERS = {"Authorization": f"Bearer {API_KEY}"}
TTS_MODEL = "sensenova-tts-2.0"
ASR_MODEL = "senseaudio-asr-1.5-260319"
VOICE_ID = "female_0033_b"

# ---------------- 目标检测 ----------------
MODEL_PATH = MODELS_DIR / "yolo26m.pt"   # m：精度/速度平衡
IMGSZ = 416                   # 推理分辨率（越小越快）
CONF = 0.3
FX_FACTOR = 0.85              # 兜底焦距系数：fx = 画面宽 × 该值
DEFAULT_HEIGHT = 0.3
IMAGE_W_FALLBACK = 640        # 标定脚本换算 FX_FACTOR 时用的参考画面宽

# ---------------- 相机内参（标定值优先，见 scripts/calibrate_camera.py）----------------
# FX_PX 标定后填入像素值，优先级高于 FX_FACTOR；两者都为 None 时才用兜底系数。
FX_PX = None                  # 横向焦距（像素）
FY_OVER_FX = 1.0              # fy / fx，手机与网络摄像头像元为正方形，取 1.0
CX_OVER_W = 0.5               # 主点横坐标 / 画面宽
CY_OVER_H = 0.5               # 主点纵坐标 / 画面高

# ---------------- 测距：地面平面几何模型 ----------------
CAM_HEIGHT = 1.25             # 相机镜头离地高度（米）；手持约 1.2~1.4，放桌面约 0.5
CAM_PITCH_DEG = 0.0           # 俯仰角：正值=镜头朝下俯拍，负值=朝上仰拍

# ---------------- 测距：参考点与融合 ----------------
GROUND_OFFSET = 0.06          # 接地参考点自框底上移的比例（框比实物略大，做像素偏移修正）
FUSE_TOL = 1.6                # 双模型偏差容忍度：比值超出 [1/tol, tol] 判定地面假设不成立
W_TRUSTED = 0.65              # 类别高度可信时，高度法在融合中的权重
W_UNTRUSTED = 0.45            # 类别高度不可信时，高度法权重（更偏向与类别无关的地面法）
DIST_MIN = 0.15               # 距离下限（米），小于此值视为无效
DIST_MAX = 20.0               # 距离上限（米）

# ---------------- 测距：时序滤波 ----------------
DIST_MEDIAN_WIN = 5           # 中位数滤波窗口（帧），抗检测框跳变离群
DIST_EMA_ALPHA = 0.35         # 指数平滑系数，越小越稳但越滞后
TRACK_TTL_SEC = 1.5           # 目标连续丢失超过该时长则清空其滤波状态
ANGLE_EMA_ALPHA = 0.4         # 方位角平滑系数，避免播报方位在阈值附近来回跳

# ---------------- 方位分级阈值（度，以画面中心为 0，负=左）----------------
DIR_LEFT_DEG = -12            # 小于该角度判为左前方
DIR_RIGHT_DEG = 12            # 大于该角度判为右前方


# ---------------- 录音 / 播报节奏 ----------------
RECORD_SEC = 3
SAMPLE_RATE = 16000
LOSE_PROMPT_INTERVAL = 8      # 找不到目标时两次提示的最小间隔（秒）
FOUND_REPORT_INTERVAL = 6     # 找到目标时两次播报的最小间隔（秒）

# ---------------- 语音指令监听（本地能量检测 VAD） ----------------
VAD_CALIBRATE_SEC = 1.0       # 启动时采集环境噪声的时长
VAD_BLOCK_SEC = 0.03          # 每块音频长度（秒）
VAD_NOISE_FACTOR = 5          # 触发阈值 = 环境噪声 RMS × 倍数
VAD_MIN_RMS = 300             # 触发阈值下限（int16，0~32767）
VAD_START_BLOCKS = 2          # 连续多少块超阈值判定为开始说话
VAD_END_BLOCKS = 18           # 连续多少块低于阈值判定为说完（约0.5秒）
VAD_MIN_SEC = 0.3             # 语音段最短时长，短于此时长视为噪声
VAD_MAX_SEC = 6.0             # 语音段最长时长，到点强制截断识别
VAD_TAIL_SEC = 0.4            # 播报结束后再多屏蔽这么久，防回声尾音
