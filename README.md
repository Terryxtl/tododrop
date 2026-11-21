# TodoDrop（待办投递）

一个按「项目」分类的 Android 待办备忘录，外加一个 PC 端命令行接收器。

- **Android App「待办投递」**：待办按项目归类，项目下可以再建子项目（最多 3 层），每条只有正文（可多行）。除了普通待办，还可以记「提醒」——只给人看，不写进导出的文件。可以跨项目勾选未完成的待办，扫描 PC 终端上的二维码，通过局域网发到电脑上；发送成功后这些待办自动标为已完成。
- **PC CLI `tododrop`**：用 Rust 写的单文件程序。启动后在局域网内开一个 HTTPS 服务器，在终端里画出二维码；手机扫码后把原始数据发过来，CLI 包装成 Markdown，每个顶层项目保存为一个 `TODOS_<项目名>_yyyyMMdd_HHmmss.md`，提醒则直接显示在终端里。

传输方式参考 [LocalSend](https://localsend.org/)：HTTPS、自签证书、手机端校验证书指纹。区别是用扫码代替了组播发现，扫码本身就是授权，电脑端不需要再点确认。

## 仓库结构

```
TODO/
├── README.md            本文件
├── docs/PROTOCOL.md     传输协议（两端都照它实现）
├── cli/                 PC 接收端，Rust crate "tododrop"
│   ├── src/
│   │   ├── main.rs      命令行参数、终端输出
│   │   ├── config.rs    配置文件、保存目录解析
│   │   ├── net.rs       枚举并筛选本机 IPv4 地址
│   │   ├── tls.rs       自签证书和指纹
│   │   ├── qr.rs        二维码内容和终端字符画
│   │   ├── server.rs    HTTPS 接口
│   │   ├── markdown.rs  把项目树包装成 Markdown、收集提醒
│   │   └── storage.rs   文件名、写文件、重名处理
│   └── tests/e2e.rs     端到端测试（真实 HTTPS + 指纹校验）
└── android/             Android 工程（Gradle）
    └── app/src/main/java/com/terryxtl/tododrop/
        ├── data/        Room 数据库、项目树
        ├── export/      把选中的待办组织成要发送的项目树
        ├── transfer/    二维码解析、指纹固定的 HTTPS 客户端
        ├── backup/      JSON 备份与恢复
        └── ui/          Compose 界面
```

## 下载

到 [Releases](https://github.com/Terryxtl/tododrop/releases) 下载编译好的文件：

| 文件 | 说明 |
|---|---|
| `TodoDrop-vX.Y.Z-arm64.apk` | Android App（arm64-v8a，Android 9+） |
| `tododrop-vX.Y.Z-windows-x64.zip` | Windows CLI |
| `tododrop-vX.Y.Z-linux-x64.tar.gz` / `linux-arm64` | Linux CLI（musl 静态链接，不依赖 glibc 版本） |
| `tododrop-vX.Y.Z-macos-arm64.tar.gz` / `macos-x64` | macOS CLI（未签名，首次运行需在「隐私与安全性」里允许，或执行 `xattr -d com.apple.quarantine tododrop`） |
| `SHA256SUMS.txt` | 校验和 |

## PC 端 CLI

### 编译

需要 Rust 1.88 或更新的版本。加密后端用的是 ring，Windows 上编译不需要 NASM。

```bash
cd cli
cargo build --release
```

产物在 `cli/target/release/tododrop.exe`（其他系统上没有 `.exe` 后缀）。可以把它复制到 `PATH` 里的任意目录。

运行测试：

```bash
cargo test
```

### 使用

直接运行就会启动接收：

```bash
tododrop                  # 保存到默认目录；没设默认目录就保存到当前目录
tododrop -o D:\Notes      # 保存到指定目录
tododrop --once           # 收到一次后自动退出
```

终端会显示版本、保存目录（及其来源）、电脑名称、监听地址、证书短指纹和二维码。用手机 App 扫码即可发送。默认可以连续接收多次，按 `Ctrl+C` 退出。

#### 参数

| 参数 | 说明 |
|---|---|
| `-o, --out <DIR>` | 保存目录，优先于默认目录配置。目录不存在时自动创建 |
| `-p, --port <PORT>` | 监听端口，默认 `53320`。端口被占用时自动换成系统分配的随机端口，并在终端提示 |
| `--host <IP>` | 手动指定写进二维码的 IPv4 地址，可以重复使用多次。指定后不再自动检测 |
| `--name <NAME>` | 在手机上显示的电脑名称，默认取主机名，最长 32 个字符 |
| `--once` | 成功收到一次后自动退出 |
| `--plain` | 不用 ANSI 颜色画二维码 |
| `--invert` | 二维码黑白反转 |
| `--show-url` | 同时打印二维码里的连接链接（调试用，链接里含 token，注意别泄露） |
| `-h, --help` | 显示帮助 |
| `-V, --version` | 显示版本 |

服务器监听 `0.0.0.0`。不加 `--host` 时会自动挑选最多 4 个 IPv4 地址写进二维码：

- 排除环回地址、`169.254.x.x`、`198.18.0.0/15`（代理软件 TUN 模式常用）
- 排除名字里带 vEthernet、WSL、Hyper-V、VMware、VirtualBox、Docker、蓝牙等关键词的虚拟网卡
- 普通网卡上的私有地址排在最前；ZeroTier、Tailscale、WireGuard 等组网 / VPN 网卡的地址排在后面但保留

手机会同时尝试这几个地址，用第一个连得上的。

#### 二维码显示

- 默认用半块字符 `▀` 加 ANSI 前景色和背景色，固定画成**白底黑码**，四周留 4 个模块的静区，深色和浅色终端都能扫。
- 以下情况会自动改用无颜色的 `--plain` 风格：输出不是终端（例如重定向到文件）、设置了环境变量 `NO_COLOR`、Windows 上无法开启 ANSI 支持。
- `--plain` 风格用终端的前景色画「浅色」模块，适合**深色背景**的终端。浅色背景的终端请用 `--plain --invert`。
- 默认风格下加 `--invert` 会变成黑底白码。部分扫码器不认反色二维码，一般不需要加。

#### 配置默认保存目录

```bash
tododrop config --dir D:\Notes   # 设置默认保存目录（会转成绝对路径，目录不存在时自动创建）
tododrop config --show           # 查看当前配置（不带参数时也是这个行为）
tododrop config --reset          # 清除默认保存目录
```

配置文件位置：

- Windows：`%APPDATA%\tododrop\config.toml`
- 其他系统：系统配置目录下的 `tododrop/config.toml`（例如 Linux 的 `~/.config/tododrop/config.toml`）
- 可以用环境变量 `TODODROP_CONFIG` 指定其他路径

内容只有一项：

```toml
default_dir = 'D:\Notes'
```

**保存目录优先级**：`-o` 参数 > 配置文件里的 `default_dir` > 当前工作目录。

#### 文件名

- 每个顶层项目保存为一个文件，格式为 `TODOS_<项目名>_yyyyMMdd_HHmmss.md`，例如 `TODOS_工作_20261119_153045.md`，时间取电脑**收到时**的本地时间。子项目的待办写在顶层项目的文件里，用 `##` / `###` 标题分节。
- 项目名里 Windows 不允许的字符（`<>:"/\|?*`）换成 `_`，最长 40 个字符。
- 同名文件已存在时依次命名为 `..._2.md`、`_3.md`……不会覆盖已有文件。一次发送的多个文件要么全部保存，要么一个都不留。
- 内容按 UTF-8 写入，不加 BOM，换行符为 `\n`。

每次收到都会在终端打出日志；有提醒时用黄色列在下面（提醒不写进文件），例如：

```
[15:30:45] 收到「Xiaomi 14」的 5 条待办，保存为 2 个文件：
    工作  4 条 → D:\Notes\TODOS_工作_20261119_153045.md  96 字节
    生活  1 条 → D:\Notes\TODOS_生活_20261119_153045.md  17 字节

(!) 提醒 1 条（不写入文件）
    [工作 / 后端] 先和前端对一下接口
```

#### Windows 防火墙

第一次运行时 Windows 可能会弹出防火墙窗口，请勾选允许 **「专用网络」** 访问。如果当时点了取消，手机会连不上，可以到「Windows 安全中心 → 防火墙和网络保护 → 允许应用通过防火墙」里给 `tododrop.exe` 勾上专用网络。另外请确认当前网络在 Windows 里被设成了「专用网络」，而不是「公用网络」。

## Android App

### 编译与安装

- 只打包 `arm64-v8a`，最低支持 Android 9（minSdk 28），targetSdk 36
- release 包开启 R8 混淆；本地编译默认用 debug 签名，方便直接安装（设置了 `TODODROP_KEYSTORE` 等环境变量时改用正式签名，见下方「发布」）

```bash
cd android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Windows 的 cmd / PowerShell 里用 `gradlew.bat :app:assembleRelease`。

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

### 主要功能

- 首页是项目卡片列表，显示每个项目（含子项目）的未完成数；首次启动预置「收件箱」，可以改名或删除
- 子项目：长按项目卡片选「添加子项目」，最多 3 层；子项目在父项目展开后缩进显示，可以在同级之间拖拽排序。删除项目会连同子项目一起删除
- 项目内：添加多行待办、拖拽排序、右滑完成、左滑删除（带撤销），已完成的折叠在底部
- 提醒：输入行左边的图标点一下切换成「提醒」（带圈的感叹号）再添加；编辑时也可以在「待办 / 提醒」之间切换。提醒没有复选框、不能完成，导出时不写进 Markdown，发送后在电脑终端和手机上显示出来，提醒本身留在 App 里
- 导出：跨项目勾选未完成的待办，预览电脑上会生成的文件，扫码发送；**发送成功后这些待办自动标为已完成**（在「已完成」里可以恢复）
- 导出历史：查看每次发送的时间、电脑、保存的文件和每个文件里的待办、当时的提醒
- 备份与恢复：导出 / 导入 JSON 文件（备份格式 v2，包含子项目和提醒；旧的 v1 备份也能导入）

## 使用流程

1. 在电脑上运行 `tododrop`，终端里出现二维码。
2. 手机和电脑连同一个局域网（同一个 Wi-Fi，或同在 ZeroTier / Tailscale 等虚拟局域网里）。
3. 在 App 里导出：
   - **快速导出**：在首页把项目卡片**从左往右滑**，直接打开相机，扫码发送这个项目（含子项目）的全部未完成待办。
   - **挑选导出**：在首页点「导出」（或长按项目卡片选「导出此项目」），勾选要发送的待办，点「扫码发送」。
4. 手机显示保存的文件名和提醒，发出去的待办标为已完成；电脑终端打出日志，保存目录里出现新文件。

每个顶层项目一个文件。顶层项目自己的待办是开头的平铺任务列表（不写项目名，项目名在文件名里），子项目、孙项目用 `##`、`###` 标题分节，多行正文从第二行起缩进 2 格。例如 `TODOS_工作_20261119_153045.md`：

```markdown
- [ ] 写周报
- [ ] 修复登录页崩溃
  第二行正文缩进续写

## 后端

- [ ] 接口文档

### 数据库

- [ ] 加索引
```

## 发布

GitHub Actions 配置在 `.github/workflows/`：

- `ci.yml`：推送到 `main` 或提 PR 时，在 Linux / Windows / macOS 上跑 `cargo test`，并跑 Android 单元测试、编译 debug APK。
- `release.yml`：推送 `v*` 标签时编译 5 个平台的 CLI 和 release APK，生成 `SHA256SUMS.txt`，发布到 Releases。带 `-` 的标签（如 `v0.3.0-beta.1`）发布为预发布版本。

发新版本：

1. 同时修改 `cli/Cargo.toml` 的 `version` 和 `android/app/build.gradle.kts` 的 `versionName`（以及递增 `versionCode`），在 `cli/` 下跑一次 `cargo build` 更新 `Cargo.lock`。
2. 提交后打标签并推送：

   ```bash
   git tag v0.3.0
   git push origin main v0.3.0
   ```

标签和两处版本号不一致时，工作流会直接失败。

### APK 签名

在仓库 Settings → Secrets and variables → Actions 里配置以下 4 个 Secret，CI 就会用正式证书签名 APK：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 release.keystore`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

没配置时 APK 用 CI 临时生成的 debug 证书签名，每次构建的证书都不同，新版本不能覆盖安装旧版本。

## 传输协议

完整协议见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。概要：

- 二维码内容：`tododrop://v1?h=<IP 列表>&p=<端口>&t=<token>&f=<证书指纹>&n=<电脑名>`
- `GET /api/v1/info`：探测地址是否可达、token 是否正确，比对协议版本（当前为 2）
- `POST /api/v2/todos`：上传项目树原始数据（待办原文、提醒、子项目），CLI 生成 Markdown，成功返回 `201 {"files": [{"project", "savedAs", "count"}], "notes": n}`
- 出错时返回 `{"error": "..."}`：`400` 请求体不合法，`401` token 错误，`413` 超过 2 MiB，`500` 写文件失败
- App 和 CLI 需要同时升级：协议版本不一致时 App 会提示「版本不兼容」

## 安全模型

- **自签证书**：CLI 每次启动都用 rcgen 生成新的 ECDSA P-256 自签证书，只用于本次运行，不落盘。
- **证书指纹固定**：二维码里带着证书 DER 的 SHA-256 指纹。手机端不走系统的 CA 校验，而是计算服务器证书的 SHA-256 并和二维码里的指纹做常量时间比较，不一致就中止连接。所以即使局域网里有人冒充电脑，也拿不到待办内容。终端里显示的「证书指纹」是指纹前 4 个字节，可用于人工核对。
- **一次性 token**：每次启动生成 16 字节随机 token，只出现在二维码里。所有请求都必须带 `Authorization: Bearer <token>`，服务器用常量时间比较，不对就返回 `401`。token 在本次运行期间一直有效，CLI 退出后作废；重启 CLI 后旧二维码失效，需要重新扫码。
- **扫码即授权**：能扫到屏幕上二维码的人才能发送，电脑端不再弹窗确认。不要把带 `--show-url` 打印出来的链接发给别人。
- 请求体上限 2 MiB；文件用「新建且不覆盖」的方式写入，不会覆盖保存目录里已有的文件。
