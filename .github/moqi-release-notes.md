## fcitx5-android-fusion-enhanced

这是 `fcitx5-android-fusion-enhanced` 的自构建发布线，包含 fcitx5-chinese-addons 的 **MoQi Auxiliary Filter**（墨奇辅助筛选）。与上游官方构建无隶属关系，请勿当作官方版本。

- fcitx5-android：当前产品开发分支
- fcitx5-chinese-addons：固定提交 `2145c4bf1cff8c62112b9c38db4e6032a190b280`
- 墨奇码表：[`gaboolic/moqima-tables`](https://github.com/gaboolic/moqima-tables) @ `6d8ba8f1c57466f358e682baefe11bbd0fe389ab`（MIT），SHA256 `66deab4aaba1285e3c85eb3a364c21bc08db1911b61df8e934f0d006ca7e7923`

## 安装与升级

- 包名 `org.fcitx.fcitx5.android.fusionenhanced`，可与官方 Fcitx5 共存；同一发布线之间可**直接覆盖升级**（使用固定的发布签名密钥）。
- 与更早的 `.moqi` 发布线**不互通**：本产品线已改用全新包名，从旧 `.moqi` 升级到本线需先卸载旧包再全新安装，不做偏好/数据迁移。
- 与早前的 pre-release（包名 `.debug`、每次构建随机 debug 密钥）**不互通**：要换到本发布线需先卸载那个测试包。
- 安装后在 系统设置 → 语言和输入法 中启用，并在应用内添加「拼音」。

## 使用墨奇辅助筛选

1. 拼音设置里把 `Auxiliary Filter` 设为 `MoQi`（另有 `Disabled` / `Stroke`）。
2. 输入拼音 → 按反引号 **`** 触发筛选 → 输入墨奇码。
3. 例：`xian` → **`** → `ak`（西 = `ak`）；安 = `bn`，你 = `rx`。
4. 墨奇筛选在**整串拼音内持续生效**：部分选词（编码后选词、数字键、鼠标 / 触摸点选）之后**无需再按** **`**，可直接继续输入下一段的墨奇码；整串选完上屏后自动退出筛选。退出方式：墨奇码缓冲为空时按一次退格（Backspace）退出筛选，已选前缀保留，之后可继续输入普通拼音（再按一次退格是普通退格，反选上一个已选字）；使用实体键盘时也可按 Escape 退出。

## Local ASR

- FunASR Nano、X-ASR 离线 INT8、X-ASR 960 ms 流式 INT8 均可在 Voice Settings
  中由用户按需下载、启用、管理和使用；debug 与 release 均包含 Local ASR
  runtime 及其 JNI 库。
- 旧 bilingual Zipformer 已移除，且本轮不做旧模型兼容迁移。
- D035 本地回落顺序固定为：X-ASR 离线 → X-ASR 960 ms 流式 → FunASR Nano；每一步只
  选择已启用、完整安装且当前 runtime 可用的模型。
- 本发布使用既定的 Fusion 发布签名身份；真机行为（含中英混输、Auxiliary Filter、
  Local ASR 等）的验收由所有者在安装本 APK 后完成。未完成验收不代表已通过；真机
  验收是否作为发布前置条件，按该次发布的所有者授权执行。

## 许可

基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)（LGPL-2.1）构建，对应源代码见上方两个 fork 的相应提交/标签。墨奇码表来自 `gaboolic/moqima-tables`（MIT），许可证文本见 [moqima-tables.LICENSE](https://github.com/choicky/fcitx5-chinese-addons/blob/feature/moqi-filter/third_party/moqima-tables.LICENSE)。

## 本次更新（v0.1.3-fusion.13 → v0.1.3-fusion.14）

- 共享引擎固定到 `2145c4bf1cff8c62112b9c38db4e6032a190b280`（与 fusion-windows
  使用的共享源一致）。共享引擎测试 `testmixedpipeline` 覆盖「中文之后接规范英文词」
  的排序约束：旧谓词下失败，该提交下通过。英文词库、MoQi 码表、哈希与许可文件未改。
- X-ASR 960 ms 流式：停止时补两个 960 ms 静音块（共 1920 ms，原为一个），修复较长
  双句（约 16.2 s）末尾字符被截断的问题；流式解码改为 modified_beam_search（4 路径）。
- 主机固定语料（7 段）对照：以**离线 X-ASR 输出为参照**（不是人工真值）的平均字错率
  由 0.0767 降至 0.0584。这是与参照输出的一致度变化，不构成准确率提升的证明；真机
  质量、延迟与生命周期行为仍待验收。
- 流式模型本身仍经常不输出句末标点（7 段中 1 段带句末标点）；这是导出模型的行为，
  应用不补句号、不猜问号。需要句末标点时请选用 X-ASR 离线。
- FunASR Nano：模型文件、哈希与适配参数未改；30 s 保护上限（D081）保持不变。
  所有者的质量疑虑仍未解决：主机侧未复现回归，真机体验尚未验证。
- versionCode 182 → 192（arm64-v8a），可在 v0.1.3-fusion.13 上直接覆盖升级。
- 2026-10-25：本版本由所有者授权直接发布为正式 Release，未等待真机验收；该授权仅
  适用于本版本，不改变常规发布政策。真机验收仍未完成。
