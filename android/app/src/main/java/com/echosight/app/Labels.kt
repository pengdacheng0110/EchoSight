package com.echosight.app

/**
 * 语音里说出的目标：**哪个模型的第几类**。
 *
 * 为什么不能只用类别 id：类别 id 是模型内局部的。将来小模型的 3 号可能是钥匙、
 * 大模型的 3 号是摩托车，只存一个 int 就会出现"用户说找钥匙，应用兴冲冲地去
 * 找摩托车并播报找到了"。所以凡是跨模型引用的地方都带上模型标识。
 */
data class TargetRef(val modelId: String, val classId: Int)

/** COCO 80 类中文名（按类别 id 排序）、口语别名、真实高度（米）。 */
object Labels {

    val CLASS_CN = arrayOf(
        "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船",
        "红绿灯", "消防栓", "停车标志", "停车计时器", "长凳", "鸟", "猫", "狗", "马",
        "羊", "牛", "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带",
        "行李箱", "飞盘", "滑雪板", "滑雪板", "球", "风筝", "棒球棒", "棒球手套",
        "滑板", "冲浪板", "网球拍", "瓶子", "酒杯", "杯子", "叉子", "刀", "勺子", "碗",
        "香蕉", "苹果", "三明治", "橙子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈",
        "蛋糕", "椅子", "沙发", "盆栽", "床", "餐桌", "马桶", "电视", "笔记本电脑",
        "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "水槽", "冰箱",
        "书", "时钟", "花瓶", "剪刀", "玩具熊", "吹风机", "牙刷"
    )

    /** 中文口语别名 -> 类别 id。 */
    val ALIASES: Map<String, Int> = buildMap {
        put("人", 0); put("行人", 0); put("大人", 0); put("小孩", 0)
        put("自行车", 1); put("单车", 1)
        put("汽车", 2); put("小汽车", 2); put("轿车", 2); put("车子", 2)
        put("摩托车", 3); put("电动车", 3)
        put("飞机", 4)
        put("公交车", 5); put("巴士", 5); put("公共汽车", 5)
        put("火车", 6)
        put("卡车", 7); put("货车", 7)
        put("船", 8)
        put("红绿灯", 9); put("交通灯", 9)
        put("消防栓", 10)
        put("停车标志", 11)
        put("长凳", 13); put("长椅", 13)
        put("鸟", 14); put("小鸟", 14)
        put("猫", 15); put("猫咪", 15)
        put("狗", 16); put("小狗", 16)
        put("马", 17)
        put("羊", 18)
        put("牛", 19)
        put("大象", 20)
        put("熊", 21)
        put("斑马", 22)
        put("长颈鹿", 23)
        put("背包", 24); put("书包", 24)
        put("雨伞", 25); put("伞", 25)
        put("手提包", 26); put("包", 26)
        put("领带", 27)
        put("行李箱", 28); put("箱子", 28)
        put("飞盘", 29)
        put("球", 32)
        put("风筝", 33)
        put("滑板", 36)
        put("网球拍", 38)
        put("瓶子", 39); put("水瓶", 39)
        put("酒杯", 40)
        put("杯子", 41); put("水杯", 41)
        put("叉子", 42)
        put("刀", 43)
        put("勺子", 44)
        put("碗", 45)
        put("香蕉", 46)
        put("苹果", 47)
        put("三明治", 48)
        put("橙子", 49); put("橘子", 49)
        put("西兰花", 50)
        put("胡萝卜", 51)
        put("热狗", 52)
        put("披萨", 53)
        put("甜甜圈", 54)
        put("蛋糕", 55)
        put("椅子", 56)
        put("沙发", 57)
        put("盆栽", 58)
        put("床", 59)
        put("餐桌", 60); put("桌子", 60)
        put("马桶", 61)
        put("电视", 62); put("电视机", 62)
        put("笔记本电脑", 63); put("笔记本", 63); put("电脑", 63)
        put("鼠标", 64)
        put("遥控器", 65)
        put("键盘", 66)
        put("手机", 67); put("电话", 67)
        put("微波炉", 68)
        put("烤箱", 69)
        put("烤面包机", 70)
        put("水槽", 71)
        put("冰箱", 72)
        put("书", 73); put("书本", 73)
        put("时钟", 74); put("钟", 74)
        put("花瓶", 75)
        put("剪刀", 76)
        put("玩具熊", 77); put("小熊", 77); put("泰迪熊", 77)
        put("吹风机", 78)
        put("牙刷", 79)
    }

    /**
     * 真实世界高度（米），用于单目测距。
     *
     * 覆盖全部 COCO 80 类（键为类别 id）。原先只填了 20 类，其余全部兜底 0.3 米，
     * 导致椅子(0.9)被当成 0.3、长凳(0.85)被当成 0.3，测距直接差 3 倍。
     */
    val REAL_HEIGHT = mapOf(
        // 人与交通
        0 to 1.70f, 1 to 1.10f, 2 to 1.50f, 3 to 1.20f, 4 to 4.00f,
        5 to 3.00f, 6 to 3.80f, 7 to 3.00f, 8 to 2.00f, 9 to 0.90f,
        10 to 0.75f, 11 to 0.75f, 12 to 1.20f, 13 to 0.85f,
        // 动物
        14 to 0.15f, 15 to 0.25f, 16 to 0.45f, 17 to 1.60f, 18 to 0.80f,
        19 to 1.40f, 20 to 2.80f, 21 to 1.30f, 22 to 1.40f, 23 to 4.50f,
        // 随身物品
        24 to 0.50f, 25 to 0.90f, 26 to 0.30f, 27 to 0.45f, 28 to 0.65f,
        // 运动器材
        29 to 0.27f, 30 to 1.70f, 31 to 1.50f, 32 to 0.22f, 33 to 0.80f,
        34 to 0.85f, 35 to 0.30f, 36 to 0.80f, 37 to 1.80f, 38 to 0.70f,
        // 餐具与食物
        39 to 0.25f, 40 to 0.20f, 41 to 0.10f, 42 to 0.19f, 43 to 0.24f,
        44 to 0.18f, 45 to 0.08f, 46 to 0.20f, 47 to 0.08f, 48 to 0.10f,
        49 to 0.08f, 50 to 0.15f, 51 to 0.18f, 52 to 0.10f, 53 to 0.30f,
        54 to 0.10f, 55 to 0.15f,
        // 家具家电
        56 to 0.90f, 57 to 0.85f, 58 to 0.60f, 59 to 0.60f, 60 to 0.75f,
        61 to 0.75f, 62 to 0.70f, 63 to 0.25f, 64 to 0.04f, 65 to 0.18f,
        66 to 0.15f, 67 to 0.15f, 68 to 0.30f, 69 to 0.90f, 70 to 0.20f,
        71 to 0.25f, 72 to 1.75f, 73 to 0.20f, 74 to 0.30f, 75 to 0.30f,
        76 to 0.20f, 77 to 0.40f, 78 to 0.25f, 79 to 0.19f
    )
    const val DEFAULT_HEIGHT = 0.3f

    /**
     * 高度先验"可信"的类别：刚性、竖直放置、通常直接立在地面上，检测框高度 ≈ 真实高度。
     * 融合测距时给这些类别更高权重；其余类别（小物件、可变形、姿态多变）可见高度
     * 随视角剧烈变化，权重调低。
     */
    val TRUSTED_HEIGHT = setOf(
        0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13,
        17, 19, 20, 21, 22, 23,
        56, 57, 58, 60, 61, 62, 68, 69, 71, 72
    )

    /** 该类别的高度先验是否可信（用于融合权重）。 */
    fun heightTrust(id: Int): Boolean = TRUSTED_HEIGHT.contains(id)


    // ---------------- 语音指令词表 ----------------
    //
    // 匹配用的是**子串**匹配，所以词表里任何一个词都不允许和别的词冲突：
    //   * 不能是另一个指令词的子串（"周围有什么" 含 "有什么"）；
    //   * 不能是某个类别名或别名的一部分（"停车标志" 含 "停"）。
    // 否则就会出现"说了 A 却执行了 B" —— 这个应用里最危险的一类错误。
    // 下面每条都逐一对过 80 个类别名和 90 个别名，几处关键的坑写在注释里。
    //
    // **顺序即优先级**，见 [parseCommand] 里的编号说明。

    /**
     * 找到了 -> 安静待命。
     * 不收单字"停"："停车标志""停车计时器"里都有它，收了就会把"找停车标志"
     * 解析成"找到了"。只收"停下"这种不会出现在类别名里的说法。
     */
    private val FOUND_WORDS = arrayOf(
        "找到了", "找着了", "不用找了", "不找了", "停下", "先停下", "待命")

    /**
     * 静音。
     * 必须排在 [CANCEL_WORDS] 之前："不要说话"里含"不要"。
     */
    private val MUTE_WORDS = arrayOf(
        "别说话", "别说了", "不要说话", "安静", "闭嘴", "停止播报", "先别说")

    /**
     * 恢复播报。
     * 必须排在 [RESUME_WORDS] 之前："继续说"里含"继续"，否则"继续说"会被
     * 当成"继续找" —— 用户想恢复播报，结果扫描状态被重置了。
     */
    private val UNMUTE_WORDS = arrayOf(
        "可以说了", "说话吧", "开始播报", "继续说")

    /**
     * 继续找 -> 从待命恢复扫描。
     * 必须排在 [TARGET_PREFIXES] 之前："继续找""接着找"里都含"找"，
     * 否则会被当成"找（继续）"这种没有意义的目标请求。
     */
    private val RESUME_WORDS = arrayOf(
        "继续找", "接着找", "继续", "接着", "恢复", "接着来")

    /**
     * 帮助。
     * 必须排在 [DESCRIBE_WORDS] 之前："有什么功能"里含"有什么"。
     */
    private val HELP_WORDS = arrayOf(
        "帮助", "怎么用", "能做什么", "有什么功能", "指令")

    /** 描述周围：把画面里所有物体一次说出来。 */
    private val DESCRIBE_WORDS = arrayOf(
        "看看周围", "周围有什么", "前面有什么", "看看有什么", "有什么", "帮我看看")

    /**
     * 再说一遍。
     * 不收单字"什么"："周围有什么"里含它，会抢走 [DESCRIBE_WORDS]。
     */
    private val REPEAT_WORDS = arrayOf(
        "再说一遍", "再说一次", "重复一遍", "重复", "没听清")

    private val LOUDER_WORDS = arrayOf("大点声", "大声点", "声音大", "听不见")
    private val QUIETER_WORDS = arrayOf("小点声", "小声点", "声音小", "太吵")
    private val SLOWER_WORDS = arrayOf("说慢点", "慢点说", "说慢一些")
    private val FASTER_WORDS = arrayOf("说快点", "快点说", "说快一些")

    /**
     * 切换模型。
     * 必须排在 [TARGET_PREFIXES] 之前："换小模型"里含"换"。
     * 不收"小东西"这类说法 —— 它既可能指"找小东西"也可能指"切小模型"，太含糊，
     * 宁可让用户说清楚一点，也不要猜。
     */
    private val SMALL_MODEL_WORDS = arrayOf("小模型", "小物体", "小物体模式")
    private val BIG_MODEL_WORDS = arrayOf("大模型", "普通模型", "大物体模式")

    /**
     * 退出。
     * 真正执行前还有二次确认（见 [parseCommand] 的 `awaitingExitConfirm`），
     * 所以这里可以放宽口语说法，不怕误触。
     */
    private val EXIT_WORDS = arrayOf("退出", "关闭", "关掉", "不玩了")

    /** 退出确认态下才生效，见 [parseCommand] 的 `awaitingExitConfirm`。 */
    private val CONFIRM_WORDS = arrayOf("确认", "确定", "是的", "退出吧")

    /**
     * 取消。
     * 不收单字"不要"：它太容易出现在正常句子里（"找杯子，不要别的"），
     * 会把一句正常的找物品请求变成取消。而"不要说话"已经归到 [MUTE_WORDS]。
     */
    private val CANCEL_WORDS = arrayOf("取消", "算了")

    private val TARGET_PREFIXES = arrayOf(
        "帮我找一个", "帮我找下", "帮我找", "我要找一个", "我要找下", "我要找",
        "我想找一个", "我想找下", "我想找", "请找一个", "请找下", "请找", "找一个",
        "找下", "找", "换成", "换一个", "换个", "换"
    )

    /**
     * 全部语音指令。
     *
     * 拆得这么细是为了让 MainActivity 的 `when` 是穷尽的 —— 编译器会挡住
     * "新增了一条指令但忘了处理"这种情况。
     */
    sealed class Command {
        /** 找到了 / 停下 -> 安静待命，不再播报 */
        data object Found : Command()

        /** 继续找 -> 从待命恢复扫描 */
        data object Resume : Command()

        /** 再说一遍 */
        data object Repeat : Command()

        /** 安静：停止播报，但继续扫描 */
        data object Mute : Command()

        /** 可以说了：恢复播报 */
        data object Unmute : Command()

        /** 看看周围：把画面里所有物体一次说出来 */
        data object Describe : Command()

        /** 帮助：能做什么、怎么说 */
        data object Help : Command()

        data object Louder : Command()
        data object Quieter : Command()
        data object Slower : Command()
        data object Faster : Command()

        /** 退出。调用方必须先问一次再执行 —— 退出不可撤销。 */
        data object Exit : Command()

        /** 确认退出（只在确认态下会产生） */
        data object Confirm : Command()

        /** 取消（只在确认态下会产生） */
        data object Cancel : Command()

        /** 切换检测模型 */
        data class SwitchModel(val modelId: String) : Command()

        /** 找某个物品 */
        data class Target(val ref: TargetRef) : Command()
    }

    /**
     * 匹配表：所有已注册模型的类别中文名 + 口语别名，合并成一张
     * 表层词 -> [TargetRef] 的表，匹配时按**最长的表层词优先**。
     *
     * 只查 [ALIASES] 有两个坑，第二个尤其致命：
     *   1) 漏收录 —— 停车计时器、滑雪板、棒球棒、棒球手套、冲浪板叫不出来；
     *   2) **错配** —— ALIASES 里有"球""包""刀""伞"这类极短的口语词，而
     *      "棒球棒""棒球手套"里都含"球"、"烤面包机"里含"包"。于是用户说
     *      "找棒球棒"会被匹配成 32(sports ball)、说"找烤面包机"会被匹配成
     *      26(handbag) —— 应用不会报错，它会**自信地去找一个完全不相干的东西**
     *      并播报"找到了"。对盲人用户来说这比"没听懂"危险得多。
     *
     * 注意：光加"兜底查询"救不了 —— 别名循环会先返回。必须合并成同一张表，
     * 再按长度降序，"棒球棒"(3) 才能先于 "球"(1) 命中。
     *
     * 值用 [TargetRef] 而不是裸的类别 id：类别 id 是**模型内局部**的，
     * 将来小模型的 3 号可能是钥匙、大模型的 3 号是摩托车，只存 id 会张冠李戴。
     */
    private val MATCH_TABLE: Map<String, TargetRef> by lazy {
        val m = HashMap<String, TargetRef>()
        // 各模型自己的类别名。先登记的模型优先（putIfAbsent）：
        // 大模型覆盖常见物品，小模型将来若定义同名类别仍走大模型，
        // 避免同一个词指向两个模型。
        for (spec in Models.ALL) {
            spec.classNames.forEachIndexed { id, cn ->
                if (cn.isNotBlank()) m.putIfAbsent(cn, TargetRef(spec.id, id))
            }
        }
        // 别名是 COCO 语境的说法（"单车" -> 自行车），所以只归到大模型。
        ALIASES.forEach { (alias, id) -> m.putIfAbsent(alias, TargetRef(Models.BIG.id, id)) }
        // 30(skis) 与 31(snowboard) 的中文名都是"滑雪板"，补两个能区分的说法
        m["双板滑雪板"] = TargetRef(Models.BIG.id, 30)
        m["单板滑雪板"] = TargetRef(Models.BIG.id, 31)
        m
    }

    private val MATCH_SURFACES: List<String> by lazy {
        MATCH_TABLE.keys.sortedByDescending { it.length }
    }

    /** 从一句话里按最长的表层词匹配，返回目标引用；匹配不到返回 null。 */
    fun matchTarget(text: String): TargetRef? {
        for (surface in MATCH_SURFACES) {
            if (text.contains(surface)) return MATCH_TABLE[surface]
        }
        return null
    }

    /**
     * 解析 ASR 文本。
     *
     * 匹配是子串匹配，**顺序即优先级**，调换顺序会改变行为，每一步的理由见下。
     *
     * @param awaitingExitConfirm 是否正在等用户确认退出。
     *        这个参数不能省：确认态必须**只**认确认/取消两种说法，别的说法一律
     *        返回 null 让调用方再问一次。若把"确认""是的"放进通用词表，
     *        用户一句"是的，我要找杯子"就会先命中"是的"而丢掉真正的目标。
     *        反过来，退出是不可撤销的操作，也不该因为听岔就执行。
     */
    fun parseCommand(text: String, awaitingExitConfirm: Boolean = false): Command? {
        if (text.isBlank()) return null
        fun hit(words: Array<String>) = words.any { text.contains(it) }

        if (awaitingExitConfirm) {
            if (hit(CONFIRM_WORDS) || hit(EXIT_WORDS)) return Command.Confirm
            if (hit(CANCEL_WORDS)) return Command.Cancel
            return null
        }

        // 1. 待命。放在最前面：它的说法里含"找"，不能被下面的目标前缀抢走。
        if (hit(FOUND_WORDS)) return Command.Found
        // 2. 静音必须早于取消："不要说话"含"不要"。
        if (hit(MUTE_WORDS)) return Command.Mute
        // 3. 恢复播报必须早于继续找："继续说"含"继续"。
        if (hit(UNMUTE_WORDS)) return Command.Unmute
        if (hit(RESUME_WORDS)) return Command.Resume
        // 4. 帮助必须早于描述周围："有什么功能"含"有什么"。
        if (hit(HELP_WORDS)) return Command.Help
        if (hit(DESCRIBE_WORDS)) return Command.Describe
        if (hit(REPEAT_WORDS)) return Command.Repeat
        // 5. 音量与语速
        if (hit(LOUDER_WORDS)) return Command.Louder
        if (hit(QUIETER_WORDS)) return Command.Quieter
        if (hit(SLOWER_WORDS)) return Command.Slower
        if (hit(FASTER_WORDS)) return Command.Faster
        // 6. 模型切换必须早于目标匹配："换小模型"含"换"。
        if (hit(SMALL_MODEL_WORDS)) return Command.SwitchModel(Models.SMALL.id)
        if (hit(BIG_MODEL_WORDS)) return Command.SwitchModel(Models.BIG.id)
        // 7. 退出与取消
        if (hit(EXIT_WORDS)) return Command.Exit
        if (hit(CANCEL_WORDS)) return Command.Cancel

        // 8. 目标："找X / 换X"。去掉前缀后在剩余部分里找，
        //    找不到再退回整句找 —— 用户可能说"找一下那个杯子"。
        for (p in TARGET_PREFIXES.sortedByDescending { it.length }) {
            if (text.contains(p)) {
                val rest = text.replace(p, "", ignoreCase = false)
                val ref = matchTarget(rest) ?: matchTarget(text)
                if (ref != null) return Command.Target(ref)
            }
        }
        return matchTarget(text)?.let { Command.Target(it) }
    }
}
