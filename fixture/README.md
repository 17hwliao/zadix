# Zadix Android 测试样本

fixture 是独立的测试 APK，无网络权限，也不依赖 Zadix 的实现。包名为
`com.ahuamiao.adprotection.fixture`，版本号为 1，所有场景都从
`.FixtureActivity` 打开。相同入口再次启动时重置样本和计数。

| `scenario` | 内容 | 预期观察 |
| --- | --- | --- |
| `ad` | 唯一广告标记和关闭按钮 | 实时服务运行时出现 `result_auto`，无障碍动作计数为 1 |
| `normal` | 存在相同 ID 的按钮，没有广告标记 | 两个动作计数保持为 0 |
| `sensitive` | 广告标记和关闭按钮同时出现，但有登录确认标记 | 两个动作计数保持为 0 |
| `ambiguous` | 广告标记搭配两个相同 ID 的关闭按钮 | 两个动作计数保持为 0 |
| `repeat` | 同一个广告和节点连续发出内容变化事件，点击后仍保留 1.1 秒 | `result_auto`，无障碍动作计数仍为 1 |
| `switch` | 原广告窗口前打开另一个窗口，新窗口重复使用广告标记和按钮 ID | 先暂停服务，等 `result_switch` 出现后再恢复。新窗口的动作计数保持为 0。待提交旧候选切窗的竞态另由可控时序测试覆盖 |
| `countdown` | 有广告标记和关闭按钮，倒计时后自动进入结果页 | 暂停实时服务并等待 `result_natural`，两个动作计数为 0 |
| `manual` | 有广告标记和关闭按钮，等待物理触摸 | 暂停实时服务并手动触摸「跳过」，出现 `result_manual`，手动动作计数为 1 |

对 `switch` 和 `countdown` 可传入 `--ei delay_ms 400` 一类参数调整延时。结果页及测试页均通过
`action_count` 显示 `accessibility=N, manual=N`。拒绝样本收到关闭动作时会显示 `result_unexpected`。

样本在按钮的 `performAccessibilityAction(ACTION_CLICK)` 入口记录节点动作，正常触摸走常规点击回调，
倒计时走独立路径。隔离模拟器上仅启用被测自动点击服务，才能把节点动作归因到它。该方法不用于给真实 App 的点击事件归因。

从仓库根目录构建 `:fixture:assembleDebug`。仅在明确选中的 Android 模拟器运行下列命令。

```powershell
$serial = 'emulator-5554'
if ($serial -notmatch '^emulator-\d+$') { throw '仅支持已选中的本地模拟器' }
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$isEmulator = & $adb -s $serial shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or "$isEmulator".Trim() -ne '1') { throw '无法确认模拟器' }
& $adb -s $serial install -r fixture/build/outputs/apk/debug/fixture-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'fixture 安装失败' }
& $adb -s $serial shell am start -W -n com.ahuamiao.adprotection.fixture/.FixtureActivity --es scenario ad
```

主 APK 也安装在同一模拟器后，从系统无障碍设置启用「Zadix · 实时跳过」，并从主 App 添加磁贴。
然后在仓库根运行下列命令。脚本从独立窗口启动每个场景，避免上一场景的窗口状态影响本场。

```powershell
python fixture/emulator_smoke.py --serial emulator-5554
```

脚本通过 App 和磁贴的界面操作切换服务，读取样本动作计数和结果 ID。它只接受明确指定的本地模拟器，
并用系统属性核验设备。系统授权由模拟器的正常设置流程完成。
