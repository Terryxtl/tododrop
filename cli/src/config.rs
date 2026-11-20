//! 配置文件与保存目录解析。
//!
//! 配置文件位置：`<系统配置目录>/tododrop/config.toml`
//! （Windows 上是 `%APPDATA%\tododrop\config.toml`），
//! 可以用环境变量 `TODODROP_CONFIG` 指定其他路径。

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

/// 配置文件内容。
#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Config {
    /// 默认保存目录。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default_dir: Option<PathBuf>,
}

/// 保存目录的来源，用于在终端里说明。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirSource {
    /// 命令行 `-o` 参数
    Arg,
    /// 配置文件里的 `default_dir`
    Config,
    /// 当前工作目录
    CurrentDir,
}

impl DirSource {
    pub fn label(self) -> &'static str {
        match self {
            DirSource::Arg => "来自 -o 参数",
            DirSource::Config => "来自默认目录配置",
            DirSource::CurrentDir => "当前目录",
        }
    }
}

/// 配置文件路径。
pub fn config_path() -> Result<PathBuf> {
    if let Some(p) = std::env::var_os("TODODROP_CONFIG") {
        return Ok(PathBuf::from(p));
    }
    let base = directories::BaseDirs::new().context("无法确定系统配置目录")?;
    Ok(base.config_dir().join("tododrop").join("config.toml"))
}

/// 读取配置；文件不存在时返回默认值。
pub fn load(path: &Path) -> Result<Config> {
    match fs::read_to_string(path) {
        Ok(s) => toml::from_str(&s).with_context(|| format!("配置文件格式错误：{}", path.display())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Config::default()),
        Err(e) => Err(e).with_context(|| format!("无法读取配置文件：{}", path.display())),
    }
}

/// 写入配置。
pub fn save(path: &Path, cfg: &Config) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("无法创建配置目录：{}", parent.display()))?;
    }
    let s = toml::to_string_pretty(cfg).context("无法序列化配置")?;
    fs::write(path, s).with_context(|| format!("无法写入配置文件：{}", path.display()))
}

/// 把路径转成绝对路径（不解析符号链接，避免 Windows 上出现 `\\?\` 前缀）。
pub fn absolutize(p: &Path) -> Result<PathBuf> {
    std::path::absolute(p).with_context(|| format!("无效的路径：{}", p.display()))
}

/// 按优先级解析保存目录：`-o` 参数 > 配置文件 > 当前目录；目录不存在时自动创建。
pub fn resolve_out_dir(arg: Option<&Path>, cfg: &Config) -> Result<(PathBuf, DirSource)> {
    let (dir, source) = if let Some(a) = arg {
        (a.to_path_buf(), DirSource::Arg)
    } else if let Some(d) = &cfg.default_dir {
        (d.clone(), DirSource::Config)
    } else {
        (std::env::current_dir().context("无法获取当前目录")?, DirSource::CurrentDir)
    };
    let dir = absolutize(&dir)?;
    fs::create_dir_all(&dir).with_context(|| format!("无法创建保存目录：{}", dir.display()))?;
    if !dir.is_dir() {
        anyhow::bail!("保存路径不是目录：{}", dir.display());
    }
    Ok((dir, source))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_config_is_default() {
        let tmp = tempfile::tempdir().unwrap();
        let cfg = load(&tmp.path().join("nope.toml")).unwrap();
        assert_eq!(cfg, Config::default());
    }

    #[test]
    fn roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("sub").join("config.toml");
        let cfg = Config { default_dir: Some(PathBuf::from(r"D:\Notes\待办")) };
        save(&path, &cfg).unwrap();
        assert_eq!(load(&path).unwrap(), cfg);
    }

    #[test]
    fn priority_arg_over_config_over_cwd() {
        let tmp = tempfile::tempdir().unwrap();
        let arg_dir = tmp.path().join("arg");
        let cfg_dir = tmp.path().join("cfg");
        let cfg = Config { default_dir: Some(cfg_dir.clone()) };

        let (d, s) = resolve_out_dir(Some(&arg_dir), &cfg).unwrap();
        assert_eq!((d, s), (arg_dir.clone(), DirSource::Arg));
        assert!(arg_dir.is_dir(), "应自动创建目录");

        let (d, s) = resolve_out_dir(None, &cfg).unwrap();
        assert_eq!((d, s), (cfg_dir, DirSource::Config));

        let (_, s) = resolve_out_dir(None, &Config::default()).unwrap();
        assert_eq!(s, DirSource::CurrentDir);
    }
}
