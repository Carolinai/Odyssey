# Odyssey 前端样式改造 · 评审记录（2026-10-29）

## 方向
- 风格：`achromatic-ledger`（light，kind=devtools，seed 1260603226），`--keep palette,type`（保留品牌蓝与中文字体栈）
- 重绘记录：Shell 由方案抽到的"无侧栏顶栏"改为**保留现有侧栏骨架**（restyle 约束：保留布局与收起/用户卡片等交互）
- Face：蓝灰底 `#f2f4f7`（账本/仪表感）；品牌蓝映射为"链接/数据"唯一色相（文字用加深后的 `#1f6391`，对比度 6.4:1）

## 改造前主要问题（lint 2 FAIL + 7 WARN；检查 9 页共 10 FAIL）
1. 深蓝侧栏 + 品牌蓝铺满全部 chrome（导航/按钮/tab/返回链接）
2. 对比度大量不达标（#999/#888 灰字；菜单页"恢复默认"白字浅灰底 1.1:1）
3. 饱和实底徽标遍地、毛玻璃操作栏、emoji 当 UI（👁）、正文 12px、env 页 768px 横向溢出 92px、index 无 h1

## 改造内容
- Token 化全部颜色（`:root` 变量）；表面 = 蓝灰底 + 白卡片 + 1px 边线，无阴影无毛玻璃
- 侧栏转浅色、灰胶囊选中；主按钮近黑；危险按钮描边红；tab 改下划线式（墨色指示线）
- 全部徽标（badge/branch-env/audit-event）改"浅底 + 同色深字"；用户标签色自动压暗至白字 4.5:1
- 眼睛 emoji → SVG 图标；h1 统一 24px；正文 14px；审计行 14px；焦点环统一
- 手机端：侧栏折叠为顶部 48px 图标条；审计行/表单行流式换行，修复 390px 溢出

## 最终检查（9 页全部 0 FAIL）
剩余 WARN 及理由：
- `hero-void`（audit/decrypt/menus/personal/login/index）：数据驱动页面，内容止于数据处（如审计仅 1 条），不虚构内容
- `below-fold`（index）：卡片列表天然较长，首屏含搜索行 + 首张完整卡片
- `unused-canvas`（personal）：4 行信息卡的短内容
- lint WARN：等宽字体仅用于分支名（代码标识）；阴影仅存在于真实悬浮层（底部操作栏、图片灯箱）与输入框 focus 环

## 遗留说明
- 改动已同步至 `target/classes/static/`（运行中的应用即时生效）；重新打包会自动从源码覆盖
- .taste-skill/before-* 与 after-* 保留改造前后对比截图
