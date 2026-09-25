# -*- coding: utf-8 -*-
"""COCO 80 类：中文名、口语别名、用于测距的真实高度（米）。"""

# ---------------- 英文名 -> 中文名 ----------------
CLASS_CN = {
    "person": "人", "bicycle": "自行车", "car": "汽车", "motorcycle": "摩托车",
    "airplane": "飞机", "bus": "公交车", "train": "火车", "truck": "卡车",
    "boat": "船", "traffic light": "红绿灯", "fire hydrant": "消防栓",
    "stop sign": "停车标志", "parking meter": "停车计时器", "bench": "长凳",
    "bird": "鸟", "cat": "猫", "dog": "狗", "horse": "马", "sheep": "羊",
    "cow": "牛", "elephant": "大象", "bear": "熊", "zebra": "斑马",
    "giraffe": "长颈鹿", "backpack": "背包", "umbrella": "雨伞",
    "handbag": "手提包", "tie": "领带", "suitcase": "行李箱", "frisbee": "飞盘",
    "skis": "滑雪板", "snowboard": "滑雪板", "sports ball": "球", "kite": "风筝",
    "baseball bat": "棒球棒", "baseball glove": "棒球手套",
    "skateboard": "滑板", "surfboard": "冲浪板", "tennis racket": "网球拍",
    "bottle": "瓶子", "wine glass": "酒杯", "cup": "杯子", "fork": "叉子",
    "knife": "刀", "spoon": "勺子", "bowl": "碗", "banana": "香蕉",
    "apple": "苹果", "sandwich": "三明治", "orange": "橙子",
    "broccoli": "西兰花", "carrot": "胡萝卜", "hot dog": "热狗",
    "pizza": "披萨", "donut": "甜甜圈", "cake": "蛋糕", "chair": "椅子",
    "couch": "沙发", "potted plant": "盆栽", "bed": "床",
    "dining table": "餐桌", "toilet": "马桶", "tv": "电视",
    "laptop": "笔记本电脑", "mouse": "鼠标", "remote": "遥控器",
    "keyboard": "键盘", "cell phone": "手机", "microwave": "微波炉",
    "oven": "烤箱", "toaster": "烤面包机", "sink": "水槽",
    "refrigerator": "冰箱", "book": "书", "clock": "时钟", "vase": "花瓶",
    "scissors": "剪刀", "teddy bear": "玩具熊", "hair drier": "吹风机",
    "toothbrush": "牙刷",
}

# ---------------- 中文口语别名 -> 英文名 ----------------
ALIASES = {
    "人": "person", "行人": "person", "大人": "person", "小孩": "person",
    "自行车": "bicycle", "单车": "bicycle",
    "汽车": "car", "小汽车": "car", "轿车": "car", "车子": "car",
    "摩托车": "motorcycle", "电动车": "motorcycle",
    "飞机": "airplane", "公交车": "bus", "巴士": "bus", "公共汽车": "bus",
    "火车": "train", "卡车": "truck", "货车": "truck", "船": "boat",
    "红绿灯": "traffic light", "交通灯": "traffic light",
    "消防栓": "fire hydrant", "停车标志": "stop sign", "长凳": "bench",
    "长椅": "bench", "鸟": "bird", "小鸟": "bird", "猫": "cat", "猫咪": "cat",
    "狗": "dog", "小狗": "dog", "马": "horse", "羊": "sheep",
    "牛": "cow", "大象": "elephant", "熊": "bear", "斑马": "zebra",
    "长颈鹿": "giraffe", "背包": "backpack", "书包": "backpack",
    "雨伞": "umbrella", "伞": "umbrella", "手提包": "handbag",
    "包": "handbag", "领带": "tie", "行李箱": "suitcase", "箱子": "suitcase",
    "飞盘": "frisbee", "球": "sports ball", "风筝": "kite",
    "滑板": "skateboard", "网球拍": "tennis racket",
    "瓶子": "bottle", "水瓶": "bottle", "水杯": "cup", "杯子": "cup",
    "酒杯": "wine glass", "叉子": "fork", "刀": "knife", "勺子": "spoon",
    "碗": "bowl", "香蕉": "banana", "苹果": "apple",
    "三明治": "sandwich", "橙子": "orange", "橘子": "orange",
    "西兰花": "broccoli", "胡萝卜": "carrot", "热狗": "hot dog",
    "披萨": "pizza", "甜甜圈": "donut", "蛋糕": "cake",
    "椅子": "chair", "沙发": "couch", "盆栽": "potted plant",
    "床": "bed", "餐桌": "dining table", "桌子": "dining table",
    "马桶": "toilet", "电视": "tv", "电视机": "tv",
    "笔记本电脑": "laptop", "笔记本": "laptop", "电脑": "laptop",
    "鼠标": "mouse", "遥控器": "remote", "键盘": "keyboard",
    "手机": "cell phone", "电话": "cell phone",
    "微波炉": "microwave", "烤箱": "oven", "水槽": "sink",
    "冰箱": "refrigerator", "书": "book", "书本": "book",
    "时钟": "clock", "钟": "clock", "花瓶": "vase", "剪刀": "scissors",
    "玩具熊": "teddy bear", "小熊": "teddy bear", "泰迪熊": "teddy bear",
    "吹风机": "hair drier", "牙刷": "toothbrush",
}

# ---------------- 各类真实世界高度（米），用于单目测距 ----------------
# 覆盖全部 COCO 80 类。原先只填了 20 类，其余全部兜底 0.3 米，
# 导致椅子(0.9)被当成 0.3、长凳(0.85)被当成 0.3，测距直接差 3 倍。
# 这里按"物体典型竖直尺寸"填写；姿态多变的类别（球、滑板、书…）取最常见摆放姿态。
REAL_HEIGHT = {
    # --- 人与交通 ---
    "person": 1.70, "bicycle": 1.10, "car": 1.50, "motorcycle": 1.20,
    "airplane": 4.00, "bus": 3.00, "train": 3.80, "truck": 3.00,
    "boat": 2.00, "traffic light": 0.90, "fire hydrant": 0.75,
    "stop sign": 0.75, "parking meter": 1.20, "bench": 0.85,
    # --- 动物 ---
    "bird": 0.15, "cat": 0.25, "dog": 0.45, "horse": 1.60,
    "sheep": 0.80, "cow": 1.40, "elephant": 2.80, "bear": 1.30,
    "zebra": 1.40, "giraffe": 4.50,
    # --- 随身物品 ---
    "backpack": 0.50, "umbrella": 0.90, "handbag": 0.30, "tie": 0.45,
    "suitcase": 0.65,
    # --- 运动器材 ---
    "frisbee": 0.27, "skis": 1.70, "snowboard": 1.50, "sports ball": 0.22,
    "kite": 0.80, "baseball bat": 0.85, "baseball glove": 0.30,
    "skateboard": 0.80, "surfboard": 1.80, "tennis racket": 0.70,
    # --- 餐具与食物 ---
    "bottle": 0.25, "wine glass": 0.20, "cup": 0.10, "fork": 0.19,
    "knife": 0.24, "spoon": 0.18, "bowl": 0.08, "banana": 0.20,
    "apple": 0.08, "sandwich": 0.10, "orange": 0.08, "broccoli": 0.15,
    "carrot": 0.18, "hot dog": 0.10, "pizza": 0.30, "donut": 0.10,
    "cake": 0.15,
    # --- 家具家电 ---
    "chair": 0.90, "couch": 0.85, "potted plant": 0.60, "bed": 0.60,
    "dining table": 0.75, "toilet": 0.75, "tv": 0.70, "laptop": 0.25,
    "mouse": 0.04, "remote": 0.18, "keyboard": 0.15, "cell phone": 0.15,
    "microwave": 0.30, "oven": 0.90, "toaster": 0.20, "sink": 0.25,
    "refrigerator": 1.75, "book": 0.20, "clock": 0.30, "vase": 0.30,
    "scissors": 0.20, "teddy bear": 0.40, "hair drier": 0.25,
    "toothbrush": 0.19,
}

# 高度先验"可信"的类别：刚性、竖直放置、且通常直接立在地面上，
# 检测框高度 ≈ 真实高度。融合测距时给这些类别更高权重。
# 其余类别（小物件、可变形、姿态多变）可见高度随视角剧烈变化，权重调低。
TRUSTED_HEIGHT = frozenset({
    "person", "bicycle", "car", "motorcycle", "bus", "train", "truck",
    "boat", "fire hydrant", "stop sign", "parking meter", "bench",
    "horse", "cow", "elephant", "bear", "zebra", "giraffe",
    "chair", "couch", "potted plant", "dining table", "toilet",
    "refrigerator", "oven", "microwave", "sink", "tv",
})


def height_trust(en):
    """该类别的高度先验是否可信（用于融合权重）。"""
    return en in TRUSTED_HEIGHT



def match_target(text):
    """从一句话里按最长别名匹配目标，返回英文名；匹配不到返回 None。"""
    for alias in sorted(ALIASES, key=len, reverse=True):
        if alias in text:
            return ALIASES[alias]
    return None
