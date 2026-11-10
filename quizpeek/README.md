# QuizPeek

An Android floating "look at the question, show me the answer" helper.
Tap the floating **识** button on any question screen; the answer appears in a draggable
overlay above the app — no app switching, no OCR, no server.

> 一个安卓悬浮识题小工具：任意答题页点一下悬浮的「识」，答案直接贴在屏幕上的浮层里。
> 不截图上传第三方服务器，不需要电脑，不需要自建后端。

---

## 目录

- [它是什么](#它是什么)
- [功能](#功能)
- [工作流程](#工作流程)
- [环境要求](#环境要求)
- [安装与使用](#安装与使用)
- [配置](#配置)
- [兼容性](#兼容性)
- [从源码构建](#从源码构建)
- [已知限制](#已知限制)
- [隐私](#隐私)
- [免责声明](#免责声明)
- [License](#license)

---

## 它是什么

手机上的答题 App（学习通、知到、超星、雨课堂、各种题库 App、在线课程平台…）通常全屏显示，
来回切换 App 查资料很麻烦。

QuizPeek 用一个可拖动的悬浮按钮加一个答案浮层解决这件事：

- 全屏停在题目页
- 点一下悬浮的「识」
- 1～3 秒后，答案浮在题目旁边

它本身不含任何题目数据，也不内置模型。真正的"思考"由你自己填的
[DeepSeek API Key](https://platform.deepseek.com/api_keys) 完成——所以答案质量、费用都取决于你的账号。

---

## 功能

- **悬浮按钮**：常驻其他 App 之上，可拖动，不挡题目
- **一键识题**：截屏 → 多模态模型读题 → 出答案
- **支持图片/公式/表格题**：因为传的是整张截图，不只靠文字
- **答案浮层**：默认屏幕偏下；拖动标题移动、拖 `⤢` 缩放、点 `×` 关闭、内容可滚动
- **排版**：答案大字加粗，解析小字
- **降级方案**：截屏被系统拦时，自动退回「读取控件文字」再问
- **纯本地**：Key 存在 App 私有存储，不经过任何中间服务器
- **无第三方依赖**：不使用 AndroidX / Gradle 运行时，APK 约 40 KB

---

## 工作流程

```
点「识」
   │
   ├─ AccessibilityService.takeScreenshot()  ── 截当前屏（不含自己的浮层）
   │        │ 失败
   │        └─ 读当前窗口控件树文字（降级）
   │
   ├─ JPEG 压缩（最长边 1080，质量 80）
   │
   ├─ POST https://api.deepseek.com/chat/completions
   │      { model, messages:[ { image_url + text } ] }
   │
   └─ 答案贴回悬浮窗
```

一次请求约 1～3 秒，费用按 DeepSeek 官方计费，一次几分钱。

---

## 环境要求

**使用**

- Android 11（API 30）及以上
- 一个 DeepSeek API Key（在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请，需充值）
- 允许安装未知来源应用、允许悬浮窗、开启无障碍服务

**构建（可选，只有改代码才需要）**

- Linux（arm64 或 x86_64）
- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`
- `aapt`（Debian/Ubuntu 可 `apt install aapt`；或直接用 build-tools 里的）
- Python 3（`pack.py` 用来塞 dex 并做 4 字节对齐，替代 zipalign）

---

## 安装与使用

### 1. 安装

下载 [apk/QuizPeek-v2.0.apk](apk/QuizPeek-v2.0.apk)，点开安装。系统提示「允许此来源安装应用」时允许一次。

### 2. 填 Key

打开 **QuizPeek**：

- **DeepSeek API Key**：粘贴 `sk-...`
- **模型**：保持默认 `deepseek-chat`（支持看图，约 2 秒）
- 点 **保存设置**

> `deepseek-reasoner` 目前不接受图片输入，传图会返回空；本项目默认不用它。

### 3. 开权限

按顺序点：

1. **授权悬浮窗** → 系统弹「显示在其他应用上层」→ 允许
2. **开启无障碍** → 列表里找到 **QuizPeek** 打开（截屏靠它）
3. **开始** → 屏幕上出现蓝色「识」圆钮

### 4. 使用

1. 打开答题 App，停在题目页（全屏）
2. 点「识」
3. 浮层显示「截屏中…」→「思考中…」→ 答案
4. 点 `×` 收起

浮层操作：

| 操作 | 方式 |
|---|---|
| 移动 | 按住顶部「答案」那行拖 |
| 缩放 | 拖右上角 `⤢`（宽 190–380dp，高 120–560dp） |
| 关闭 | 点 `×` |
| 滚动 | 内容超出时在框内滑动 |

---

## 配置

| 项 | 位置 | 说明 |
|---|---|---|
| API Key | App 首页输入框 | 存在 `SharedPreferences`（App 私有目录），不上传 |
| 模型 | App 首页输入框 | 默认 `deepseek-chat`；任何支持图像输入的 OpenAI 兼容模型理论上都可换 |
| 接口地址 | 源码常量 `OverlayService.API` | 默认 `https://api.deepseek.com/chat/completions`，可改成自建/中转 |
| 悬浮按钮初始位置 | `OverlayService.setupButton()` | 默认 `(10dp, 320dp)` |
| 浮层默认位置/大小 | `OverlayService.ensurePanel()` | 底部往上 110dp，300×230dp |

日志：`/sdcard/Download/QuizPeek/quizpeek-log.txt`（记录截了多少字节、请求结果、异常）

---

## 兼容性

**通用，不针对具体 App 做适配。** 它只依赖两个系统能力：无障碍截屏 + 悬浮窗。
所以原则上任何**允许截屏**的 App 都能用：

- 学习通（超星）、知到（智慧树）、雨课堂、中国大学 MOOC
- 各类题库/刷题 App、网课平台、在线作业系统
- 浏览器里的网页题目（只要页面能截屏）

**不能用的**：设置了 `FLAG_SECURE` 的页面（部分考试、支付、密码页），截出来是全黑；
此时会降级为读取控件文字，但网页/图片题可能读不到内容。

不同机型（vivo / 小米 / 华为 / OPPO）对后台截屏、悬浮窗的策略不同，遇到问题见
[已知限制](#已知限制) 和日志。

---

## 从源码构建

不需要 Gradle，`build.sh` 直接走 `aapt → javac → d8 → apksigner`：

```sh
export ANDROID_HOME=/path/to/android-sdk
bash build.sh
# 产物：QuizPeek.apk
```

脚本做的事：

1. `aapt package` 打包资源与清单
2. `javac --release 8` 编译（用 `android.jar` 作 classpath）
3. `d8` 转 dex
4. `pack.py` 把 `classes.dex` 塞进 APK，并对未压缩条目做 4 字节对齐（代替 zipalign）
5. 首次构建自动生成 `debug.keystore`，用 `apksigner` 签名

在 x86_64 机器上，如果系统没有 `aapt`，把 `build-tools/34.0.0/aapt` 加进 `PATH` 即可。

目录结构：

```
.
├── AndroidManifest.xml
├── res/
│   ├── values/strings.xml
│   └── xml/peek_a11y.xml        # 无障碍服务声明
├── src/com/dsh/quizpeek/
│   ├── MainActivity.java        # 设置页：Key、模型、权限入口
│   ├── OverlayService.java      # 悬浮按钮、截屏、API 调用、答案浮层
│   ├── PeekA11yService.java     # 截屏 + 读控件文字
│   └── FileLog.java             # 日志
├── build.sh
├── pack.py
└── apk/QuizPeek-v2.0.apk
```

---

## 已知限制

- **依赖外部 API**：没网、没额度、Key 失效都无法作答
- **防截屏页面**：全黑，只能退回读文字
- **整屏截图**：悬浮按钮和答案浮层可能入镜，建议把它拖到不挡选项的位置
- **一次一题**：不会自动翻页、不会提交，需要自己点
- **没有本地 OCR**：所有识别都在云端模型完成，图片不出本机则无法工作
- **答案可能有错**：模型会算错，尤其是公式、多小问、图表题，务必自己核对
- **费用自负**：每次调用按 DeepSeek 官方价格计费

---

## 隐私

- API Key 只存在本机 App 私有存储（`SharedPreferences`），明文保存，请自行保护手机
- 截图只发给 **你自己配置的接口**（默认 `api.deepseek.com`），不经任何中转服务器
- 项目本身**不收集**任何数据、不含统计 SDK、不联网上报
- 日志文件在本机 `Download/QuizPeek/`，只记录字节数和结果摘要，不保存图片
- 卸载 App 即清除 Key 与日志目录（日志需手动删）

---

## 免责声明

> **请在使用前完整阅读。**

1. **仅供学习与自查**。本工具用于课后练习、刷题时快速核对答案、理解题目，不用于任何形式的
   考试、测评、代考、代做作业。
2. **禁止作弊**。在学习通、知到、雨课堂、慕课等平台参加**在线考试、限时测验、计分作业**时
   使用本工具，属于违反学术诚信与平台规则的行为，可能被判定作弊并承担相应后果。
   **本项目作者明确反对并将此类使用视为滥用。**
3. **后果自负**。使用者应自行确认其使用场景是否符合所在学校、机构的规定与当地法律。
   因使用本工具产生的任何处分、成绩作废、账号封禁、费用或法律纠纷，**由使用者自行承担**，
   作者不承担任何责任。
4. **无担保**。软件按"现状"提供，不保证识别准确、不保证服务可用、不保证 API 接口不变。
   模型输出可能错误，请勿将其作为唯一依据。
5. **费用自负**。API 调用产生的费用由使用者自己的账号承担。
6. **合规使用 API**。请遵守 [DeepSeek 服务条款](https://platform.deepseek.com) 与相关平台的用户协议。

---

## License

MIT，见 [LICENSE](LICENSE)。
