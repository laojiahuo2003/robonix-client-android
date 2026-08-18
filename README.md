# Robonix Android Client

## 项目结构

```
robonix-client-android/
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
├── app/
│   ├── build.gradle.kts          # 应用构建配置 (Compose, gRPC, Hilt, Room)
│   ├── proguard-rules.pro        # ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 权限 + Activity + Service
│       ├── proto/                # 7个 .proto 文件 (从桌面版重构)
│       ├── res/values/           # strings.xml, themes.xml, colors.xml
│       └── java/com/robonix/client/
│           ├── RobonixApp.kt     # Hilt Application
│           ├── MainActivity.kt   # 单 Activity，Compose 入口
│           ├── di/AppModule.kt   # Hilt DI 模块
│           ├── data/
│           │   ├── grpc/         # gRPC 客户端 (Atlas/Liaison/Executor)
│           │   ├── audio/        # AudioRecord/Player/Bridge + ForegroundService
│           │   ├── local/        # DataStore 持久化
│           │   └── model/        # 数据模型 (14 个 data class)
│           ├── domain/           # 4 个 Repository
│           └── ui/
│               ├── theme/        # 暗色主题 (复用桌面版色板)
│               ├── navigation/   # BottomNav + NavHost
│               ├── chat/         # ChatScreen + RtdlScreen + ViewModels
│               ├── audio/        # AudioScreen + ViewModel
│               ├── settings/     # SettingsScreen + ViewModel
│               └── components/   # StatusChip 共用组件
```

## 构建 APK

```bash
# 需要 Android Studio 或命令行 SDK
cd robonix-client-android

# Debug APK
./gradlew assembleDebug

# Release APK (需要签名配置)
./gradlew assembleRelease
```

APK 输出位置: `app/build/outputs/apk/debug/app-debug.apk`

## 注意事项

1. **构建环境**: 需要 Android Studio Hedgehog (2023.1.1) 或更新版本，JDK 17
2. **Proto 编译**: gradle protobuf 插件会在构建时自动编译 .proto 文件生成 Java stub
3. **首次构建**: 需要下载大量依赖，可能需要 10-20 分钟
4. **真机测试**: 手机需与 Robonix Atlas 在同一网络 (WiFi)，输入 Robot Host IP 即可
