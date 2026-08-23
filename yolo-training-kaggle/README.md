# 青云 YOLOv8n 训练指南（Kaggle免费GPU，手机可操作）

## 为什么用Kaggle？

- **完全免费**：每周30小时T4 GPU（比Colab免费版还慷慨）
- **不用翻墙**：kaggle.com国内可访问
- **手机可操作**：手机浏览器登录就能跑
- **一次性投入10分钟**：训练完下载`.tflite`模型，集成到APP后永久本地运行

## 第一步：注册Kaggle（2分钟）

1. 手机浏览器打开 https://www.kaggle.com/account/login
2. 用Google账号或邮箱注册
3. **关键：必须验证手机号**才能用GPU（设置→Phone Verification）
4. 国内手机号可收验证码

## 第二步：创建Notebook（1分钟）

1. 登录后点左上角菜单 → **Create** → **New Notebook**
2. 右侧设置面板：
   - **Accelerator**：选 `GPU T4 x2`（或任一GPU选项）
   - **Persistence**：选 `Files only`
   - **Internet**：打开 `On`
3. 右上角 `Save Version` → 选 `Save & Run All` 

## 第三步：粘贴训练代码（我会提供）

直接把 `train_yolo_kaggle.ipynb` 里的代码按顺序粘贴到Kaggle单元格：
- Cell 1: 安装ultralytics
- Cell 2: 下载Roboflow公开扑克牌数据集
- Cell 3: 训练YOLOv8n（10-15分钟）
- Cell 4: 导出TFLite
- Cell 5: 显示模型下载链接

## 第四步：下载模型

训练完后，在Notebook输出区会有 `.tflite` 文件下载链接（约6MB），手机直接下载，发给我就行。

## 时间预估

| 步骤 | 耗时 |
|---|---|
| 注册+验证手机号 | 2分钟 |
| 创建Notebook+设置 | 1分钟 |
| 粘贴代码+启动训练 | 1分钟 |
| 实际训练（30 epoch） | 12-15分钟 |
| 导出TFLite | 1分钟 |
| **总计** | **约20分钟** |

## 预期效果

- **mAP@0.5 > 95%**（扑克牌52类，数据集24k+张）
- **手机推理 50-80ms**（一加13T骁龙8 Elite）
- **整手Pipeline：5-8秒 → <0.5秒**（截图+识别+决策+点击）
- **完全离线**，不依赖网络，不花API费

## 为什么不用其他方案？

| 方案 | 问题 |
|---|---|
| Google Colab | 需翻墙，免费版经常断 |
| AutoDL租GPU | 要花钱（虽然1块钱），要电脑操作 |
| 本地沙箱训练 | 1核CPU 2G内存，跑不动 |
| 直接下载现成模型 | Roboflow Universe没有直接可用的TFLite，只有.pt权重 |
| **Kaggle** ✅ | 免费、国内可访问、手机可操作、GPU够强 |
