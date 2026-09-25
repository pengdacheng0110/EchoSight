package com.echosight.app

import android.content.Context
import android.util.Log

/**
 * 一个可用的检测模型。
 *
 * ## 为什么类别 id 是"模型内局部"的
 *
 * 后面要加一个专门识别小物体的模型，它的类别表跟现在这套 COCO 80 类不一样。
 * 所以**不能拿一个模型的类别 id 去另一个模型里查名字** —— 小模型的 3 号可能是
 * 钥匙，大模型的 3 号是摩托车，混用会"自信地报出完全不相干的名字"。
 * 所有 id → 名字的换算都必须经由 [ModelSpec]，跨模型引用一律走 [TargetRef]。
 *
 * ## 加一个小模型要做什么
 *
 * 1. 把 .onnx 放到 `android/app/src/main/assets/`；
 * 2. 在 [Models] 里登记一条 [ModelSpec]，填对 [assetName] 与 [classNames]。
 *
 * 就这两步。语音切换（"换小模型"）、自动兜底、按名字找物品（"找钥匙"）
 * 都会自动跟着生效，不需要改别处的代码。
 *
 * ## 类别表里没登记真实高度的类会怎样
 *
 * 单目测距的高度法要用"这个物体真实多高"。高度表在 [Labels.REAL_HEIGHT]，
 * 目前只覆盖 COCO 80 类。小模型引入的新类别会落到 [Labels.DEFAULT_HEIGHT]（0.3 米），
 * 距离随之失真 —— 但不会静默：[Guidance] 会把这种结果标成低置信度
 * （`source = "height"`、`confidence` 降到 0.5 以下），界面副标题也会显示来源。
 * [Models.isAvailable] 另外会在 logcat 里告警一次，提醒补高度表。
 *
 * @param id          稳定标识，用于语音切换与日志
 * @param assetName   `assets/` 下的文件名
 * @param displayName 语音里怎么称呼它（"大模型" / "小模型"）
 * @param classNames  类别名，**下标即该模型输出的类别 id**
 * @param inputSize   网络输入边长（正方形 letterbox）
 */
data class ModelSpec(
    val id: String,
    val assetName: String,
    val displayName: String,
    val classNames: Array<String>,
    val inputSize: Int = 320,
) {
    val classCount get() = classNames.size

    /** 类别 id → 中文名。越界或空名一律给一个说得出口的兜底说法。 */
    fun nameOf(classId: Int): String =
        classNames.getOrNull(classId)?.takeIf { it.isNotBlank() } ?: "未知物品"

    /** 这个类别 id 在本模型里是否有效。 */
    fun hasClass(classId: Int): Boolean =
        classNames.getOrNull(classId)?.isNotBlank() == true
}

/**
 * 已注册的检测模型。
 *
 * [SMALL] 现在**还没装进仓库**（assets 里没有那个 .onnx），但仍然登记在这里：
 * 这样加模型时只要丢文件 + 填类别表，其余逻辑自动生效。
 *
 * 登记了却不可用，就**必须让用户听得出来**（见 [isAvailable] 与
 * MainActivity 里对切换失败的处理）。不可用时静默什么都不做，用户会以为
 * "说了没反应" —— 那是最糟的一种失败，因为他不知道该不该再试一次。
 */
object Models {

    /** 通用物体（COCO 80 类），仓库里现成有的那个。 */
    val BIG = ModelSpec(
        id = "big",
        assetName = "yolo26n.onnx",
        displayName = "大模型",
        classNames = Labels.CLASS_CN,
    )

    /**
     * 小物体专用模型（待接入）。
     *
     * 接入时把 [assetName] 与 [classNames] 改成实际的即可。
     * [classNames] 的下标必须与模型输出通道一一对应，顺序错了会"自信地报错名字"。
     */
    val SMALL = ModelSpec(
        id = "small",
        assetName = "yolo_small.onnx",
        displayName = "小模型",
        classNames = emptyArray(),
    )

    val ALL = listOf(BIG, SMALL)

    /** 默认使用的模型。 */
    val DEFAULT get() = BIG

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }

    /**
     * 模型是否真的能用。
     *
     * 两个条件都要满足：类别表非空（否则 id → 名字无从查起）、
     * assets 里确实有这个文件（否则 createSession 会抛 FileNotFoundException）。
     * 只判前者不够 —— 文件没打进包是很容易发生的事。
     */
    fun isAvailable(context: Context, spec: ModelSpec): Boolean {
        if (spec.classCount == 0) return false
        return runCatching { context.assets.open(spec.assetName).close() }.isSuccess
    }

    /**
     * 类别表里有没有漏掉真实高度。
     *
     * 漏了不会崩，但测距会失真（落到 [Labels.DEFAULT_HEIGHT]）。
     * 这里只在 logcat 里提醒一次，让加模型的人能看见，而不是等用户发现距离不对。
     */
    fun warnMissingHeights(spec: ModelSpec) {
        val missing = (0 until spec.classCount).filter { !Labels.REAL_HEIGHT.containsKey(it) }
        if (missing.isNotEmpty()) {
            val names = missing.take(8).joinToString("、") { spec.nameOf(it) }
            Log.w("Models", "模型 ${spec.id} 有 ${missing.size} 个类别没登记真实高度" +
                "（测距会退回 ${Labels.DEFAULT_HEIGHT} 米兜底值）：$names" +
                if (missing.size > 8) " …" else "")
        }
    }
}
