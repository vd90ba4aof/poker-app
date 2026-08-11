
    /**
     * 形状分析区分同色花色 — V3.4 锚定法（豪哥方案）
     *
     * 原理：不比较 ♥ vs ♦ 谁分高，只算目标特征达标分。
     * - 红色：只算 ♦ 的分（居中对称），达标→♦，不达标→♥
     * - 黑色：只算 ♠ 的分（顶部分散+底部重），达标→♠，不达标→♣
     *
     * 颜色判定（V3.2）：纯色像素统计，排除绿色背景干扰
     *
     * @param pixels 原始ARGB像素
     * @param w 裁剪区域宽
     * @param h 裁剪区域高
     * @param startY suit分析起始行
     * @param endY suit分析结束行
     * @param maxX suit分析最大列宽
     * @param knownColor "red" / "black" / "unknown"
     * @return Pair<suit, suitSymbol>，如 ("h","♥")；失败返回 ("?","?")
     */
    private fun analyzeSuitShape(
        pixels: IntArray, w: Int, h: Int,
        startY: Int, endY: Int, maxX: Int, knownColor: String
    ): Pair<String, String> {
        val regW = maxX
        val regH = endY - startY
        if (regH < 4 || regW < 4) return "?" to "?"

        // --- Step 1: V3.2 颜色判定（纯色像素统计，排除绿色背景） ---
        val isRed = if (knownColor == "red") true
                    else if (knownColor == "black") false
                    else {
                        var redPx = 0; var blackPx = 0
                        for (y in startY until endY) {
                            for (x in 0 until regW) {
                                val idx = y * w + x
                                if (idx < 0 || idx >= pixels.size) continue
                                val p = pixels[idx]
                                val cr = Color.red(p); val cg = Color.green(p); val cb = Color.blue(p)
                                if (cr > 130 && cr - cg > 45 && cr - cb > 45) redPx++
                                else if (cr < 70 && cg < 70 && cb < 70 && kotlin.math.abs(cg - cr) < 30) blackPx++
                            }
                        }
                        redPx > blackPx * 0.4f
                    }

        // --- Step 2: 建立二值 mask + 行宽度剖面 ---
        val mask = BooleanArray(regH * regW)
        val rowWidths = IntArray(regH)
        for (y in 0 until regH) {
            for (x in 0 until regW) {
                val idx = (startY + y) * w + x
                if (idx < 0 || idx >= pixels.size) continue
                val p = pixels[idx]
                val cr = Color.red(p); val cg = Color.green(p); val cb = Color.blue(p)
                val hit = if (isRed)
                    (cr > 130 && cr - cg > 45 && cr - cb > 45)
                else
                    (cr < 70 && cg < 70 && cb < 70 && kotlin.math.abs(cg - cr) < 30)
                if (hit) {
                    mask[y * regW + x] = true
                    rowWidths[y]++
                }
            }
        }

        val totalPx = rowWidths.sum()
        if (totalPx < 5) return "?" to "?"

        // --- Step 3: 提取关键特征 ---
        // widest row position
        var widestRow = 0; var maxW = 0
        for (y in 0 until regH) { if (rowWidths[y] > maxW) { maxW = rowWidths[y]; widestRow = y } }
        val wp = widestRow.toFloat() / regH  // 最宽行归一化位置

        // comY 重心
        var sumWeightedY = 0
        for (y in 0 until regH) sumWeightedY += y * rowWidths[y]
        val comY = (sumWeightedY.toFloat() / totalPx) / regH

        // 上下半总像素
        val half = regH / 2
        var ts = 0; var bs = 0
        for (y in 0 until half) ts += rowWidths[y]
        for (y in half until regH) bs += rowWidths[y]

        // 顶部x标准差
        val topXStd = computeTopXStd(mask, regW, regH)

        // 连通分量数
        val compCount = countConnectedComponents(mask, regW, regH)

        // --- Step 4: V3.4 二选一锚定法 ---
        if (isRed) {
            // ♦ DIAMOND 锚定: 最宽行居中 + 上下对称
            var diamondScore = 0.0
            if (wp > 0.50 && wp < 0.80) diamondScore += 4.0
            if (comY > 0.40 && comY < 0.62) diamondScore += 1.5
            if (totalPx > 0 && kotlin.math.abs(ts - bs).toFloat() / totalPx < 0.35) diamondScore += 1.0
            return if (diamondScore > 3.5)
                ("d" to "\u2666") else ("h" to "\u2665")
        } else {
            //  SPADE 锚定: 顶部x分散 + 底部重 + 碎片多
            var spadeScore = 0.0
            if (topXStd > 14) spadeScore += 4.0
            else if (topXStd > 10) spadeScore += 2.0
            if (wp > 0.65) spadeScore += 2.0
            if (bs > ts) spadeScore += 1.0
            if (compCount > 6) spadeScore += 1.5
            return if (spadeScore > 3.5)
                ("s" to "\u2660") else ("c" to "\u2663")
        }
    }

    /** 计算 mask 顶部 25% 区域的 x 坐标标准差 */
    private fun computeTopXStd(mask: BooleanArray, regW: Int, regH: Int): Float {
        val topEnd = (regH * 0.25).toInt()
        val xs = mutableListOf<Float>()
        for (y in 0 until minOf(topEnd, regH)) {
            for (x in 0 until regW) {
                if (mask[y * regW + x]) xs.add(x.toFloat())
            }
        }
        if (xs.size < 3) return 0f
        val mean = xs.average()
        val variance = xs.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance).toFloat()
    }

    /** 计算 mask 中连通分量数量（4邻域 BFS，轻量版） */
    private fun countConnectedComponents(mask: BooleanArray, regW: Int, regH: Int): Int {
        val visited = BooleanArray(regW * regH)
        var count = 0
        val queue = ArrayDeque<Int>()
        for (sy in 0 until regH) {
            for (sx in 0 until regW) {
                val startIdx = sy * regW + sx
                if (!mask[startIdx] || visited[startIdx]) continue
                count++
                queue.add(startIdx)
                visited[startIdx] = true
                while (queue.isNotEmpty()) {
                    val pos = queue.removeFirst()
                    val cx = pos % regW; val cy = pos / regW
                    for ((dy, dx) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx < 0 || nx >= regW || ny < 0 || ny >= regH) continue
                        val nPos = ny * regW + nx
                        if (mask[nPos] && !visited[nPos]) {
                            visited[nPos] = true
                            queue.add(nPos)
                        }
                    }
                }
            }
        }
        return count
    }
