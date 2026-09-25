# EchoSight

盲人识物助手：语音说出要找的物品，摄像头识别并语音播报距离和方位。

## 运行

```powershell
py blind_assistant.py        # 或双击 run.bat
```

API Key 读取顺序：环境变量 `SENSEAUDIO_API_KEY` → 根目录 `.api_key` 文件（写入 `sk-` 开头的 Key，已 gitignore）。

## 目录说明

| 路径 | 作用 |
|---|---|
| `blind_assistant.py` | 程序入口 |
| `assistant/` | 主程序模块 |
| `assistant/config.py` | 配置参数、路径，读取 API Key |
| `assistant/labels.py` | 80 类物品的中文名、别名、真实高度 |
| `assistant/audio_api.py` | 云端 TTS / ASR 接口 |
| `assistant/speaker.py` | 异步语音播报 |
| `assistant/voice_command.py` | 语音口令监听（"找到了"、"找XX"） |
| `assistant/detector.py` | YOLO 后台推理线程 |
| `assistant/geometry.py` | 距离与方位计算 |
| `assistant/display.py` | 画面中文标注绘制 |
| `assistant/desktop.py` | 桌面版主流程 |
| `scripts/` | 测试和基准脚本（bench、接口测试、相机标定、测距自检） |
| `models/` | YOLO 权重文件（需自行下载，不入仓库） |
| `assets/` | 示例图片 |
| `docs/` | 语音接口文档 |
| `android/` | 手机 App 工程（Kotlin） |

## 下载权重

在项目根目录运行，ultralytics 会自动下载缺失的权重到 `models/`：

```powershell
py -c "from ultralytics import YOLO; [YOLO(f'models/{n}.pt') for n in ('yolo26n','yolo26s','yolo26m','yolo26x','yolov8n','yolov8s','yolov8x')]"
```

## 手机 App（Android）

手机本地运行 YOLO 模型，无需电脑；语音识别/合成仍走 SenseAudio 云端。

**使用方式：**

1. **按住**屏幕下方大按钮说话（如"找杯子"、"换手机"），**松手**自动识别并切换目标
2. 摄像头持续扫描，语音播报：方位（正前方/左前方/右前方/左侧/右侧）、高低（头部以上/视线高度/腰部以下）、距离（米 + 步数）、行动指引（往哪转、往前走几步）
3. 说"找到了"进入安静待命；再次按住说话即可换新目标

**下载 APK：**

每次推送到 `main`，GitHub Actions 自动编译。到
[Actions 页面](https://github.com/pengdacheng0110/EchoSight/actions)
点开最新一次构建，在底部 Artifacts 下载 `EchoSight-apk`，解压得到 `EchoSight-<短SHA>.apk`，传到手机安装（需开启"允许安装未知来源应用"）。

> 注意：编译前需在仓库 Settings → Secrets and variables → Actions 添加
> Secret `SENSEAUDIO_API_KEY`（值为 `sk-` 开头的 Key），否则工作流会直接报错停下——
> 这是有意为之：缺 Key 打出来的 APK 能装但语音完全不可用，不如早点说清楚。
> 本地构建可在 `android/app/api_key.txt` 放入 Key（已 gitignore）。

打 `v*` 标签时（如 `git tag v1.0 && git push --tags`）会自动发 Release 并附上 APK。

### 界面说明

界面是给**低视力用户和陪行的人**看的辅助通道，语音仍是主通道。

| 区域 | 内容 |
|---|---|
| 顶部状态卡片 | 左侧状态点 + 图标按状态变色（灰=待命、黄=搜索中、绿=已找到、蓝=安静待机、红=聆听/出错）；主标题是当前状态，副标题是目标与测距来源（`双模型融合` / `仅高度法`） |
| 方位条 | 卡片下方一条刻度带，游标位置 = 目标相对正前方的水平角；±10° / ±35° 刻度与语音里"正前方 / 左前方 / 你的左侧"的分级阈值对齐，超出 ±60° 贴边并加小三角 |
| 检测框 | 最近目标（语音播报的那个）用亮绿粗框 + 绿底黑字标签，其余同类目标用半透明细框；标签是圆角胶囊，会自动避让屏幕边缘；框下垫半透明深色描边，浅色画面上也看得清 |
| 中心准星 | 画面正中的淡十字，标出"正前方"的参照 |
| 按住说话 | 圆形大按钮，录音时变红、文案切成"松开识别"，并有振动反馈 |

实现上几个点：文字尺寸走 sp（原先硬编码 38px，在 1080p 屏上只相当于 12sp）；
状态卡片副标题有 1.5~2.5 秒的**占位时间**，否则"正在聆听 / 正在识别"会被
约 30fps 的检测帧立刻冲掉；调色板集中在 `res/values/colors.xml`，
两个自定义 View 通过 `colorOf(R.color.*)` 取用，不在代码里另抄一份色值。

**重新导出端侧模型（更换模型时）：**

```powershell
py -c "from ultralytics import YOLO; m=YOLO('models/yolo26n.pt'); m.export(format='onnx', imgsz=320, simplify=True)"
copy models\yolo26n.onnx android\app\src\main\assets\
```

## 测距算法

距离由两条独立模型融合得到（Python 见 `assistant/geometry.py`，Android 见
`Guidance.kt` + `DistanceTracker.kt`，两边参数与公式一致）：

| 模型 | 公式 | 特点 |
|---|---|---|
| A 高度法 | `d = 物体真实高度 × fx ÷ 检测框高` | 与相机俯仰无关，地面/桌面/手中都适用；依赖类别高度表 |
| B 地面法 | `d = 相机离地高 ÷ tan(俯仰角 + 参考点下偏角)` | 与物体类别无关，高度表缺失的类别也有效；要求物体确实接触地面 |

两者算出的距离比值落在容差内 → 认为地面假设成立，按类别高度可信度加权做几何平均；
差异过大 → 判定物体不在假设的地面上（例如杯子在桌上），退回模型 A 并降低置信度。

参考点取**检测框底边中点**（接地/接触点）并向上偏移，而不是框中心——框通常比实物
略大，且侧方目标用框中心会被目标自身宽度带偏。方位角也用参考点算。

距离最后过**中位数窗口 + 指数平滑**，避免语音播报的数字在帧间剧烈跳动。

### 先标定相机焦距

默认的 `FX_FACTOR = 0.85` 是经验值，和真实值差 10% 以上很常见，测距会整体偏。
用已知尺寸的物体在已知距离上反解 fx：

```powershell
# 自动检测：把一个人放在量好的 3 米处，按空格采样、按 Q 结束
py scripts/calibrate_camera.py --class person --distance 3.0 --write

# 或者自己量出框的像素高度，纯离线手算
py scripts/calibrate_camera.py --class person --distance 3.0 --height-px 283 --write
```

`--write` 会把结果写回 `assistant/config.py` 的 `FX_FACTOR`。

### 自检

```powershell
py scripts/test_geometry.py
```

覆盖双模型精度、融合判定、时序滤波抗离群与收敛、方位分级、边界钳制、高度表完整性。

### 可调参数（`assistant/config.py`）

| 参数 | 含义 |
|---|---|
| `CAM_HEIGHT` | 相机镜头离地高度（米），手持约 1.2~1.4，放桌面约 0.5 |
| `CAM_PITCH_DEG` | 相机俯仰角，正值=镜头朝下俯拍 |
| `DIST_EMA_ALPHA` | 距离平滑系数，越小越稳但越滞后 |
| `DIST_MEDIAN_WIN` | 中位数窗口帧数，抗检测框跳变 |
| `GROUND_OFFSET` | 接地参考点自框底上移的比例 |

### 俯仰角：桌面端固定，手机端实时

`CAM_PITCH_DEG` 是桌面端的固定值。手机端不用它——`CameraTilt.kt` 会订阅
`TYPE_GRAVITY` 重力传感器，由 `TiltMath` 实时算出相机光轴相对水平面的俯角：

```
俯角 = acos(光轴 · 上方向) − 90°      光轴 = (0, 0, −1)  →  俯角 = acos(−u.z) − 90°
```

手持时俯仰角一直在变，接上传感器后地面法才能跟着手机姿态走；没有重力传感器的设备
（模拟器、部分机型）自动回退到 `Guidance.CAM_PITCH_DEG`。

推导与自检见 `TiltMath.kt` 的注释。

