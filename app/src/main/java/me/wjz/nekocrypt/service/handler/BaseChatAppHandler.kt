package me.wjz.nekocrypt.service.handler

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.wjz.nekocrypt.CryptoMode
import me.wjz.nekocrypt.R
import me.wjz.nekocrypt.service.NCAccessibilityService
import me.wjz.nekocrypt.ui.DecryptionPopupContent
import me.wjz.nekocrypt.util.CryptoManager
import me.wjz.nekocrypt.util.CryptoManager.appendNekoTalk
import me.wjz.nekocrypt.util.WindowPopupManager
import kotlin.system.measureTimeMillis

abstract class BaseChatAppHandler : ChatAppHandler {
    protected val tag = "NCBaseHandler"

    // 由子类提供具体应用的ID
    abstract override val inputId: String
    abstract override val sendBtnId: String
    abstract override val messageTextId: String
    abstract override val messageListClassName: String

    // 处理器内部状态
    private var service: NCAccessibilityService? = null
    // 按钮遮罩的管理器
    private var overlayWindowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayManagementJob: Job? = null

    // 为我们的界面节点变量做缓存
    private var cachedSendBtnNode: AccessibilityNodeInfo? = null
    private var cachedInputNode: AccessibilityNodeInfo? = null

    // 为 RecyclerView/ListView 创建一个专属的缓存
    private var cachedMessageListNode: AccessibilityNodeInfo? = null

    // 为沉浸式解密创建一个“防抖”任务，避免过于频繁的扫描
    private var immersiveDecryptionJob: Job? = null

    // Key: 一个消息气泡的唯一标识符 (位置 + 文本哈希)
    // Value: 管理这个气泡弹窗的 WindowPopupManager 实例
    private val immersiveDecryptionCache = mutableMapOf<String, WindowPopupManager>()
    // 拿来判断是否拉起图片、视频弹窗。
    private var lastInputClickTime:Long = 0L

    /**
     * 根据发送按钮的矩形区域，基于每个APP定向创建加密悬浮窗的布局参数。
     *
     * @param anchorRect 发送按钮在屏幕上的位置和大小。
     * @return 配置好的 WindowManager.LayoutParams 对象。
     */
    abstract fun getOverlayLayoutParams(
        anchorRect: Rect,
    ): WindowManager.LayoutParams

    // 根据不同APP定向修改解密弹窗的window位置。
    abstract fun modifyDecryptionWindowRect(rect: Rect): Rect


    override fun onAccessibilityEvent(event: AccessibilityEvent, service: NCAccessibilityService) {
        // 悬浮窗管理逻辑
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (service.useAutoEncryption) {
                //只有开启加密，才会加上悬浮窗，每次事件改变，都要更新悬浮窗位置
                overlayManagementJob?.cancel()
                overlayManagementJob = service.serviceScope.launch(Dispatchers.Default) {
                    handleOverlayManagement()   // 可能是添加、更新、删除悬浮窗
                }
            } else {
                removeOverlayView()
            }
        }

        // 解密逻辑，开启解密，才进行解密操作。
        if (service.useAutoDecryption) {
            when (service.decryptionMode) {
                // 标准模式：用户点击密文时，才进行解密
                CryptoMode.STANDARD.key -> {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        handleDecryption(event.source)
                    }
                }
                // 沉浸模式：当窗口内容变化时，主动扫描并解密
                CryptoMode.IMMERSIVE.key -> {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                        //带防抖处理
                        immersiveDecryptionJob?.cancel()
                        // 启动一个新的扫描任务
                        immersiveDecryptionJob = service.serviceScope.launch(Dispatchers.Default) {
                            // ✨ 等待300毫秒，如果在这期间又有新的事件进来，这个任务就会被取消
                            delay(service.decryptionWindowUpdateInterval)
                            Log.d(tag, "UI稳定，开始执行沉浸式解密...")
                            performImmersiveDecryption()
                        }
                    }
                }
            }
        }

        // 监听点击事件，用来拉起图片视频文件发送弹窗
        if(event.eventType ==AccessibilityEvent.TYPE_VIEW_CLICKED){
            handleInputDoubleClick(event.source)
        }
    }

    // 启动服务
    override fun onHandlerActivated(service: NCAccessibilityService) {
        this.service = service
        this.overlayWindowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(tag, "激活$packageName 处理器。")
    }

    // 销毁服务
    override fun onHandlerDeactivated() {
        overlayManagementJob?.cancel()
        immersiveDecryptionJob?.cancel()
        // 用一个副本做遍历避免删除时下标异常
        val managersToDismiss = immersiveDecryptionCache.values.toList()
        // 依次关闭所有弹窗
        managersToDismiss.forEach { it.dismiss() }
        immersiveDecryptionCache.clear()    // 最后确保万无一失

        cachedSendBtnNode = null
        cachedInputNode = null
        cachedMessageListNode = null
        removeOverlayView {
            // 在视图置空后，其他引用量也要置为空，方便gc回收
            this.service = null
            this.overlayWindowManager = null
            Log.d(tag, "取消$packageName 处理器。")
        }
    }

    /**
     * 处理节点检查是否需要解密。
     * @param sourceNode 用户点击的源节点。
     */
    private fun handleDecryption(
        sourceNode: AccessibilityNodeInfo?,
        isShowLongTime: Boolean = false,
    ) {
        // 经过多次实际测试，单次解密()
        val costTime = measureTimeMillis {
            val node = sourceNode ?: return
            val text = node.text?.toString() ?: return
            val currentService = service ?: return

            // 1. 判断节点文本是否包含我们的“猫语”
            if (CryptoManager.containsCiphertext(text)) {
                Log.d(tag, "检测到密文: $text")
                // 2. 尝试用所有密钥进行解密
                Log.d(tag, "目前的全部密钥${currentService.cryptoKeys.joinToString()}")
                for (key in currentService.cryptoKeys) {
                    val decryptedText = CryptoManager.decrypt(text, key)
                    // 3. 只要有一个密钥解密成功...
                    if (decryptedText != null) {
                        Log.d(tag, "解密成功 -> $decryptedText")
                        // ...就立刻显示我们的解密弹窗！
                        showDecryptionPopup(
                            decryptedText,
                            node,
                            if (isShowLongTime) 60 else currentService.decryptionWindowShowTime
                        )
                        break // 停止尝试其他密钥
                    }
                }
            }
        }
        Log.d(tag, "解密耗时$costTime ms")
    }

    /**
     * 执行沉浸式解密相关逻辑。
     */
    private fun performImmersiveDecryption() {
        val executionTime = measureTimeMillis {
            val currentService = service ?: return
            // ✨ 1. 优先信任缓存：检查消息列表节点的缓存是否依然有效
            val messageList = if (isNodeValid(cachedMessageListNode)) {
                cachedMessageListNode
            } else {
                // ✨ 2. 缓存无效，则在整个窗口中查找一次列表容器，并存入缓存
                findMessageListContainer(currentService.rootInActiveWindow)?.also {
                    Log.d(tag, "找到了新的消息列表容器并已缓存！")
                    cachedMessageListNode = it
                }
            }
            // 3. 如果最终我们拥有了一个有效的列表容器...
            if (messageList != null) {
                val messageNodes = messageList.findAccessibilityNodeInfosByViewId(messageTextId)
                if (messageNodes.isNullOrEmpty()) {
                    Log.d(tag, "消息列表中无消息或已离开聊天界面，开始清理所有弹窗...")
                    // 如果缓存中还有弹窗，说明是用户离开了聊天界面，需要全部清理
                    if (immersiveDecryptionCache.isNotEmpty()) {
                        currentService.serviceScope.launch(Dispatchers.Main) {
                            val managersToDismiss = immersiveDecryptionCache.values.toList()
                            Log.d(tag, "清理 ${managersToDismiss.size} 个残留弹窗。")
                            managersToDismiss.forEach { it.dismiss() }
                        }
                    }
                    return // 这里直接退出整个函数，也不会执行计时了
                }
                // 分成三类
                val visibleCacheKeys = mutableSetOf<String>()   // 此轮可见的缓存key。
                val creationTasks = mutableListOf<Triple<String, AccessibilityNodeInfo, String>>()
                val updateTasks = mutableListOf<Pair<WindowPopupManager, Rect>>()

                for (node in messageNodes) {
                    val text = node.text?.toString()
                    if (text.isNullOrEmpty() || !CryptoManager.containsCiphertext(text)) continue

                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)
                    val cacheKey = text.hashCode().toString()   // key就直接哈希
                    visibleCacheKeys.add(cacheKey)

                    val existingManager = immersiveDecryptionCache[cacheKey]

                    if (existingManager != null) {
                        // ✨ 如果弹窗已存在，则加入“更新位置”任务列表
                        val targetPopupRect = modifyDecryptionWindowRect(nodeBounds)
                        updateTasks.add(existingManager to targetPopupRect)
                    } else {
                        // ✨ 如果弹窗不存在，则加入“创建弹窗”任务列表
                        for (key in currentService.cryptoKeys) {
                            val decryptedText = CryptoManager.decrypt(text, key)
                            if (decryptedText != null) {
                                creationTasks.add(Triple(decryptedText, node, cacheKey))
                                break
                            }
                        }
                    }
                }
                // 找到需要被清除的弹窗。比如用户滑动了窗口，有的弹窗对应的气泡不再可见，就需要消失。
                val cachedKeys = immersiveDecryptionCache.keys.toSet()
                val keysToDismiss = cachedKeys - visibleCacheKeys

                if (keysToDismiss.isNotEmpty() || updateTasks.isNotEmpty() || creationTasks.isNotEmpty()) {
                    Log.d(tag, "--- 沉浸式解密任务分配 ---")
                    Log.d(tag, "需要销毁的弹窗 (${keysToDismiss.size}个): $keysToDismiss")
                    Log.d(tag, "需要更新位置的弹窗 (${updateTasks.size}个)")
                    Log.d(
                        tag,
                        "需要新创建的弹窗 (${creationTasks.size}个): ${creationTasks.map { it.third }}"
                    )
                    Log.d(tag, "--------------------------")
                }


                // 整理完毕，在主线程执行操作
                if (keysToDismiss.isNotEmpty() || updateTasks.isNotEmpty() || creationTasks.isNotEmpty()) {
                    currentService.serviceScope.launch(Dispatchers.Main) {
                        keysToDismiss.forEach {
                            immersiveDecryptionCache[it]?.dismiss() // dismiss里面会自动让对象本身为null
                        }
                        updateTasks.forEach { (manager, rect) ->
                            if (!isActive) return@forEach
                            manager.updatePosition(rect)
                        }
                        creationTasks.forEach { (decryptedText, node, cacheKey) ->
                            if (!isActive) return@forEach

                            // ✨ [正确逻辑] 1. 调用通用函数，并传入“从缓存移除自己”的正确回调
                            val popupManager = showDecryptionPopup(
                                decryptedText = decryptedText,
                                anchorNode = node,
                                showTime = 30000, // 配置项为 currentService.decryptionWindowShowTime
                                onDismiss = {
                                    // 这个回调在弹窗关闭时执行，完美地维护了缓存
                                    immersiveDecryptionCache.remove(cacheKey)
                                    Log.d(tag, "弹窗关闭，从缓存中移除: $cacheKey")
                                }
                            )
                            // ✨ [正确逻辑] 2. 将返回的管理器实例存入缓存
                            immersiveDecryptionCache[cacheKey] = popupManager
                            Log.d(tag, "新弹窗已创建并加入缓存: $cacheKey")

                            delay(32L)
                        }
                    }

                }
            } else {
                Log.w(tag, "未找到消息列表容器，无法执行沉浸式解密。")
            }
        }
        Log.d(tag, "沉浸式解密扫描和任务分派完成，总耗时: ${executionTime}ms")
    }

    /**
     * ✨ 新增：一个专门用来查找消息列表容器的辅助函数。
     * 它使用广度优先搜索来查找第一个符合条件的节点。
     */
    private fun findMessageListContainer(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        val nodesToProcess = ArrayDeque<AccessibilityNodeInfo>()
        nodesToProcess.add(rootNode)

        while (nodesToProcess.isNotEmpty()) {
            val node = nodesToProcess.removeFirst()
            if (node.className?.toString()?.contains(messageListClassName) == true) {
                return node // 找到了！
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { nodesToProcess.add(it) }
            }
        }
        return null // 没找到
    }

    private fun showDecryptionPopup(
        decryptedText: String,
        anchorNode: AccessibilityNodeInfo,
        showTime: Long,
        onDismiss: (() -> Unit)? = null,
    ): WindowPopupManager {
        val currentService = service!!

        val anchorRect = Rect()
        anchorNode.getBoundsInScreen(anchorRect)

        // 每个弹窗都有自己的管理器实例。
        var popupManager: WindowPopupManager? = null
        // 创建并显示我们的弹窗
        popupManager = WindowPopupManager(
            context = currentService, onDismissRequest = {
                onDismiss?.invoke()
                popupManager = null
            },// 关闭时清理引用
            anchorRect = modifyDecryptionWindowRect(anchorRect)
        ) {
            // 把UI内容传进去
            DecryptionPopupContent(
                text = decryptedText,
                onDismiss = { popupManager?.dismiss() },
                durationMills = showTime
            )
        }
        popupManager?.show()
        return popupManager!!
    }


    // --- 所有悬浮窗和加密逻辑都内聚在这里 ---

    /**
     * ✨ 终极形态的悬浮窗管理逻辑 ✨
     * 先检查缓存，再搜索
     */
    protected fun handleOverlayManagement() {
        // delay(50)
        var sendBtnNode: AccessibilityNodeInfo?

        // 1. 优先信任缓存：用廉价的 refresh() 给缓存“体检”
        if (isNodeValid(cachedSendBtnNode)) {
            sendBtnNode = cachedSendBtnNode
        }
        // 2. 缓存无效，才启动昂贵的“全楼大搜查”
        else {
            val rootNode = service?.rootInActiveWindow ?: return
            sendBtnNode = findNodeById(rootNode, sendBtnId)
            // 搜索到了就更新缓存，为下一次做准备
            cachedSendBtnNode = sendBtnNode
        }

        // 3. 根据最终的节点状态来决定如何操作
        if (sendBtnNode != null) {
            val rect = Rect()
            sendBtnNode.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                createOrUpdateOverlayView(rect)
            } else {
                // 节点虽然存在，但没有实际尺寸，也视为无效
                removeOverlayView()
            }
        } else {
            // 如果最终还是没有有效节点，就清理
            removeOverlayView()
        }
    }

    /**
     * @param rect 悬浮窗的目标位置和大小。
     */
    protected fun createOrUpdateOverlayView(rect: Rect) {
        val currentService = service ?: return
        //  绘制悬浮窗位置所需要用到的参数
        val params = getOverlayLayoutParams(rect)
        currentService.serviceScope.launch(Dispatchers.Main) {
            if (overlayView == null) {
                overlayView = View(currentService).apply {
                    setBackgroundColor(currentService.sendBtnOverlayColor.toColorInt())

                    // ✨ 1. 首先，为视图定义一个标准的“单击”行为
                    // 这就是我们的“标准门铃按钮”。
                    setOnClickListener {
                        // 标准模式下，短按执行普通发送
                        if (currentService.encryptionMode == CryptoMode.STANDARD.key) {
                            Log.d(currentService.tag, "标准模式短按，执行普通发送！")
                            doNormalClick()
                        }
                        // 沉浸模式下，短按（单击）也执行加密发送
                        else if (currentService.encryptionMode == CryptoMode.IMMERSIVE.key) {
                            Log.d(currentService.tag, "沉浸模式点击，执行加密！")
                            doEncryptAndClick()
                        }
                    }

                    // ✨ 2. 然后，我们只用 onTouch 来“监听”手势，特别是长按
                    var longPressJob: Job? = null
                    setOnTouchListener { v, event -> // 'v' 就是这个 View 本身
                        // 只在标准模式下才需要区分长按和短按
                        if (currentService.encryptionMode == CryptoMode.STANDARD.key) {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    longPressJob = currentService.serviceScope.launch {
                                        delay(currentService.longPressDelay) // 长按阈值
                                        Log.d(currentService.tag, "标准模式长按，执行加密！")
                                        doEncryptAndClick()
                                    }
                                    true // 我们要处理后续事件
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    // 如果手指抬起时，长按任务还在“准备中”...
                                    if (longPressJob?.isActive == true) {
                                        // ...说明这是一个短按，取消长按任务...
                                        longPressJob.cancel()
                                        // ✨ ...然后“按响”那个标准的门铃！
                                        v.performClick()
                                    }
                                    // 如果长按任务已经执行或被取消，这里就什么都不做
                                    true
                                }

                                else -> false
                            }
                        } else {
                            // 在沉浸模式下，我们让标准的 OnClickListener 去处理所有点击
                            // onTouch 只需要返回 false，表示“我不管，让别人来处理”
                            false
                        }
                    }
                }
                overlayWindowManager?.addView(overlayView, params)
            } else {
                overlayWindowManager?.updateViewLayout(overlayView, params)
            }
        }
    }

    // 移除悬浮窗
    protected fun removeOverlayView(onComplete: (() -> Unit)? = null) {
        service?.serviceScope?.launch(Dispatchers.Main) {
            if (overlayView != null && overlayWindowManager != null) {
                overlayWindowManager?.removeView(overlayView)
                overlayView = null

                onComplete?.invoke()
            }
        }
    }

    // 自动加密并发送消息
    protected fun doEncryptAndClick() {
        val currentService = service ?: return

        // 1. 获取发送按钮节点 (优先从缓存，不行再找)
        if (!isNodeValid(cachedSendBtnNode)) {
            val root = currentService.rootInActiveWindow ?: return
            cachedSendBtnNode = findNodeById(root, sendBtnId)
        }
        val sendBtnNode = cachedSendBtnNode ?: return

        // 2. 获取输入框节点 (逻辑同上)
        if (!isNodeValid(cachedInputNode)) {
            val root = currentService.rootInActiveWindow ?: return
            cachedInputNode = findNodeById(root, inputId)
        }
        val inputNode = cachedInputNode ?: return

        // 3. 执行核心加密逻辑
        val originalText = inputNode.text?.toString()
        if (originalText.isNullOrEmpty()) {
            // 输入框内容为空，直接发送。
            sendBtnNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val encryptedText =
                CryptoManager.encrypt(originalText, currentService.currentKey).appendNekoTalk()
            val isSetText = performSetText(inputNode, encryptedText)

            if (isSetText)
                currentService.serviceScope.launch {
                    delay(50)
                    sendBtnNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            else {
                // 设置密文失败了，弹出toast通知用户
                service?.serviceScope?.launch(Dispatchers.Main) {
                    Toast.makeText(
                        service,
                        service?.getString(R.string.set_text_failed, encryptedText.length),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // 普通点击的发送逻辑 (用于标准模式的短按)
    protected fun doNormalClick() {
        if (!isNodeValid(cachedSendBtnNode)) {
            val root = service?.rootInActiveWindow ?: return
            cachedSendBtnNode = findNodeById(root, sendBtnId)
        }
        cachedSendBtnNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    protected fun findNodeById(
        rootNode: AccessibilityNodeInfo,
        viewId: String,
    ): AccessibilityNodeInfo? {
        return rootNode.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
    }

    /**
     * 使用 ACTION_SET_TEXT 来直接设置文本，这是更安全、更专业的做法。
     * 它不会污染用户的剪贴板！
     * @param nodeInfo 目标节点。
     * @param text 要设置的文本。
     */
    protected fun performSetText(nodeInfo: AccessibilityNodeInfo, text: String): Boolean {
        // 检查节点是否支持“设置文本”这个动作。
        if (nodeInfo.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
            Log.d(tag,"准备设置加密文本到输入框")
            // 1. 创建一个 Bundle (包裹)，用来存放我们要设置的文本。
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )

            // 2. 对节点下达“执行设置文本”的命令，并把装有文本的“包裹”递给它。
            if (!nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.e(tag, "performAction(ACTION_SET_TEXT) 直接返回失败。")
                return false
            }
            // 刷新节点，验证文本内容是否正确更新
            nodeInfo.refresh()
            if (nodeInfo.text.toString() == text) {
                Log.d(tag, "已将加密内容直接设置到节点: $text")
                return true
            } else {
                Log.d(tag, "加密内容设置到textField失败，可能是内容过长: ${text.length}字")
                return false
            }
        } else {
            // 如果节点不支持直接设置文本（比如不可编辑的TextView），我们再考虑其他策略。
            // 比如弹窗提示，或者把解密内容复制到剪贴板（并明确告知用户）。
            Log.d(tag, "节点不支持设置文本。加密内容: $text")
            return false
        }
    }

    /**
     * ✨ 验证缓存节点有效性的“金标准”方法 ✨
     */
    private fun isNodeValid(node: AccessibilityNodeInfo?): Boolean {
        return node?.refresh() ?: false
    }

    /**
     * 处理输入框双击事件逻辑
     */
    private fun handleInputDoubleClick(sourceNode: AccessibilityNodeInfo?){
        val node = sourceNode ?: return
        val currentService = service ?: return
        // 1. 检查被点击的节点是不是我们关心的那个输入框
        //    我们通过比较节点的 viewIdResourceName 来确认它的身份
        if (node.viewIdResourceName == inputId) {
            val currentTime = System.currentTimeMillis()

            // 2. 检查距离上次点击的时间，是否在我们的“双击”阈值之内
            if (currentTime - lastInputClickTime < currentService.doubleClickThreshold) {
                Log.d(tag, "检测到输入框双击事件,准备拉起弹窗")
                //showToast(R.string.double_click_to_send_image)

                // 3. 重置计时器，防止第三次点击也被误判
                lastInputClickTime = 0L
            } else {
                // 如果是第一次点击，或者距离上次点击太久，就只更新时间戳
                lastInputClickTime = currentTime
            }
        }
    }
}