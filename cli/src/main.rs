use std::io::IsTerminal as _;
use std::net::Ipv4Addr;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use clap::{Args, Parser, Subcommand};

use tododrop::server::{AppState, Saved};
use tododrop::tls::TlsIdentity;
use tododrop::{DEFAULT_PORT, config, net, qr, server};

#[derive(Parser)]
#[command(
    name = "tododrop",
    version,
    about = "待办投递 PC 接收端：在局域网内接收手机导出的待办 Markdown",
    after_help = "示例：\n  tododrop                       启动接收，保存到默认目录或当前目录\n  tododrop -o D:\\Notes           保存到指定目录\n  tododrop config --dir D:\\Notes  设置默认保存目录",
    args_conflicts_with_subcommands = true
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Cmd>,
    #[command(flatten)]
    serve: ServeArgs,
}

#[derive(Subcommand)]
enum Cmd {
    /// 查看或设置默认保存目录
    Config(ConfigArgs),
}

#[derive(Args)]
struct ServeArgs {
    /// 保存目录（优先于默认目录配置）
    #[arg(short, long, value_name = "DIR")]
    out: Option<PathBuf>,
    /// 监听端口，被占用时自动换一个
    #[arg(short, long, default_value_t = DEFAULT_PORT)]
    port: u16,
    /// 手动指定写进二维码的 IPv4 地址，可重复使用（默认自动检测）
    #[arg(long = "host", value_name = "IP")]
    hosts: Vec<Ipv4Addr>,
    /// 在手机上显示的电脑名称（默认取主机名）
    #[arg(long)]
    name: Option<String>,
    /// 收到一次后自动退出
    #[arg(long)]
    once: bool,
    /// 不用 ANSI 颜色画二维码（适合深色背景的老式终端）
    #[arg(long)]
    plain: bool,
    /// 二维码黑白反转
    #[arg(long)]
    invert: bool,
    /// 同时打印二维码里的连接链接（调试用，链接里含 token）
    #[arg(long)]
    show_url: bool,
}

#[derive(Args)]
#[group(multiple = false)]
struct ConfigArgs {
    /// 设置默认保存目录
    #[arg(long, value_name = "DIR")]
    dir: Option<PathBuf>,
    /// 显示当前配置（默认行为）
    #[arg(long)]
    show: bool,
    /// 清除默认保存目录
    #[arg(long)]
    reset: bool,
}

/// 终端样式；不支持 ANSI 时原样输出。
#[derive(Clone, Copy)]
struct Term {
    ansi: bool,
}

impl Term {
    fn detect() -> Self {
        let tty = std::io::stdout().is_terminal();
        let no_color = std::env::var_os("NO_COLOR").is_some();
        Self { ansi: tty && !no_color && enable_ansi() }
    }
    fn paint(self, code: &str, s: &str) -> String {
        if self.ansi { format!("\x1b[{code}m{s}\x1b[0m") } else { s.to_string() }
    }
    fn bold(self, s: &str) -> String { self.paint("1", s) }
    fn dim(self, s: &str) -> String { self.paint("2", s) }
    fn green(self, s: &str) -> String { self.paint("32", s) }
    fn yellow(self, s: &str) -> String { self.paint("33", s) }
    fn red(self, s: &str) -> String { self.paint("31", s) }
    fn cyan(self, s: &str) -> String { self.paint("36", s) }
}

#[cfg(windows)]
fn enable_ansi() -> bool {
    enable_ansi_support::enable_ansi_support().is_ok()
}

#[cfg(not(windows))]
fn enable_ansi() -> bool {
    true
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let term = Term::detect();
    let result = match cli.command {
        Some(Cmd::Config(a)) => run_config(a, term),
        None => run_serve(cli.serve, term),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{} {e:#}", term.red("错误："));
            ExitCode::FAILURE
        }
    }
}

fn run_config(a: ConfigArgs, term: Term) -> Result<()> {
    let path = config::config_path()?;
    let mut cfg = config::load(&path)?;
    if let Some(dir) = a.dir {
        let dir = config::absolutize(&dir)?;
        std::fs::create_dir_all(&dir).with_context(|| format!("无法创建目录：{}", dir.display()))?;
        cfg.default_dir = Some(dir.clone());
        config::save(&path, &cfg)?;
        println!("{} 默认保存目录已设为 {}", term.green("✓"), term.bold(&dir.display().to_string()));
    } else if a.reset {
        cfg.default_dir = None;
        config::save(&path, &cfg)?;
        println!("{} 已清除默认保存目录，之后会保存到运行时所在的目录", term.green("✓"));
    } else {
        match &cfg.default_dir {
            Some(d) => println!("默认保存目录：{}", term.bold(&d.display().to_string())),
            None => println!("默认保存目录：{}", term.dim("未设置（保存到运行时所在的目录）")),
        }
    }
    println!("{}", term.dim(&format!("配置文件：{}", path.display())));
    Ok(())
}

fn run_serve(args: ServeArgs, term: Term) -> Result<()> {
    let cfg = config::load(&config::config_path()?)?;
    let (out_dir, dir_source) = config::resolve_out_dir(args.out.as_deref(), &cfg)?;

    let hosts: Vec<net::Candidate> = if args.hosts.is_empty() {
        net::candidates(4)?
    } else {
        args.hosts.iter().map(|ip| net::Candidate { iface: "手动指定".into(), ip: *ip, rank: 0 }).collect()
    };
    if hosts.is_empty() {
        anyhow::bail!("没有找到可用的局域网 IPv4 地址。请确认电脑已连上 Wi-Fi 或有线网络，或用 --host 手动指定");
    }

    let name: String = args
        .name
        .clone()
        .unwrap_or_else(|| gethostname::gethostname().to_string_lossy().into_owned())
        .chars()
        .take(qr::MAX_NAME_CHARS)
        .collect();
    let identity = TlsIdentity::generate()?;
    let token = tododrop::generate_token()?;
    let listener = server::bind_listener(args.port).context("无法监听端口")?;
    let port = listener.local_addr()?.port();

    let payload = qr::Payload {
        hosts: hosts.iter().map(|c| c.ip).collect(),
        port,
        token: token.clone(),
        fingerprint_b64: identity.fingerprint_b64(),
        name: name.clone(),
    };
    let url = payload.to_url();
    let style = if args.plain || !term.ansi { qr::Style::Plain } else { qr::Style::Ansi };
    let qr_text = qr::render(&url, style, args.invert)?;

    // ---- 终端输出 ----
    println!();
    println!("  {} {}", term.bold("待办投递 TodoDrop 接收端"), term.dim(&format!("v{}", env!("CARGO_PKG_VERSION"))));
    println!();
    println!("  保存目录  {}  {}", term.bold(&out_dir.display().to_string()), term.dim(&format!("（{}）", dir_source.label())));
    println!("  电脑名称  {name}");
    for (i, c) in hosts.iter().enumerate() {
        let label = if i == 0 { "  监听地址  " } else { "            " };
        println!("{label}{}  {}", term.cyan(&format!("https://{}:{port}", c.ip)), term.dim(&format!("[{}]", c.iface)));
    }
    println!("  证书指纹  {}", term.dim(&identity.fingerprint_short()));
    if port != args.port {
        println!("  {}", term.yellow(&format!("端口 {} 已被占用，已改用 {port}", args.port)));
    }
    println!();
    print!("{qr_text}");
    println!();
    if args.show_url {
        println!("  {}", term.dim(&url));
        println!();
    }
    println!("  {}", term.dim(if args.once { "用 App 扫码发送，收到一次后退出" } else { "用 App 扫码发送，Ctrl+C 退出" }));
    println!();

    // ---- 启动服务 ----
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;
    rt.block_on(async move {
        let handle = axum_server::Handle::new();

        let once = args.once;
        let shutdown = handle.clone();
        let on_saved = Arc::new(move |s: &Saved| {
            print_saved(s, term);
            if once {
                let h = shutdown.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(300)).await;
                    h.graceful_shutdown(Some(Duration::from_secs(2)));
                });
            }
        });

        let ctrl_c = handle.clone();
        tokio::spawn(async move {
            if tokio::signal::ctrl_c().await.is_ok() {
                println!();
                ctrl_c.graceful_shutdown(Some(Duration::from_secs(1)));
            }
        });

        let state = Arc::new(AppState { token, name, out_dir, on_saved });
        server::serve(listener, identity.server_config()?, state, handle).await.context("服务器异常退出")?;
        println!("  {}", term.dim("已退出"));
        Ok(())
    })
}

/// 打印一次接收的日志：每个文件一行；有提醒时在下面用醒目的颜色列出来。
fn print_saved(s: &Saved, term: Term) {
    let time = chrono::Local::now().format("%H:%M:%S").to_string();
    let device = s.device.as_deref().filter(|d| !d.trim().is_empty()).unwrap_or("手机");
    let total: usize = s.files.iter().map(|f| f.count).sum();
    println!(
        "  {} 收到「{device}」的 {total} 条待办，保存为 {} 个文件：",
        term.dim(&format!("[{time}]")),
        s.files.len()
    );
    for f in &s.files {
        println!(
            "      {}  {} 条 → {}  {}",
            term.bold(&f.project),
            f.count,
            term.green(&f.path.display().to_string()),
            term.dim(&format!("{} 字节", f.bytes))
        );
    }
    if !s.notes.is_empty() {
        println!();
        println!("  {}", term.yellow(&term.bold(&format!("(!) 提醒 {} 条（不写入文件）", s.notes.len()))));
        for n in &s.notes {
            println!("      {} {}", term.dim(&format!("[{}]", n.path.join(" / "))), term.yellow(&n.content));
        }
    }
    println!();
}
