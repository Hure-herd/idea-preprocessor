# IDEA Preprocessor

为使用 ReplayMod Preprocessor 及其衍生版本的多版本 Minecraft Java/Mixin 项目提供代码补全、高亮、跳转和编辑辅助。

- 插件显示名：**Preprocessor Support**
- 插件 ID：`rems.idea.preprocessor`
- 支持的 IDEA 最低内部版本：`253`（2025.3）

## 功能

- 在 `//#if MC...`、`//#elseif MC...` 等条件后补全 `MC`、比较运算符和项目 `createNode` 中声明的点分 Minecraft 版本号；条件可使用嵌套、逻辑运算、`defined(...)` 和范围表达式。
- 在 `//$$` 注释代码中补全 Java 关键字、类、字段、方法、局部变量，以及 Mixin/MixinExtras 注解、属性、目标方法和注入点。补全独立的方法调用时，例如 `ci.c`，可插入 `ci.cancel();`。
- 在 `//$$` 中对类、注解、方法、字段和 Mixin 目标使用 `Ctrl+B` 或 Ctrl+单击，跳转到条件对应版本的源码或依赖。
- 为 `//$$` 代码着色，并为嵌套的 `//#if`、分支和配对的 `//#endif` 显示层级颜色。颜色会适配当前编辑器主题；不依赖 Rainbow Brackets。
- 选中代码后使用浮动工具栏或右键菜单中的 **Wrap with MC Preprocessor**，生成 `//#if`、逐行 `//$$` 和 `//#endif`。选中完整条件块后可使用 **Sync to Main src**，同步到主工程对应的源码文件。
- 在 **Settings → Tools → Preprocessor Support** 中开启换行自动插入 `//$$`。插件会按主版本和当前条件判断是否需要前缀，并要求下方已有配对的 `//#endif`。

例如，在非主版本分支中可以编辑带前缀的 Mixin 代码：

```java
//#if MC>1.21.1
//$$ @Inject(method = "tick", at = @At("HEAD"))
//$$ private void onTick(CallbackInfo ci) {
//$$     ci.cancel();
//$$ }
//#endif
```

## 预处理器版本

输入 `//#` 时，候选取决于项目的 Gradle 配置：

| 项目使用的实现 | 指令候选 |
| --- | --- |
| ReplayMod 原版或其他 fork | `if`、`ifdef`、`elseif`、`else`、`endif`、`disable-remap`、`enable-remap` |
| Gradle 配置包含 `liuyuexiaoyu1` 的六月分支 | 上述指令，另加 `ifndef`、`elif`、`define`、`error`、`warn`、`replace`、`case`、`endcase` |

识别依据是从当前源码目录向上查找 `settings.gradle(.kts)`、`build.gradle(.kts)` 和 `gradle.properties`。插件仍可识别部分扩展语法以提供编辑辅助；候选列表不代表当前项目的 preprocessor 一定能构建这些指令。`//#import` 的补全与跳转仍在插件中，但使用前应确认所用 preprocessor 实现支持该指令。

## 安装与迁移

在 IDEA 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择构建出的 ZIP，安装后重启 IDEA。

旧插件的 ID 是 `rems.idea.mixin-descriptor-completion`。由于新版 ID 已改为 `rems.idea.preprocessor`，IDEA 会将两者视为不同插件。升级前请先卸载旧插件并重启 IDEA，然后安装新版，避免两个插件同时处理补全和按键。

首次打开项目时，请等 Gradle 导入和 IDEA 索引完成；版本感知的补全与跳转还需要对应版本模块的 Minecraft、Mixin 等依赖已经导入。

## 构建

需要 JDK 21，以及可用的 IntelliJ IDEA 安装目录。默认使用 `C:/Program Files/JetBrains/IntelliJ IDEA 2026.1`；安装在其他位置时可通过 `-PideaHome=...` 指定。

在**本仓库根目录**运行：

```powershell
.\gradlew.bat buildPlugin
```

安装包位于 `build/distributions/idea-preprocessor-<版本号>.zip`。也可以从父目录运行：

```powershell
.\idea-preprocessor\gradlew.bat -p idea-preprocessor buildPlugin
```
