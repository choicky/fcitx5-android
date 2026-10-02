## fcitx5-android-fusion-enhanced

这是 `fcitx5-android-fusion-enhanced` 的自构建发布线，包含 fcitx5-chinese-addons 的 **MoQi Auxiliary Filter**（墨奇辅助筛选）。与上游官方构建无隶属关系，请勿当作官方版本。

- fcitx5-android：当前产品开发分支
- fcitx5-chinese-addons：固定提交 `9b3448e6b3889e4281ea39e334c7e5714f8a8b12`
- 墨奇码表：[`gaboolic/moqima-tables`](https://github.com/gaboolic/moqima-tables) @ `6d8ba8f1c57466f358e682baefe11bbd0fe389ab`（MIT），SHA256 `66deab4aaba1285e3c85eb3a364c21bc08db1911b61df8e934f0d006ca7e7923`

## 安装与升级

- 包名 `org.fcitx.fcitx5.android.moqi`，可与官方 Fcitx5 共存；同一发布线之间可**直接覆盖升级**（使用固定的发布签名密钥）。
- 与早前的 pre-release（包名 `.debug`、每次构建随机 debug 密钥）**不互通**：要换到本发布线需先卸载那个测试包。
- 安装后在 系统设置 → 语言和输入法 中启用，并在应用内添加「拼音」。

## 使用墨奇辅助筛选

1. 拼音设置里把 `Auxiliary Filter` 设为 `MoQi`（另有 `Disabled` / `Stroke`）。
2. 输入拼音 → 按反引号 **`** 触发筛选 → 输入墨奇码。
3. 例：`xian` → **`** → `ak`（西 = `ak`）；安 = `bn`，你 = `rx`。
4. Backspace 逐步退码并退出筛选；Escape 退出但保留已选前缀；筛选与部分选择后仍可继续输入并再次触发。

## Local ASR

- FunASR Nano、X-ASR 离线 INT8、X-ASR 960 ms 流式 INT8 均可在 Voice Settings
  中由用户按需下载、启用、管理和使用；debug 与 release 均包含 Local ASR
  runtime 及其 JNI 库。
- 旧 bilingual Zipformer 已移除，且本轮不做旧模型兼容迁移。
- D035 本地回落顺序固定为：X-ASR 离线 → FunASR Nano → X-ASR 流式；每一步只
  选择已启用、完整安装且当前 runtime 可用的模型。
- 本发布由 GitHub Actions 在 debug 检查成功后构建；本轮未以真机验收为发布前置，
  所有者已报告两款 X-ASR 的基础使用及主观效果比较。长语音等未完成验收仍不代表
  已通过。

## 许可

基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)（LGPL-2.1）构建，对应源代码见上方两个 fork 的相应提交/标签。墨奇码表来自 `gaboolic/moqima-tables`（MIT），许可证文本见 [moqima-tables.LICENSE](https://github.com/choicky/fcitx5-chinese-addons/blob/feature/moqi-filter/third_party/moqima-tables.LICENSE)。
