# 计算器（HelloApp）

单 Activity 的 Android 计算器，零第三方依赖，界面全部用系统 View 代码搭建（无 XML 布局、无 Compose）。

## 结构

| 文件 | 作用 |
| --- | --- |
| `app/src/main/kotlin/com/xiaoqi/calculator/Calculator.kt` | 表达式引擎：四则、括号、一元负号、后缀百分号；纯 Kotlin，可脱离 Android 编译 |
| `app/src/main/kotlin/com/xiaoqi/calculator/MainActivity.kt` | 界面与按键状态机 |
| `tools/EngineCheck.kt` | 引擎自检（28 条断言），`kotlinc` 直接跑 |
| `design/icon.svg`、`design/icon-round.svg` | 图标设计源文件 |
| `keystore/hello-release.p12` | release 签名密钥（**务必备份**） |

## 构建

```bash
gradle assembleDebug     # 调试包
gradle assembleRelease   # 签名发布包
```

`gradle.properties` 里的 `android.aapt2FromMavenOverride` 指向本机 `build-tools`，换机器要改。
`keystore/keystore.properties` 提供签名口令，不在版本库内。

## 引擎自检

```bash
kotlinc app/src/main/kotlin/com/xiaoqi/calculator/Calculator.kt tools/EngineCheck.kt \
        -include-runtime -d .check/engine.jar
java -jar .check/engine.jar
```

## 历史记录

- 按 `=` 记录「算式 + 结果 + 年月日」；
- 输入中途停手 15 秒，会把半成品算式记一条「停留记录」（结果栏显示预览值或 `未完成`）；
- 顶部横条显示最近一条，点开是历史面板；
- 面板里点一条 → 把该算式放回输入框继续算；
- 长按一条 → 复制 `算式 = 结果`；
- 长按结果区 → 菜单（复制表达式 / 粘贴 / 清空），粘贴会自动过滤非法字符并统一运算符。

## 行为约定

- 屏幕上**只显示用户输入的表达式**，按下 `=` 之后才显示计算结果，历史行显示 `表达式 =`。
- 转屏保留当前表达式与历史行。
- 长按 `⌫` 清空全部。
- 支持外接键盘：数字、`+ - * /`、回车（=）、Esc（C）、Backspace。

## 签名

版本：`2.0-test`（versionCode 3）

`com.xiaoqi.calculator`，自签名证书 `CN=Xiaoqi Calculator`，
SHA-256 `0594d3e9934c4ff424c55eb73f33dcc7ff528f1534bf970a20ca1181481999be`。
