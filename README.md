# Mixin Descriptor Completion

为 Java Mixin 注解中的 JVM 描述符提供补全。

IDEA 插件概览使用结构化中文说明，介绍版本感知补全、跳转、预处理指令和编辑辅助；“最近变化”页同步记录当前版本的主要更新。

`//$$` 后的 Java 代码使用 IDEA 当前 Java 配色方案着色：关键字、注解、类型、字段、方法、字符串和数字沿用主题颜色，未分类的普通标识符保持注释灰色。

注解的 `@` 与名称使用相同颜色；括号、逗号、点、等号、分号以及未分类内容保持注释灰色，不使用主题中的白色标点颜色。

类型名、限定类名和普通方法调用也保持灰色；方法声明使用方法颜色，字段、常量和注解属性使用字段颜色。预处理条件中，指令、`MC`、比较运算符和版本号使用不同的主题颜色。

`if`、`//#` 和 `//$$` 使用灰粉红色；普通类型名、限定类名、普通调用、括号、点、逗号、等号和分号使用主题适配的浅灰色。

`method`、`at`、`value`、`target`、`cancellable` 等注解属性名与普通调用使用相同的浅灰色；字段和常量仍使用暗紫色。

同步编辑器着色层会在文件打开和每次输入时立即应用分支颜色，后台语义分析完成前不再先显示整段灰色。

选中 Java 代码后，浮动工具栏会显示 `Wrap with MC Preprocessor` 按钮。输入例如 `MC>=12101` 的条件后，插件会保留原缩进，自动添加首尾 `//#if`、`//#endif`，并为选中代码的每一行添加 `//$$`。

整套预处理分支配色使用较低亮度的灰、黄、蓝、绿、紫和灰粉红色，与正常 Java 代码保持明显视觉区别。

支持：

- 完整识别默认 ReplayMod Preprocessor 指令：`if`、`ifdef`、`ifndef`、`elseif`、`elif`、`else`、`endif`、`define`、`error`、`warn`、`replace`、`case`、`endcase`、`import`、`disable-remap` 和 `enable-remap`。
- 条件解析支持 `&&`、`||`、`!`、括号、`defined(...)`、`//#define` 别名、点分版本号、简写比较、`in A..B`、`in [A, B]` 和 `not in`。
- `//?` case 分支、`//#replace` 替换代码、`/*$$ ... $$*/` 多行代码和行内 `/*#case*/` / `/*?...*/` 使用与 `//$$` 相同的 Java 编辑辅助和分支配色。
- `//#import` 支持条件版本类名补全、完整限定名插入及跳转；实际构建仍要求项目使用实现了该指令的 preprocessor 版本。
- 版本节点可声明在 `build.gradle`、`build.gradle.kts`、`settings.gradle` 或 `settings.gradle.kts`，同步功能识别 `mainProjectFile` 与 `mainProjectFileRel`。

- `@Inject`、`@WrapOperation` 等注解的 `method = "..."`：列出 `@Mixin` 目标类的方法并只插入方法名，描述符仅用于候选提示。
- `@At(target = "...")`：分析 `method` 指向的目标方法，列出其中实际调用的方法并插入完整 owner、方法名和描述符。
- ReplayMod 预处理器的 `//$$` 行：可直接在被注释的 `method = ""` 和 `target = ""` 中补全。
- 在上述 `//$$` 字符串内输入时自动弹出候选，无需手动按 `Ctrl+Space`。
- 根据最近的 `//#if MC...` / `//#elseif MC...` 条件选择对应的版本模块；例如 `MC>12101` 会在当前项目中选择 `1.21.4`。
- `//$$` 中完整的 `method` 和 `target` 支持 `Ctrl+B` 与 Ctrl 点击，跳转到所选版本模块的方法。
- `//$$` 中的 `类名.字段` 和 `类名.方法` 也支持跨版本跳转及快速文档，例如 `REMSSettings.pearlnotloadingchunk`。
- `//$$ @At(...)` 中的注入点代码可跳转到条件版本依赖中以 `InjectionPoint.AtCode` 声明它的真实实现类。
- `//$$ @At(value = "...")` 从条件版本的 Mixin、MixinExtras 及其他扩展依赖中动态发现全部 `InjectionPoint` 子类及其 `AtCode`。
- `//$$ @Shadow` 注解可跳转到 Mixin 注解类；Shadow 字段和方法声明可跳转到所选版本目标类中的真实成员。
- MixinExtras 注解按短名称在所选版本模块中动态解析，包括 `WrapOperation`、`ModifyExpressionValue`、`WrapWithCondition`、`Local`、`Share`、`Definition` 和 `Expression`；`MIXINEXTRAS:EXPRESSION` 可跳转到其注入点实现。
- 可在 `Settings > Tools > Mixin Descriptor Completion` 开关换行自动前缀；启用后，在 `//#if` 到对应 `//#endif` 中按 Enter 会自动插入 `//$$ `。
- 在 `//$$ @` 后输入时，从条件所选版本模块中补全所有可用 Java 注解，并显示注解图标与包名。
- 在 `//$$ ` 后补全连续的 Java 声明关键字，包括 `private`、`protected`、`public`、`static`、`final`、`abstract`、`void` 等。
- `//$$ @Inject` 处理方法的括号内可根据所选版本目标方法补全参数和 `CallbackInfo` / `CallbackInfoReturnable`。
- `//$$ @Inject` 方法签名不匹配时显示检查警告，并提供 `Fix method signature` 快速修复。
- 在 `//$$` 行输入 `{` 时自动补全配对的 `}`。
- 在任意 `//$$` Java 表达式中，从条件所选版本模块及其依赖补全可见类名，不局限于 `REMSSettings`。
- 注解参数补全直接读取条件版本中真实注解类型声明的方法，因此 Mixin、MixinExtras 和项目自定义注解都使用各自实际属性。
- 输入 `//$$ @` 后立即显示注解候选并优先列出常用 Mixin 注解；注解参数中的 `"` 自动补全配对引号。
- 在 `//$$` 表达式中补全对象成员，包括 `ci.` / `cir.` 的回调方法及代码块内可解析变量的字段和方法。
- 对象成员补全优先从上方方法参数和局部变量声明推断变量类型，支持缩写变量名、泛型、数组与多行参数。
- 多版本模块存在同名类时优先选择条件对应模块中的类；`@At` 的 `value` 结束后正确切换到 `target` 等属性补全。
- 使用括号栈识别嵌套注解；闭合内层 `@At(...)` 后继续补全外层 `@Inject` 的 `cancellable` 等属性。
- 注解上下文跨连续 `//$$` 行解析，支持注解数组、限定注解名、转义字符串以及 `Slice`、`Desc`、`Definition`、`Expression`、`Local`、`Share` 的专属属性。
- 自动候选随每个输入字符刷新；类名补全替换已输入前缀，避免出现 `REMSSeREMSSettings` 等重复文本。
- 类名候选先搜索条件版本模块，再补充整个项目；同名类的成员补全优先使用对应版本的预处理或编译输出。
- 所有 `//$$` 行支持 `()`、`{}`、`[]`、`""` 成对输入，并可越过已有的右半边字符。
- Java 修饰符和基本类型关键字补全后自动添加空格，便于连续输入 `private void` 等声明。
- 类名和成员解析增加 IDEA 全局索引回退，覆盖被项目作用域排除的预处理源码与版本编译类。
- 版本模块直接由 `//#if MC...` 条件解析并贯穿类名、注解和成员补全，不再依赖 Minecraft 库类的模块归属。
- 条件版本内的项目类仅从该版本的预处理源码和版本源码读取；Minecraft 原版、Mixin 与其他库类从同版本模块依赖读取。
- 版本条件从满足表达式的现有模块中选择最低版本；例如 `MC<12101` 使用主分支基准 1.20.1，`MC>=12111` 使用 1.21.11。
- `//$$` 中的类名、局部变量、注解和注解属性支持引用解析与跳转，包括 `REMSSettings`、`CallbackInfo`、`ci`、`@At`、`method`、`value` 和 `target`。
- 引用解析不依赖示例名称：任意注解属性及可推断类型的静态或实例字段、方法均支持跳转。
- 自动插入 `//$$` 仅在已经存在配对 `//#endif` 的预处理块内生效。
- `//$$` 行首同时补全 Java 声明关键字和条件版本可用类，因此局部代码可直接补全 `EnderPearlEntity`、`Vec3d` 等类型。
- 表达式位置补全上方 `//$$` 方法参数和局部变量，并继续根据变量真实类型补全字段与方法。
- 补全 `this`、`super`、`new`、`true`、`false`、`null`；`this.` 与 `super.` 的成员来自条件版本目标类。
- 输入类型后的变量名前缀时，根据类型名称生成候选，例如 `EnderPearlEntity p` 可选择 `pearl`。
- `ci`、`cir` 及其他变量的成员补全均从上方实际声明类型解析，不再按变量名称绑定 `CallbackInfo` 类型。
- 输入 `@` 或一个字母时仅精确查询常用 Mixin/MixinExtras 注解，避免在编辑器线程遍历 Minecraft 和全部依赖；输入至少两个字符后仍会发现项目自定义注解。
- 方法成员补全会用 IDEA Java 解析器判断补全结果是否已经构成完整语句；`ci.c` 可补成 `ci.cancel();`，而条件、参数、括号和仍有后续表达式的位置不会误加分号。
- 输入 `//#` 后补全预处理指令 `if`、`elseif`、`else`、`endif`；`if` 与 `elseif` 会自动追加空格。
- 在 `//#if MC` 或 `//#elseif MC` 后补全预处理器实际支持的比较运算符：`>`、`<`、`>=`、`<=`、`==`、`!=`。
- 普通类中的 `//$$` 注解支持按条件版本跳转；注解属性以及通过静态导入使用的常量也会动态解析，例如 `@Rule`、`categories`、`REMS` 和 `FEATURE`。
- 预处理比较运算符候选显示英文用途说明；在运算符后输入版本数字时，从项目 `build.gradle` 的 `createNode` 声明动态补全版本代码，并显示对应 Minecraft 版本。
- `//$$` 中声明的字段和方法名称可跳转到条件选中版本的 `build/preprocessed/main/java` 真实声明，例如 `MC>=12004` 下的字段会跳转到 `1.21` 模块。
- `//$$` 字段或方法声明仅在声明名称范围显示跳转下划线；执行 `Go to Declaration or Usages` 时直接查找条件版本模块中的源码用法，有多个用法时显示目标列表。
- `//#` 后的 `if`、`elseif`、`else`、`endif` 候选显示对应的英文用途说明。
- 手动输入完整的 `//#if` 或 `//#elseif` 时也会立即追加空格，不要求必须选择补全候选。
- 已写完的多行 `//$$` 注解属性通过括号栈解析所属注解，`categories`、`validators`、`options`、`strict` 等可跳转到真实注解方法。

## 构建

在仓库根目录运行：

```powershell
.\gradlew.bat -p idea-mixin-descriptor-completion buildPlugin
```

产物位于 `idea-mixin-descriptor-completion/build/distributions/`。

## 安装和使用

在 IDEA 中打开 `Settings > Plugins > 齿轮 > Install Plugin from Disk`，选择生成的 ZIP，重启 IDEA。

把光标放在 `method = ""` 或 `target = ""` 的引号内，按 `Ctrl+Space`。

预处理分支同样适用，例如把光标放在下面的空引号内：

```java
//$$ @WrapOperation(method = "",
//$$         at = @At(value = "INVOKE",
//$$                 target = ""))
```

插件依赖当前模块已经正确导入 Minecraft/Yarn 类；首次 Gradle 导入或索引尚未结束时不会显示候选。
