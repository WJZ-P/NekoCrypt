package me.wjz.nekocrypt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.wjz.nekocrypt.NekoCryptApp
import me.wjz.nekocrypt.R
import me.wjz.nekocrypt.SettingKeys
import me.wjz.nekocrypt.data.DataStoreManager
import me.wjz.nekocrypt.hook.observeAsState
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale.getDefault
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
    val dataStoreManager: DataStoreManager by lazy { NekoCryptApp.instance.dataStoreManager }
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    //  当前使用的密文语种
    val ciphertextStyleType: String by scope.observeAsState(flowProvider = {
        dataStoreManager.getSettingFlow(SettingKeys.CIPHERTEXT_STYLE, CiphertextStyleType.NEKO.toString())
    },initialValue = CiphertextStyleType.NEKO.toString())
    //  密文长度词组最小值
    val ciphertextStyleLengthMin by scope.observeAsState(flowProvider = {
        dataStoreManager.getSettingFlow(SettingKeys.CIPHERTEXT_STYLE_LENGTH_MIN, 3)
    },initialValue = 1)
    //  密文长度词组最大值
    val ciphertextStyleLengthMax by scope.observeAsState(flowProvider = {
        dataStoreManager.getSettingFlow(SettingKeys.CIPHERTEXT_STYLE_LENGTH_MAX, 7)
    },initialValue = 1)

    private const val ALGORITHM = "AES"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256 // AES-256
    const val IV_LENGTH_BYTES = 16  // GCM 推荐的IV长度是12，为了该死的兼容改成16
    const val TAG_LENGTH_BITS = 128 // GCM 推荐的认证标签长度

    // 下面是一些映射表
    private val STEALTH_ALPHABET = (0xFE00..0xFE0F).map { it.toChar() }.joinToString("")

    /**
     * 为了高效解码，预先创建一个从“猫语”字符到其在字母表中索引位置的映射。
     * 这是一个关键的性能优化。
     */
    private val STEALTH_CHAR_TO_INDEX_MAP = STEALTH_ALPHABET.withIndex().associate { (index, char) -> char to index }

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
     * 加密一个消息，使用给定的密钥，返回的直接是隐写字符串
     */
    fun encrypt(message: String, key: String): String {
        val plaintextBytes = message.toByteArray(Charsets.UTF_8)
        val encryptedBytes = encryptBytes(plaintextBytes, key)
        return baseNEncode(encryptedBytes)
    }
    // 提供一个重载
    fun encrypt(data: ByteArray, key: String): ByteArray {
        return encryptBytes(data, key)
    }

    //消息解密，智能地从含密文的混合字符串中解密
    fun decrypt(stealthCiphertext: String, key: String): String? {
        val combinedBytes = baseNDecode(stealthCiphertext)
        val decryptedBytes = decryptBytes(combinedBytes, key)
        return decryptedBytes?.toString(Charsets.UTF_8)
    }

    fun decrypt(data: ByteArray, key: String): ByteArray? {
        return decryptBytes(data, key)
    }

    /**
     * ✨ [私有核心] 真正执行加密操作的函数
     */
    private fun encryptBytes(plaintextBytes: ByteArray, key: String): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)    //填充随机内容
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKeyFromString(key), parameterSpec)
        val ciphertextBytes = cipher.doFinal(plaintextBytes)
        // 返回拼接了IV和密文的完整数据
        return iv + ciphertextBytes
    }

    /**
     * ✨ [私有核心] 真正执行解密操作的函数
     */
    private fun decryptBytes(combinedBytes: ByteArray, key: String): ByteArray? {
        try {
            if (combinedBytes.size < IV_LENGTH_BYTES) return null

            val iv = combinedBytes.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertextBytes = combinedBytes.copyOfRange(IV_LENGTH_BYTES, combinedBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, deriveKeyFromString(key), parameterSpec)

            return cipher.doFinal(ciphertextBytes)
        } catch (e: AEADBadTagException) {
            println("解密失败：数据认证失败，可能已被篡改或密钥错误。\n" + e.message)
            return null
        } catch (e: Exception) {
            println("解密时发生未知错误: ${e.message}")
            return null
        }
    }

    /**
     * 判断给定字符串是否包含密文
     */
    fun String.containsCiphertext(): Boolean{
        return this.any { STEALTH_CHAR_TO_INDEX_MAP.containsKey(it) }
    }

    fun deriveKeyFromString(keyString: String): SecretKey {
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

    // -- 通过inputStream和outputStream来流式解密 --
    /**
     * 为 AES/GCM 实现的、真正安全的流式解密方法
     * 它会从输入流中读取加密数据，解密后写入输出流。
     * @param inputStream 包含加密数据的输入流 (必须是已经跳过GIF头的数据)
     * @param outputStream 用于写入解密后数据的输出流
     * @param key 用于解密的密钥
     */
    fun decryptStream(inputStream: InputStream, outputStream: OutputStream, key: String){
        val iv = ByteArray(IV_LENGTH_BYTES)
        require(inputStream.read(iv) == IV_LENGTH_BYTES) {
            "输入流太短，无法读取IV。"
        }

        // 2. 初始化 Cipher
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            init(Cipher.DECRYPT_MODE, deriveKeyFromString(key), spec)
        }

        // 3. 边读边解密边写
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            cipher.update(buffer, 0, read)?.let { outputStream.write(it) }
        }

        // 4. 关键！在所有数据都处理完后，调用 doFinal 来验证“防伪标签”
        try {
            cipher.doFinal()?.let { outputStream.write(it) } // 验证通过后，就doFinal做检验，校验不过抛出错误。
        } catch (e: AEADBadTagException) {
            throw SecurityException("解密失败，数据可能被篡改或密钥错误", e)
        }
    }

    /**
     * ✨ 全新：根据用户设置，为密文应用伪装文本样式。
     *
     * @return 伪装后的、包含随机语言和真实密文的最终字符串。
     */
    fun String.applyCiphertextStyle(): String {
        // 拿到对应枚举类
        val styleType: CiphertextStyleType = CiphertextStyleType.fromName(ciphertextStyleType)
        // 获取该风格下的所有可用词组
        val content = styleType.content

        // 管理最大最小值
        val finalMin = minOf(ciphertextStyleLengthMin, ciphertextStyleLengthMax)
        val finalMax = maxOf(ciphertextStyleLengthMin, ciphertextStyleLengthMax)

        // 盲文模式下，词组数量翻倍
        val actualMin = if (styleType == CiphertextStyleType.BRAILLE) finalMin * 2 else finalMin
        val actualMax = if (styleType == CiphertextStyleType.BRAILLE) finalMax * 2 else finalMax

        // 如果 actualMin 和 actualMax 相等，直接取这个值，否则在范围内取随机数
        val count = if (actualMin == actualMax) actualMin else Random.nextInt(actualMin, actualMax + 1)

        // 先随机挑选词组
        val selectedParts = List(count) { content.random() }

        // 按“词组边界”拆成前后两半，避免切断补充平面字符
        val middleIndex = selectedParts.size / 2
        val prefix = selectedParts.take(middleIndex).joinToString("")
        val suffix = selectedParts.drop(middleIndex).joinToString("")

        return prefix + this + suffix
    }

}

enum class CiphertextStyleType(val displayNameResId:Int,val content:List<String>){
    NEKO(
        displayNameResId = R.string.cipher_style_neko,  // 猫娘语
        content = listOf("嗷呜!", "咕噜~", "喵~", "喵咕~", "喵喵~", "喵?", "喵喵！", "哈！", "喵呜...", "咪咪喵！", "咕咪?")
    ),
    BANGBOO(
        displayNameResId = R.string.cipher_style_bangboo, // 邦布语
        content = listOf("嗯呢...", "哇哒！", "嗯呢！", "嗯呢哒！", "嗯呐呐！", "嗯哒！", "嗯呢呢！")
    ),
    HILICHURLIAN(
        displayNameResId = R.string.cipher_style_Hilichurlian, //丘丘语
        content = listOf("Muhe ye!", "Ye dada!", "Ya yika!", "Biat ye！", "Dala si？", "Yaya ika！", "Mi? Dada!",
            "ye pupu!", "gusha dada!","Dala？","Mosi mita！","Mani ye！","Biat ye！","Todo yo.","tiga mitono!","Biat, gusha!","Unu dada!","Mimi movo!")
    ),
    NIER(
    displayNameResId = R.string.cipher_style_nier, // 尼尔语
    content = listOf(
    "Ee ", "ser ", "les ", "hii ", "san ", "mia ", "ni ", "Escalei ", "lu ", "push ", "to ", "lei ",
    "Schmosh ", "juna ", "wu ", "ria ", "e ", "je ", "cho ", "no ",
    "Nasico ", "whosh ", "pier ", "wa ", "nei ", "Wananba ", "he ", "na ", "qua ", "lei ",
    "Sila ", "schmer ", "ya ", "pi ", "pa ", "lu ", "Un ", "schen ", "ta ", "tii ", "pia ", "pa ", "ke ", "lo ")
    ),
    MANBO(
        displayNameResId = R.string.cipher_style_manbo, //  曼波！
        content = listOf("曼波~","哈吉米~","哈吉米咩那咩路多~","曼波!","曼波...","欧码叽哩，曼波！","叮咚鸡！","哈压库！","哈压库~","哈吉米！","哦耶~","duang~")
    ),
    BRAILLE(
        displayNameResId = R.string.cipher_style_braille, // 盲文点阵
        content = (0x2800..0x28FF).map { it.toChar().toString() } // U+2800 ~ U+28FF，共 256 个盲文 Unicode
    ),
    MAGICSPELL(
        displayNameResId = R.string.cipher_style_magicspell, // 魔法咒语
        content = listOf(
            "𝓐 ", "𝓔 ", "𝓘 ", "𝓞 ", "𝓤 ",
            "𝓓𝓲 ", "𝓘𝓸 ", "𝓜𝓮 ", "𝓣𝓮 ", "𝓣𝓾 ",
            "𝓛𝓾𝔁 ", "𝓝𝓸𝔁 ", "𝓟𝓪𝔁 ", "𝓢𝓸𝓵 ", "𝓡𝓸𝓼 ",
            "𝓐𝓾𝓻𝓪 ", "𝓛𝓾𝓷𝓪 ", "𝓡𝓸𝓼𝓪 ", "𝓐𝓶𝓸𝓻 ", "𝓘𝓻𝓲𝓼 ",
            "𝓐𝓷𝓲𝓶𝓪 ", "𝓘𝓰𝓷𝓲𝓼 ", "𝓤𝓶𝓫𝓻𝓪 ", "𝓝𝓲𝓽𝓸𝓻 ", "𝓢𝓲𝓭𝓾𝓼 ",
            "𝓐𝓾𝓻𝓸𝓻𝓪 ", "𝓒𝓪𝓮𝓵𝓾𝓶 ", "𝓢𝓽𝓮𝓵𝓵𝓪 ", "𝓥𝓮𝓼𝓹𝓮𝓻 ", "𝓝𝓮𝓬𝓽𝓪𝓻 ",
            "𝓐𝓻𝓬𝓪𝓷𝓾𝓶 ", "𝓓𝓾𝓵𝓬𝓮𝓭𝓸 ", "𝓛𝓪𝓬𝓻𝓲𝓶𝓪 ", "𝓛𝓾𝓬𝓮𝓻𝓷𝓪 ", "𝓥𝓸𝓵𝓾𝓬𝓮𝓻 ",
            "𝓥𝓮𝓷𝓾𝓼𝓽𝓪𝓼 ", "𝓒𝓵𝓪𝓻𝓲𝓽𝓪𝓼 ", "𝓢𝓲𝓭𝓮𝓻𝓮𝓾𝓼 ", "𝓥𝓸𝓵𝓾𝓬𝓻𝓲𝓼 ", "𝓢𝓾𝓪𝓿𝓲𝓽𝓪𝓼 ",
            "𝓢𝓮𝓻𝓮𝓷𝓲𝓽𝓪𝓼 ", "𝓢𝓲𝓵𝓮𝓷𝓽𝓲𝓾𝓶 ", "𝓜𝓲𝓻𝓪𝓬𝓾𝓵𝓾𝓶 ", "𝓒𝓪𝓮𝓵𝓲𝓬𝓸𝓵𝓪 ", "𝓒𝓪𝓮𝓵𝓮𝓼𝓽𝓲𝓼 ",
            "𝓐𝓮𝓽𝓮𝓻𝓷𝓲𝓽𝓪𝓼 ", "𝓒𝓸𝓻𝓾𝓼𝓬𝓪𝓽𝓲𝓸 ", "𝓛𝓲𝓺𝓾𝓮𝓼𝓬𝓮𝓻𝓮 ", "𝓜𝓮𝓵𝓵𝓲𝓯𝓵𝓾𝓾𝓼 ", "𝓕𝓻𝓪𝓰𝓻𝓪𝓷𝓽𝓲𝓪 ")
    );
    companion object{
        //  辅助函数
        fun fromName(name:String): CiphertextStyleType{
            return entries.find { it.name == name.uppercase(getDefault()) } ?:NEKO
        }
    }
}