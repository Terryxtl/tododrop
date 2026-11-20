//! 把渲染好的 Markdown 写成 `TODOS_<项目名>_yyyyMMdd_HHmmss.md`。

use std::fs::OpenOptions;
use std::io::{self, Write as _};
use std::path::{Path, PathBuf};

use chrono::{DateTime, TimeZone};

/// 同名文件最多尝试的序号。
const MAX_SUFFIX: u32 = 999;

/// 文件名里项目名部分的最大字符数。
const MAX_NAME_CHARS: usize = 40;

/// 把项目名变成可以放进文件名的形式：Windows 不允许的字符和控制字符换成 `_`，
/// 空白合并成一个空格，去掉首尾的空格和点，过长时截断；结果为空时用「未命名」。
pub fn sanitize(name: &str) -> String {
    let mut out = String::new();
    for c in name.chars() {
        let c = match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if c.is_control() || c.is_whitespace() => ' ',
            c => c,
        };
        if c == ' ' && out.ends_with(' ') {
            continue;
        }
        out.push(c);
    }
    let trimmed: String = out.trim_matches([' ', '.']).chars().take(MAX_NAME_CHARS).collect();
    let trimmed = trimmed.trim_end_matches([' ', '.']);
    if trimmed.is_empty() { "未命名".to_string() } else { trimmed.to_string() }
}

/// 文件名主体，例如 `TODOS_工作_20261119_153045`。
pub fn base_name<Tz: TimeZone>(project: &str, t: &DateTime<Tz>) -> String
where
    Tz::Offset: std::fmt::Display,
{
    format!("TODOS_{}_{}", sanitize(project), t.format("%Y%m%d_%H%M%S"))
}

/// 在 `dir` 下新建文件并写入 `content`，遇到重名依次加 `_2`、`_3`……
/// 用 `create_new` 打开，保证不会覆盖已有文件，并发写入也不会撞名。
pub fn save<Tz: TimeZone>(dir: &Path, project: &str, time: &DateTime<Tz>, content: &str) -> io::Result<PathBuf>
where
    Tz::Offset: std::fmt::Display,
{
    let base = base_name(project, time);
    for i in 1..=MAX_SUFFIX {
        let name = if i == 1 { format!("{base}.md") } else { format!("{base}_{i}.md") };
        let path = dir.join(name);
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(mut f) => {
                let res = f.write_all(content.as_bytes()).and_then(|_| f.sync_all());
                if let Err(e) = res {
                    drop(f);
                    let _ = std::fs::remove_file(&path);
                    return Err(e);
                }
                return Ok(path);
            }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::other(format!("同名文件过多：{base}")))
}

/// 依次保存多个文件 `(项目名, 内容)`。中途失败时删掉这一批里已经写好的文件，
/// 保证要么全部保存、要么一个都不留。
pub fn save_all<Tz: TimeZone>(dir: &Path, time: &DateTime<Tz>, files: &[(String, String)]) -> io::Result<Vec<PathBuf>>
where
    Tz::Offset: std::fmt::Display,
{
    let mut saved = Vec::with_capacity(files.len());
    for (project, content) in files {
        match save(dir, project, time, content) {
            Ok(p) => saved.push(p),
            Err(e) => {
                for p in &saved {
                    let _ = std::fs::remove_file(p);
                }
                return Err(e);
            }
        }
    }
    Ok(saved)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{FixedOffset, TimeZone};

    fn t() -> DateTime<FixedOffset> {
        FixedOffset::east_opt(8 * 3600).unwrap().with_ymd_and_hms(2026, 11, 19, 15, 30, 45).unwrap()
    }

    #[test]
    fn name_format() {
        assert_eq!(base_name("工作", &t()), "TODOS_工作_20261119_153045");
    }

    #[test]
    fn sanitizes_project_names() {
        assert_eq!(sanitize("工作 [重要]"), "工作 [重要]");
        assert_eq!(sanitize("a/b\\c:d*e?f\"g<h>i|j"), "a_b_c_d_e_f_g_h_i_j");
        assert_eq!(sanitize("  多个   空格\t和\n换行 "), "多个 空格 和 换行");
        assert_eq!(sanitize("..隐藏. "), "隐藏");
        assert_eq!(sanitize(" . "), "未命名");
        assert_eq!(sanitize(""), "未命名");
        assert_eq!(sanitize(&"长".repeat(100)).chars().count(), MAX_NAME_CHARS);
    }

    #[test]
    fn saves_and_suffixes_on_collision() {
        let tmp = tempfile::tempdir().unwrap();
        let content = "- [ ] 写周报\n  第二行\n";
        let a = save(tmp.path(), "工作", &t(), content).unwrap();
        let b = save(tmp.path(), "工作", &t(), "b").unwrap();
        let c = save(tmp.path(), "工作", &t(), "c").unwrap();
        let d = save(tmp.path(), "生活", &t(), "d").unwrap();
        assert_eq!(a.file_name().unwrap(), "TODOS_工作_20261119_153045.md");
        assert_eq!(b.file_name().unwrap(), "TODOS_工作_20261119_153045_2.md");
        assert_eq!(c.file_name().unwrap(), "TODOS_工作_20261119_153045_3.md");
        assert_eq!(d.file_name().unwrap(), "TODOS_生活_20261119_153045.md");
        assert_eq!(std::fs::read(&a).unwrap(), content.as_bytes());
        assert_eq!(std::fs::read_to_string(&c).unwrap(), "c");
    }

    #[test]
    fn save_all_writes_every_file_or_none() {
        let tmp = tempfile::tempdir().unwrap();
        let files = vec![("工作".to_string(), "a".to_string()), ("工作".to_string(), "b".to_string())];
        let paths = save_all(tmp.path(), &t(), &files).unwrap();
        assert_eq!(paths.len(), 2);
        assert_eq!(paths[1].file_name().unwrap(), "TODOS_工作_20261119_153045_2.md");

        // 目录不存在时第一个就失败，什么都不留
        let missing = tmp.path().join("missing");
        assert!(save_all(&missing, &t(), &files).is_err());
        assert!(!missing.exists());
    }
}
