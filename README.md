<p align="center"><strong>TieBaTime</strong></p>
<div align="center">
    <a href="https://github.com/neveragain233/TiebaLite/blob/main/LICENSE">
        <img alt="License" src="https://img.shields.io/github/license/neveragain233/TiebaLite">
    </a>
    <br/>
    <br/>
    <p>基于 TiebaLite 与 TiebaShelf 合并修改的贴吧<strong>本地化</strong>客户端</p>
</div>

## 这是什么

TieBaTime 是一个贴吧第三方客户端，在 [TiebaLite](https://github.com/neveragain233/TiebaLite) 的基础上，融合了 [TiebaShelf](https://github.com/xia-tian-wu/TiebaShelf) 的核心能力——**帖子本地备份与离线阅读**。

### 主要特性

- **帖子本地备份**：帖子菜单一键「备份到本地」，支持完整版 / 只看楼主
- **离线阅读**：备份后的帖子无网络也能完整阅读，自动记忆阅读位置
- **收藏批量备份**：收藏页一键批量备份，队列逐个进行，已备份的自动增量更新
- **四种格式导出**：TiebaShelf 包（可再导入）/ PDF / EPUB / TXT，备份的帖子可转成电子书
- **朗读听书**：长按楼层或三点菜单「朗读全文」，只读楼主发言；原生媒体控件控制播放、跳楼、定时停止
- **字体导入**：支持导入 TTF 字体文件，全局应用
- **图片查看增强**：点击放大可在全帖图片间滑动，支持旋转 / 镜像

## 下载

前往 [Releases](../../releases) 下载最新的 APK。

## 构建说明

1. 克隆本仓库
2. 使用 Android Studio（Koala 或更新版本）打开
3. 等待 Gradle 同步完成后，运行 `app` 的 `debug` 构建即可

命令行构建：

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug-dev.apk
```

要求：JDK 17+、Android SDK（compileSdk 与 `app/build.gradle.kts` 中一致）。

## 使用提示

- 备份与离线阅读入口：**我的 → 本地备份帖子**；设置项在 **设置 → 本地备份帖子**
- 收藏后自动备份默认关闭，可在引导页或设置中开启
- 朗读使用系统 TTS 引擎，请在系统设置中确保已安装文字转语音引擎

## 致谢

本项目的诞生离不开以下开源项目：

- [TiebaLite](https://github.com/neveragain233/TiebaLite) — 本应用的上游基线（GPL-3.0）
- [TiebaShelf](https://github.com/xia-tian-wu/TiebaShelf) — 帖子备份与导出思路来源

## 免责声明

本软件及源码仅供学习交流使用，严禁用于商业用途。使用本软件登录百度账号存在账号被处置的风险，请自行承担。本项目为独立的开源第三方软件，与北京百度网讯科技有限公司及其关联公司无任何关联、合作、授权或背书等关系。

## 许可证

[GPL-3.0](LICENSE)
