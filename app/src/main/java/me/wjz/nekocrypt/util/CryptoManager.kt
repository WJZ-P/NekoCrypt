package me.wjz.nekocrypt.util

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 加密工具类，有相关的加密算法。
 */
object CryptoManager {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256 // AES-256
    private const val IV_LENGTH_BYTES = 16  // GCM 推荐的IV长度，为了该死的兼容改成16
    private const val TAG_LENGTH_BITS = 128 // GCM 推荐的认证标签长度

    // 下面是一些映射表
    private val STEALTH_ALPHABET = (0xFE00..0xFE0F).map { it.toChar() }.joinToString("")

    /**
     * 为了高效解码，预先创建一个从“猫语”字符到其在字母表中索引位置的映射。
     * 这是一个关键的性能优化。
     */
    private val STEALTH_CHAR_TO_INDEX_MAP =
        STEALTH_ALPHABET.withIndex().associate { (index, char) -> char to index }


// --- 猫语短语库 (分类版) ---

    /**
     * 猫娘的内心活动，用括号包裹，显得很可爱。
     */
    private val NEKO_INNER_THOUGHTS = listOf(
        "(探头)",
        "(打哈欠)",
        "(盯~)",
        "(摇尾巴)",
        "(伸懒腰)",
        "(舔爪爪)",
        "(歪头)",
        "(耳朵动了动)",
        "(用头蹭蹭)",
        "(踩奶)",
        "(缩成一团)"
    )

    /**
     * 可爱的颜文字 (Kaomoji) 列表。
     */
    private val NEKO_KAOMOJI = listOf(
        "ฅ●ω●ฅ",
        "(>^ω^<)",
        "(=´ω`=)",
        "(/ω＼)",
        "(´・ω・`)",
        "(Ф∀Ф)",
        "(๑•̀ㅂ•́)و✧"
    )

    /**
     * 纯粹的猫咪叫声和拟声词。
     */
    private val NEKO_SOUNDS = listOf(
        "喵~",
        "喵呜！",
        "嗷呜！",
        "嗷呜~",
        "咪~",
        "喵！",
        "喵？",
        "呼噜噜...",
        "咕噜咕噜~",
        "蹭蹭~",
        "喵喵~"
    )

    /**
     * 将所有类型的猫语列表聚合到一个主列表中，方便随机选择类型。
     */
    private val ALL_NEKO_PHRASE_TYPES = listOf(
        NEKO_INNER_THOUGHTS,
        NEKO_KAOMOJI,
        NEKO_SOUNDS
    )


    /**
     * 生成一个符合 AES-256 要求的随机密钥。
     *
     * @return 一个 SecretKey 对象，包含了256位的密钥数据。
     */
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_SIZE_BITS)
        return keyGenerator.generateKey()
    }


    /**
     * 为字符串追加一段结构化、有规则的可爱“猫咪话语”后缀。
     *
     * 生成规则：
     * 1. 必须包含固定数量的【猫咪叫声】。
     * 2. 随机包含 0-2 个不重复的【内心活动】，并保证它们不会出现在开头，且被【猫咪叫声】隔开。
     * 3. 随机决定是否在末尾追加一个【颜文字】。
     *
     * @receiver 调用此函数的原始字符串。
     * @return 附加了猫咪话语的新字符串。
     */
    fun String.appendNekoTalk(): String {
        // --- 1. 决定本次生成的组件数量 ---
        val soundCount = 2 // 写死包含2个叫声
        val thoughtCount = Random.nextInt(0, 3) // 随机包含 0, 1, 或 2 个内心活动

        // --- 2. 从库中随机挑选出本次要使用的具体短语 ---
        val soundsToUse = (1..soundCount).map { NEKO_SOUNDS.random() }.toMutableList()
        val thoughtsToUse = NEKO_INNER_THOUGHTS.shuffled().take(thoughtCount)


        // --- 3. 核心逻辑：将【内心活动】插入到【叫声】的间隙中 ---
        // 【关键修改】为了避免内心活动出现在开头，插入点从 1 开始 (即第一个叫声之后)。
        // 这样就保证了第一个元素永远是“叫声”。
        val availableSlots = (1..soundsToUse.size).toMutableList()
        availableSlots.shuffle()

        thoughtsToUse.forEach { thought ->
            if (availableSlots.isNotEmpty()) {
                val insertionIndex = availableSlots.removeAt(0)
                soundsToUse.add(insertionIndex, thought)
            }
        }
        val middleParts = soundsToUse


        // --- 4. 构建最终的后缀字符串 ---
        val fullNekoTalk = buildString {
            middleParts.forEach { part ->
                append(part)
            }

            // 【关键修改】随机决定是否在末尾追加颜文字 (这里设置为 60% 概率)。
            if (Random.nextInt(1, 11) <= 6) {
                val kaomojiToEnd = NEKO_KAOMOJI.random()
                append(kaomojiToEnd)
            }
        }

        val middleIndex = fullNekoTalk.length / 2
        return fullNekoTalk.substring(0, middleIndex) + this + fullNekoTalk.substring(middleIndex)
    }

    /**
     * 加密一个消息，使用给定的密钥，返回的直接是隐写字符串
     */
    fun encrypt(message: String, key: String): String {
        //先把明文字符串转成字节数组
        val plaintextBytes = message.toByteArray()
        //生成随机的iv
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        //创建加密器
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)//生成GCM所需数据
        cipher.init(Cipher.ENCRYPT_MODE, deriveKeyFromString(key), parameterSpec)
        val ciphertextBytes = cipher.doFinal(plaintextBytes)
        //拼接iv和密文
        val combinedBytes = iv + ciphertextBytes
        return baseNEncode(combinedBytes)
    }

    //消息解密，智能地从含密文的混合字符串中解密
    fun decrypt(stealthCiphertext: String, key: String): String? { // 返回值改为可空的 String?
        try {
            // 直接将隐写字符串进行 BaseN 解码
            val combinedBytes = baseNDecode(stealthCiphertext)
            if (combinedBytes.isEmpty() || combinedBytes.size < IV_LENGTH_BYTES) return null

            val iv = combinedBytes.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertextBytes = combinedBytes.copyOfRange(IV_LENGTH_BYTES, combinedBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, deriveKeyFromString(key), parameterSpec)

            // 认证校验在这里隐式发生！
            // 如果密文或IV被篡改，或者密钥错误，doFinal会抛出 AEADBadTagException。
            // 这就是GCM模式的认证功能。
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            return String(decryptedBytes)
        } catch (e: AEADBadTagException) {
            // 明确捕获认证标签错误的异常。
            // 这意味着数据100%被篡改过，或者使用的密钥是错误的。
            // 在这种情况下，我们不应该让程序崩溃，而是应该返回一个null来表示解密失败。
            println("解密失败：数据认证失败，可能已被篡改或密钥错误。\n" + e.message)
            return null
        } catch (e: Exception) {
            // 捕获其他可能的异常，例如格式错误等。
            println("解密时发生未知错误: ${e.message}")
            return null
        }
    }

    /**
     * 判断给定字符串是否包含密文
     */
    fun containsCiphertext(input: String): Boolean {
        return input.any { STEALTH_CHAR_TO_INDEX_MAP.containsKey(it) }
    }

    private fun deriveKeyFromString(keyString: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(keyString.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    // -----------------关键的baseN方法---------------------

    /**
     * 将字节数组编码为我们自定义的 BaseN 字符串。
     * 算法核心：通过大数运算，将 Base256 的数据转换为 BaseN。
     * @param data 原始二进制数据。
     * @return 编码后的“猫语”字符串。
     */
    private fun baseNEncode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        // 使用 BigInteger 来处理任意长度的二进制数据，避免溢出。
        // 构造函数 `BigInteger(1, data)` 确保数字被解释为正数。
        var bigInt = BigInteger(1, data)
        val base = BigInteger.valueOf(STEALTH_ALPHABET.length.toLong())
        val builder = StringBuilder()
        while (bigInt > BigInteger.ZERO) {
            // 除基取余法
            val (quotient, remainder) = bigInt.divideAndRemainder(base)
            bigInt = quotient
            builder.append(STEALTH_ALPHABET[remainder.toInt()])
        }
        // 因为是从低位开始添加的，所以需要反转得到正确的顺序
        return builder.reverse().toString()
    }

    /**
     * 将我们自定义的 BaseN 字符串解码回字节数组。
     * 算法核心：通过大数运算，将 BaseN 的数据转换回 Base256。
     * @param encodedString 编码后的“猫语”字符串，可能混杂有其他字符。
     * @return 原始二进制数据。
     */
    private fun baseNDecode(encodedString: String): ByteArray {
        var bigInt = BigInteger.ZERO
        val base = BigInteger.valueOf(STEALTH_ALPHABET.length.toLong())
        // 遍历字符串，只处理在“猫语字典”中存在的字符
        // 乘基加权法。
        encodedString.forEach { char ->
            val index = STEALTH_CHAR_TO_INDEX_MAP[char]
            if (index != null) {
                // 核心算法: result = result * base + index
                bigInt = bigInt.multiply(base).add(BigInteger.valueOf(index.toLong()))
            }
        }
        // 如果解码结果为0，直接返回空数组
        if (bigInt == BigInteger.ZERO) return ByteArray(0)

        // BigInteger.toByteArray() 可能会在开头添加一个0字节来表示正数，我们需要去掉它
        val bytes = bigInt.toByteArray()
        return if (bytes[0].toInt() == 0) {
            bytes.copyOfRange(1, bytes.size)
        } else { bytes }
    }
}