    /**
     * 形状分析区分同色花色 — V2.9.212 连通分量 + 位置评分 + 宽度剖面
     *
     * 核心思路：用大花色符号（中央位置）而非 rank indicator（左上角）做分类。
     *
     * 流程：
     * 1. 建立整块区域二值 mask（内缩10%排除边框）
     * 2. 连通分量标记（8邻域 BFS）
     * 3. 对每个分量计算综合评分：
     *    - 面积 x 填充率 x 方形度 x 位置分 x 左上惩罚
     *    - 位置分：离中心越近越高
     *    - 左上惩罚：rank 文字通常在左上(ncx<0.35, ncy<0.35)，给 0.2 惩罚
     * 4. 选最高分分量，计算归一化宽度剖面 top5 均值
     * 5. 分类：
     *    - 黑色: top5<0.15->尖顶(spade), else->宽顶(club)
     *    - 红色: top5>0.15 或 top25>0.45->两瓣宽顶(heart), else->尖顶(diamond)
     *
     * @param pixels 原始ARGB像素
     * @param w 裁剪区域宽
     * @param h 裁剪区域高
     * @param startY suit分析起始行
     * @param endY suit分析结束行
     * @param maxX suit分析最大列宽
     * @param knownColor "red" / "black" / "unknown"
     * @return Pair<suit, suitSymbol>，如 ("h","...")；失败返回 ("?","?")
     */
    private fun analyzeSuitShape(
        pixels: IntArray, w: Int, h: Int,
        startY: Int, endY: Int, maxX: Int, knownColor: String
    ): Pair<String, String> {
        val regW = maxX
        val regH = endY - startY
        if (regH < 4 || regW < 4) return "?" to "?"

        // --- Step 1: 建立二值 mask（内缩10%排除边框） ---
        val marginX = (regW * 0.10).toInt().coerceAtLeast(2)
        val marginY = (regH * 0.10).toInt().coerceAtLeast(2)
        val innerW = regW - 2 * marginX
        val innerH = regH - 2 * marginY
        if (innerW < 4 || innerH < 4) return "?" to "?"

        val mask = BooleanArray(innerH * innerW)
        for (y in 0 until innerH) {
            for (x in 0 until innerW) {
                val srcIdx = (startY + marginY + y) * w + (marginX + x)
                if (srcIdx >= pixels.size) continue
                val p = pixels[srcIdx]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                mask[y * innerW + x] = when (knownColor) {
                    "red" -> r > 130 && r - g > 50 && r - b > 50
                    "black" -> r < 90 && g < 90 && b < 90
                    else -> (r > 130 && r - g > 50 && r - b > 50) || (r < 90 && g < 90 && b < 90)
                }
            }
        }

        // --- Step 2: 连通分量标记（8邻域 BFS） ---
        val labels = IntArray(innerH * innerW) { -1 }
        val compX = mutableListOf<Int>()
        val compY = mutableListOf<Int>()
        val compBW = mutableListOf<Int>()
        val compBH = mutableListOf<Int>()
        val compArea = mutableListOf<Int>()
        val compSumCx = mutableListOf<Int>()
        val compSumCy = mutableListOf<Int>()

        for (sy in 0 until innerH) {
            for (sx in 0 until innerW) {
                if (!mask[sy * innerW + sx] || labels[sy * innerW + sx] >= 0) continue

                val compId = compX.size
                val queue = ArrayDeque<Int>()
                queue.add(sy * innerW + sx)
                labels[sy * innerW + sx] = compId

                var minX = sx; var maxXc = sx; var minY = sy; var maxYc = sy
                var area = 0; var sumCx = 0; var sumCy = 0

                while (queue.isNotEmpty()) {
                    val pos = queue.removeFirst()
                    val cx = pos % innerW; val cy = pos / innerW
                    area++; sumCx += cx; sumCy += cy
                    if (cx < minX) minX = cx; if (cx > maxXc) maxXc = cx
                    if (cy < minY) minY = cy; if (cy > maxYc) maxYc = cy

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dy == 0 && dx == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx < 0 || nx >= innerW || ny < 0 || ny >= innerH) continue
                            val nPos = ny * innerW + nx
                            if (mask[nPos] && labels[nPos] < 0) {
                                labels[nPos] = compId
                                queue.add(nPos)
                            }
                        }
                    }
                }

                compX.add(minX); compY.add(minY)
                compBW.add(maxXc - minX + 1); compBH.add(maxYc - minY + 1)
                compArea.add(area); compSumCx.add(sumCx); compSumCy.add(sumCy)
            }
        }

        if (compArea.isEmpty()) return "?" to "?"

        val totalArea = innerW * innerH
        val minArea = (totalArea * 0.01).toInt().coerceAtLeast(4)

        // --- Step 3: 综合评分选最佳分量 ---
        var bestIdx = -1; var bestScore = -1.0
        var bestTop5 = 0.0; var bestTop25 = 0.0

        for (i in compArea.indices) {
            val cx = compX[i]; val cy = compY[i]
            val bw = compBW[i]; val bh = compBH[i]
            val area = compArea[i]; val sumCx = compSumCx[i]; val sumCy = compSumCy[i]

            if (area < minArea) continue
            if (bw < 3 || bh < 3) continue

            val aspect = maxOf(bw, bh).toDouble() / minOf(bw, bh)
            if (aspect > 2.5) continue
            if (bw > innerW * 0.70 || bh > innerH * 0.70) continue

            val ncx = (sumCx.toDouble() / area) / innerW
            val ncy = (sumCy.toDouble() / area) / innerH

            val fill = area.toDouble() / (bw * bh)
            val squareness = 1.0 - Math.abs(bw - bh).toDouble() / maxOf(bw, bh)

            val centerDist = Math.sqrt((ncx - 0.5) * (ncx - 0.5) + (ncy - 0.5) * (ncy - 0.5))
            val positionScore = maxOf(0.0, 1.0 - centerDist / 0.5)

            val ulPenalty = if (ncx < 0.35 && ncy < 0.35) 0.2 else 1.0

            val score = area * fill * squareness * positionScore * ulPenalty

            if (score > bestScore) {
                bestScore = score
                bestIdx = i

                // 计算宽度剖面
                val colSums = DoubleArray(bw)
                for (py in cy until cy + bh) {
                    for (px in cx until cx + bw) {
                        if (labels[py * innerW + px] == i) colSums[px - cx]++
                    }
                }
                val maxCol = colSums.maxOrNull() ?: 1.0
                val sorted = colSums.map { it / maxCol }.sorted()
                val top5Count = maxOf(1, (bw * 0.05).toInt())
                val top25Count = maxOf(1, (bw * 0.25).toInt())
                bestTop5 = sorted.takeLast(top5Count).average()
                bestTop25 = sorted.takeLast(top25Count).average()
            }
        }

        if (bestIdx < 0) return "?" to "?"

        // --- Step 4: 宽度剖面分类 ---
        return when (knownColor) {
            "red" -> if (bestTop5 > 0.15 || bestTop25 > 0.45) "h" to "\u2665" else "d" to "\u2666"
            "black" -> if (bestTop5 < 0.15) "s" to "\u2660" else "c" to "\u2663"
            else -> {
                when {
                    bestTop5 > 0.15 || bestTop25 > 0.45 -> "h" to "\u2665"
                    bestTop5 < 0.15 -> "s" to "\u2660"
                    else -> "c" to "\u2663"
                }
            }
        }
    }